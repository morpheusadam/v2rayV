<?php
/**
 * Copies upstream files into the CDN's document roots so the web server can serve them as
 * plain static files.
 *
 * Run from cron, never from a web request. That is the whole design. A proxy script would put
 * PHP on the path of every fetch, which on shared hosting means the execution limits, the
 * memory limits and the concurrency limits of the cheapest plan sit between a user and the
 * file they asked for. Static files sidestep all of it: the web server serves them at full
 * speed, the CDN keeps working if PHP is disabled or the account hits a process cap, and
 * there is no request-time code to have a bug in.
 *
 * The cost is that only the files named in sources.json are carried, rather than anything
 * under an upstream repository. That is the right trade for a mirror whose contents are a
 * short, known list, and it is what makes the whole thing auditable.
 *
 * ## What makes this more than one project's mirror
 *
 * Everything specific lives in sources.json — this file knows about roots, namespaces and
 * files in the abstract, and nothing about v2rayV. Adding a second project is an entry in the
 * manifest. Three ideas carry that:
 *
 *  - **Namespaces.** Each project owns a directory under the CDN root, so two projects can
 *    both publish `app/notice.json` without colliding, and a URL says which project it
 *    belongs to.
 *  - **Roots are a list.** The same content is published to every root in one run, which is
 *    what makes moving between domains a manifest edit rather than an outage: publish to
 *    both, let the old clients drain, then delete the retiring root.
 *  - **Prefixes are a list too.** A namespace can appear at more than one path inside a root,
 *    which is how a layout change is made without breaking clients that already shipped
 *    asking for the old one.
 *
 * A file is fetched once per run however many places it is written to.
 *
 * ## Deploy
 *
 *   1. Put this file and sources.json OUTSIDE public_html, or they can be run from a browser.
 *        ~/cdn/sync.php, ~/cdn/sources.json
 *   2. Cron, every 30 minutes:
 *        /usr/bin/php /home/USER/cdn/sync.php >> /home/USER/cdn/logs/sync.log 2>&1
 *
 * Writes are atomic — download to a temporary file, then rename — so a fetch arriving
 * mid-sync gets either the old file or the new one, never half of either.
 */

declare(strict_types=1);

const MANIFEST = __DIR__ . '/sources.json';

/** A lock, so a slow run and the next cron tick do not write over each other. */
const LOCK_FILE = __DIR__ . '/.sync.lock';

/** A file that fails to download is left alone. A stale list beats no list. */
const TIMEOUT_SECONDS = 60;
const CONNECT_TIMEOUT_SECONDS = 20;

/** Anything smaller than this is treated as an error page rather than content. */
const MIN_PLAUSIBLE_BYTES = 32;

const USER_AGENT = 'bineret-cdn-sync/1';

// ---- plumbing -------------------------------------------------------------------

function log_line(string $message): void
{
    echo '[' . gmdate('Y-m-d H:i:s') . 'Z] ' . $message . PHP_EOL;
}

function fail(string $message): void
{
    log_line('ABORT ' . $message);
    exit(2);
}

/**
 * Downloads a URL, or null when it did not produce something worth publishing.
 *
 * A short body counts as a failure rather than as content: an upstream that has been moved or
 * rate-limited answers with a small error page and a 200, and publishing that would replace a
 * working list with an apology.
 */
function fetch(string $url): ?string
{
    $ch = curl_init($url);
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_FOLLOWLOCATION => true,
        CURLOPT_MAXREDIRS => 3,
        CURLOPT_TIMEOUT => TIMEOUT_SECONDS,
        CURLOPT_CONNECTTIMEOUT => CONNECT_TIMEOUT_SECONDS,
        CURLOPT_USERAGENT => USER_AGENT,
        CURLOPT_FAILONERROR => true,
    ]);
    $body = curl_exec($ch);
    $error = curl_error($ch);
    $status = (int) curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
    curl_close($ch);

    if (!is_string($body) || $body === '') {
        log_line('  FAIL ' . $url . ' — ' . ($error !== '' ? $error : 'HTTP ' . $status));
        return null;
    }
    if (strlen($body) < MIN_PLAUSIBLE_BYTES) {
        log_line('  FAIL ' . $url . ' — only ' . strlen($body) . ' bytes, treating as an error page');
        return null;
    }
    return $body;
}

/**
 * Writes content to a path, creating directories as needed.
 *
 * @return string one of 'same', 'updated', or 'failed'.
 */
function publish(string $destination, string $body): string
{
    $directory = dirname($destination);
    if (!is_dir($directory) && !mkdir($directory, 0755, true) && !is_dir($directory)) {
        log_line('  FAIL could not create ' . $directory);
        return 'failed';
    }

    // Unchanged content is not rewritten, so a file's mtime keeps meaning "when this last
    // actually changed" rather than "when cron last ran". That distinction is the whole
    // value of the freshness figures in status.json.
    if (is_file($destination) && hash('sha256', $body) === hash_file('sha256', $destination)) {
        return 'same';
    }

    $temporary = $destination . '.tmp.' . getmypid();
    if (file_put_contents($temporary, $body) === false) {
        log_line('  FAIL could not write ' . $temporary);
        return 'failed';
    }
    // Atomic on the same filesystem: a reader sees the old file or the new one, never a
    // partial write. This is why it is not written to the destination directly.
    if (!rename($temporary, $destination)) {
        log_line('  FAIL could not move ' . $temporary . ' into place');
        @unlink($temporary);
        return 'failed';
    }
    @chmod($destination, 0644);
    return 'updated';
}

/** Writes a file that is generated rather than mirrored. Never fails the run. */
function write_generated(string $destination, string $body): void
{
    if (publish($destination, $body) === 'failed') {
        log_line('  WARN could not write ' . basename($destination));
    }
}

/**
 * A plain page listing what this root carries.
 *
 * Generated from the manifest rather than kept as a static file, so it cannot drift out of
 * step with what is actually published — a directory index that lies about its contents is
 * worse than none at all.
 */
function render_index(array $manifest, array $root, array $document): string
{
    $e = static function (string $s): string {
        return htmlspecialchars($s, ENT_QUOTES, 'UTF-8');
    };

    $base = (string) ($root['urls'][0] ?? '/');

    $sections = '';
    foreach ($manifest['namespaces'] as $namespace) {
        $title = (string) ($namespace['title'] ?? $namespace['name']);
        $sections .= '<h2>' . $e((string) $namespace['name'])
            . ' <small>' . $e($title) . '</small></h2><ul>';

        $prefixes = $namespace['prefixes'] ?? [$namespace['name']];
        $prefix = trim((string) ($prefixes[0] ?? ''), '/');
        foreach ($namespace['files'] as $file) {
            $path = ($prefix === '' ? '' : $prefix . '/') . $file;
            $sections .= '<li><a href="' . $e('/' . $path) . '">' . $e($path) . '</a></li>';
        }
        $sections .= '</ul>';
    }

    $retiring = '';
    if (!empty($document['retiring'])) {
        $successor = (string) ($manifest['roots'][0]['urls'][0] ?? '');
        $retiring = '<p class="warn">This host is being retired. New clients should use <code>'
            . $e($successor) . '</code></p>';
    }

    $finished = $e((string) $document['finished']);
    $baseText = $e($base);

    return '<!doctype html>' . "\n"
        . '<meta charset="utf-8">' . "\n"
        . '<meta name="viewport" content="width=device-width, initial-scale=1">' . "\n"
        . '<title>CDN</title>' . "\n"
        . '<style>' . "\n"
        . ' body{font:16px/1.6 system-ui,-apple-system,sans-serif;max-width:44rem;margin:3rem auto;padding:0 1.25rem;color:#111;background:#fff}' . "\n"
        . ' h1{margin:0 0 .25rem;font-size:1.5rem} h2{margin:2rem 0 .5rem;font-size:1.05rem}' . "\n"
        . ' small{font-weight:400;color:#666} a{color:#0a7d46} li{margin:.15rem 0}' . "\n"
        . ' code,.meta{font-family:ui-monospace,SFMono-Regular,monospace;font-size:.85rem;color:#666}' . "\n"
        . ' .warn{background:#fff4e5;border-left:3px solid #e69500;padding:.6rem .8rem}' . "\n"
        . ' @media(prefers-color-scheme:dark){body{background:#111;color:#eee}small,.meta,code{color:#999}a{color:#3ddc91}.warn{background:#2a2113}}' . "\n"
        . '</style>' . "\n"
        . '<h1>CDN</h1>' . "\n"
        . '<p class="meta">Static mirror, served from ' . $baseText
        . ' — see <a href="/status.json">status.json</a> for freshness.</p>' . "\n"
        . $retiring . "\n"
        . $sections . "\n"
        . '<p class="meta">Last run ' . $finished . '.</p>' . "\n";
}

// ---- the manifest ---------------------------------------------------------------

if (!is_file(MANIFEST)) {
    fail('no manifest at ' . MANIFEST);
}
$manifest = json_decode((string) file_get_contents(MANIFEST), true);
if (!is_array($manifest)) {
    fail('manifest is not valid JSON: ' . json_last_error_msg());
}

$roots = $manifest['roots'] ?? [];
$namespaces = $manifest['namespaces'] ?? [];
if ($roots === [] || $namespaces === []) {
    fail('manifest declares no roots or no namespaces');
}

// One run at a time. An exclusive, non-blocking lock rather than a pid file: the lock goes
// away with the process, so a run killed by the host's process reaper does not wedge every
// run after it.
$lock = fopen(LOCK_FILE, 'c');
if ($lock === false) {
    fail('could not open ' . LOCK_FILE);
}
if (!flock($lock, LOCK_EX | LOCK_NB)) {
    log_line('another sync is still running, leaving it to finish');
    exit(0);
}

// ---- the run --------------------------------------------------------------------

$startedAt = gmdate('c');
$failures = 0;
$updated = 0;
$unchanged = 0;

/** Per-root freshness, written to that root as status.json when the run finishes. */
$status = [];
foreach ($roots as $root) {
    $status[$root['name']] = [];
}

foreach ($namespaces as $namespace) {
    $name = (string) ($namespace['name'] ?? '');
    $upstream = (string) ($namespace['upstream'] ?? '');
    $files = $namespace['files'] ?? [];
    // A namespace publishes under its own name unless the manifest says otherwise.
    $prefixes = $namespace['prefixes'] ?? [$name];

    if ($name === '' || $upstream === '' || $files === []) {
        log_line('SKIP a namespace entry is missing name, upstream or files');
        $failures++;
        continue;
    }

    log_line('namespace ' . $name . ' — ' . count($files) . ' file(s) from ' . $upstream);

    foreach ($files as $relative) {
        $body = fetch($upstream . $relative);
        if ($body === null) {
            $failures++;
            // Record the miss, so status.json shows a stale file as stale rather than quietly
            // reporting whatever was last written successfully.
            foreach ($roots as $root) {
                $status[$root['name']][$name][$relative]['last_error'] = $startedAt;
            }
            continue;
        }

        $bytes = strlen($body);
        $digest = substr(hash('sha256', $body), 0, 16);
        $results = [];

        foreach ($roots as $root) {
            foreach ($prefixes as $rawPrefix) {
                $prefix = trim((string) $rawPrefix, '/');
                $relativePath = ($prefix === '' ? '' : $prefix . '/') . $relative;
                $destination = rtrim((string) $root['path'], '/') . '/' . $relativePath;

                $outcome = publish($destination, $body);
                $results[] = $outcome;
                if ($outcome === 'failed') {
                    $failures++;
                    continue;
                }

                $known = $status[$root['name']][$name][$relative]['paths'] ?? [];
                $status[$root['name']][$name][$relative] = [
                    'bytes' => $bytes,
                    'sha256_prefix' => $digest,
                    'last_changed' => $outcome === 'updated'
                        ? $startedAt
                        : gmdate('c', (int) filemtime($destination)),
                    'paths' => array_values(array_unique(array_merge($known, [$relativePath]))),
                ];
            }
        }

        if (in_array('updated', $results, true)) {
            $updated++;
            log_line('  updated ' . $relative . ' (' . $bytes . ' bytes) to ' . count($results) . ' path(s)');
        } elseif (!in_array('failed', $results, true)) {
            $unchanged++;
            log_line('  same    ' . $relative . ' (' . $bytes . ' bytes)');
        }
    }
}

// ---- what the CDN says about itself ---------------------------------------------

// Published as static files like everything else, so checking whether the mirror is fresh is
// an HTTP GET rather than an SSH session — which matters most at the moment somebody is
// trying to work out why an app cannot reach its list.
foreach ($roots as $root) {
    $document = [
        'generated' => $startedAt,
        'finished' => gmdate('c'),
        'ok' => $failures === 0,
        'failures' => $failures,
        'urls' => $root['urls'] ?? [],
        'retiring' => (bool) ($root['retiring'] ?? false),
        'namespaces' => $status[$root['name']],
    ];

    $rootPath = rtrim((string) $root['path'], '/');
    write_generated(
        $rootPath . '/status.json',
        json_encode($document, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES) . "\n"
    );
    write_generated($rootPath . '/index.html', render_index($manifest, $root, $document));
}

log_line(sprintf(
    '%s — %d updated, %d unchanged, %d failure(s)',
    $failures === 0 ? 'done' : 'done with failures',
    $updated,
    $unchanged,
    $failures
));

flock($lock, LOCK_UN);
fclose($lock);
exit($failures === 0 ? 0 : 1);
