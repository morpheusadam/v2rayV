/**
 * A first-party mirror for the subscription lists, so that reaching them does not depend on
 * reaching GitHub.
 *
 * The app already walks a ladder of CDN mirrors when raw.githubusercontent.com is blocked, but
 * every rung on that ladder — jsDelivr, raw.githack, gitCDN — reads from GitHub too. They are
 * four front doors to one room: they survive GitHub being *blocked from the user's network*,
 * and none of them survives GitHub being unreachable from the CDN, the repository moving, or
 * jsDelivr itself being blocked, which has happened repeatedly in exactly the countries this
 * app is written for.
 *
 * This is a different room. It answers from R2, and refreshes R2 from GitHub in the background.
 * A client that can reach this host gets an answer even when GitHub is having a bad day, and an
 * answer that is at worst STALE_AFTER_MS old rather than no answer at all.
 *
 * URL shape deliberately copies jsDelivr's:
 *
 *     /gh/{user}/{repo}@{ref}/{path...}
 *
 * so the app builds every rung of the ladder from one rewrite rule, and so this can be dropped
 * in front of, or removed from, that ladder without touching the parsing.
 */

/** Older than this and a copy is refreshed before it is served. */
const FRESH_FOR_MS = 30 * 60 * 1000;

/**
 * Older than this and a copy is refused rather than served, unless GitHub is also unreachable.
 * A very old subscription list is mostly dead servers, and handing one over wastes minutes of
 * the user's radio proving it. Beyond this the honest answer is that we do not have the file.
 */
const STALE_AFTER_MS = 7 * 24 * 60 * 60 * 1000;

/** Anything larger is streamed straight through rather than stored. */
const MAX_STORE_BYTES = 25 * 1024 * 1024;

const UPSTREAM = 'https://raw.githubusercontent.com';

/** Only these repositories are mirrored. An open relay would be someone else's bandwidth bill. */
const ALLOWED = [/^morpheusadam\/v2ray-config$/];

export default {
  async fetch(request, env, ctx) {
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      return text('Method not allowed', 405);
    }

    const url = new URL(request.url);
    const target = parse(url.pathname);
    if (!target) {
      return text('Not found. Expected /gh/{user}/{repo}@{ref}/{path}', 404);
    }
    if (!ALLOWED.some((re) => re.test(`${target.user}/${target.repo}`))) {
      return text('Not mirrored', 403);
    }

    const key = `${target.user}/${target.repo}/${target.ref}/${target.path}`;
    const stored = await env.MIRROR.get(key);
    const age = stored ? Date.now() - Date.parse(stored.uploaded) : Infinity;

    // Fresh enough to serve without asking anyone.
    if (stored && age < FRESH_FOR_MS) {
      return serve(stored, request, 'hit');
    }

    // Stale but present: serve it and refresh behind the response, so nobody waits on GitHub
    // for a file we already have. This is the case that makes the mirror feel instant.
    if (stored && age < STALE_AFTER_MS) {
      ctx.waitUntil(refresh(env, key, target));
      return serve(stored, request, 'stale');
    }

    // Nothing usable stored. Now GitHub is on the critical path.
    const fetched = await refresh(env, key, target);
    if (fetched) {
      return serve(fetched, request, 'miss');
    }

    // Upstream failed and we have nothing fresh. Anything at all beats nothing here — a week-old
    // list still contains servers, and the alternative is the app having no source to try.
    if (stored) {
      return serve(stored, request, 'expired');
    }
    return text('Upstream unavailable and nothing mirrored', 502);
  },

  /**
   * Refresh on a timer as well as on demand, so a file nobody has asked for in a while is still
   * warm when somebody finally does — which, during a blackout, is exactly when it matters and
   * exactly when the on-demand path is least likely to reach GitHub.
   */
  async scheduled(event, env, ctx) {
    const warm = (env.WARM_PATHS || '')
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean);

    for (const p of warm) {
      const target = parse(p.startsWith('/') ? p : `/${p}`);
      if (!target) continue;
      const key = `${target.user}/${target.repo}/${target.ref}/${target.path}`;
      ctx.waitUntil(refresh(env, key, target));
    }
  },
};

/** `/gh/user/repo@ref/some/path.txt` → its parts, or null when it is not that shape. */
function parse(pathname) {
  const m = /^\/gh\/([^/]+)\/([^/@]+)@([^/]+)\/(.+)$/.exec(decodeURIComponent(pathname));
  if (!m) return null;
  const [, user, repo, ref, path] = m;
  // No traversal out of the repository, whatever the client sent.
  if (path.includes('..')) return null;
  return { user, repo, ref, path };
}

async function refresh(env, key, target) {
  const upstream = `${UPSTREAM}/${target.user}/${target.repo}/${target.ref}/${target.path}`;
  let response;
  try {
    response = await fetch(upstream, {
      headers: { 'User-Agent': 'v2rayV-mirror' },
      cf: { cacheTtl: 60, cacheEverything: true },
    });
  } catch (e) {
    return null;
  }
  if (!response.ok) return null;

  const body = await response.arrayBuffer();
  if (body.byteLength === 0 || body.byteLength > MAX_STORE_BYTES) return null;

  await env.MIRROR.put(key, body, {
    httpMetadata: {
      contentType: response.headers.get('content-type') || 'text/plain; charset=utf-8',
    },
  });
  return await env.MIRROR.get(key);
}

function serve(object, request, state) {
  const headers = new Headers();
  object.writeHttpMetadata(headers);
  headers.set('etag', object.httpEtag);
  headers.set('cache-control', 'public, max-age=300');
  headers.set('x-mirror', state);
  headers.set('x-mirror-age', String(Math.floor((Date.now() - Date.parse(object.uploaded)) / 1000)));
  headers.set('access-control-allow-origin', '*');

  if (request.headers.get('if-none-match') === object.httpEtag) {
    return new Response(null, { status: 304, headers });
  }
  if (request.method === 'HEAD') {
    return new Response(null, { status: 200, headers });
  }
  return new Response(object.body, { status: 200, headers });
}

function text(message, status) {
  return new Response(`${message}\n`, {
    status,
    headers: { 'content-type': 'text/plain; charset=utf-8' },
  });
}
