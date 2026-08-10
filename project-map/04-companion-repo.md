# 04 — The companion repository

[morpheusadam/v2ray-config](https://github.com/morpheusadam/v2ray-config) publishes the
lists this app runs on. It is a separate repository with its own generator (`harvest.py`)
and its own daily GitHub Actions workflow. **Nothing but a unit test connects it to this
codebase**, which is the single most important fact in this document.

## What it publishes

| File | What it is | Read by |
|---|---|---|
| `subs/bundles/best.txt` | ~2,000 configs, only from sources scoring 85+ | `DEFAULT_SOURCE_URL` — the primary source, added at the top of every run |
| `subs/all.txt` | ~1,500 links to *other people's* subscriptions | `DEFAULT_SUBS_URL` — merged into the source list as variety |
| `proxies/all.txt` | HTTP/SOCKS endpoints that proved they can reach GitHub | `DEFAULT_PROXIES_URL` — the censorship ladder's rung 3 |
| `subs/bundles/*.txt` | Split by protocol, plus `iran.txt` | Not read automatically; available as sources |
| `app/notice.json` | Remote notice / update banner | `NoticeManager` |

Everything is regenerated daily. **The app reads these live**; it does not need rebuilding
when they change.

## The contract, and how it is enforced

It is enforced by three tests and nothing else:

- `AutoModeProxyTest.parses the annotated format the published proxy list uses` — pins the
  exact published line shape.
- `AutoModeProxyTest.an unknown format version is still parsed` — pins the *policy* on
  version drift.
- `BundledSnapshotTest` — runs the real parsers over the real shipped snapshots.

### The proxy line format

```
# format: v1
http://1.231.81.166:3128 | ?? | 1801ms | 1d
```

The header promises *"everything after the first space or | is ignored by the client"*.
`AutoModeProxy.parse` keeps that promise at the `substringBefore('|')` step. It also accepts
bare `ip:port`, `user:pass@host:port` and `host:port:user:pass`, because scraped lists are a
habit rather than a format.

### On the `# format:` marker

Every published file starts with `# format: v1`. The app reads it and **logs a mismatch
without ever refusing to parse**.

That is deliberate and worth not "fixing". Refusing an unknown version would turn a cosmetic
edit in the generator into a censored network with no proxies at all — on the one path that
has no fallback left. The marker exists to make drift *findable*, not to give the client a
way to lock itself out.

The check lives in `AutoModeNetwork.noteFormat`, not in the parser, because `LogUtil` reaches
into MMKV and the parser has to keep running in a plain JVM test.

## Changing the format

If you change what the generator writes:

1. Bump `# format:` in the generator.
2. Update `AutoModeProxy.SUPPORTED_FORMAT` and the pinning test on this side.
3. Refresh `assets/automode_*.txt` and let `BundledSnapshotTest` confirm they parse.
4. Remember old app versions are still installed and still parsing the file. Backwards
   compatibility is the generator's problem, because it is the thing that can change.

## Known quality figures

The proxy file's header carries its own honesty: **density**, the fraction of a random
sample that still worked on re-check. It has been around **43%** — close to six in ten dead
within the day. For the app that means the proxy race has to try roughly twice as many
candidates to find one, which works but costs time. The generator's two-stage probing (an
entry must survive two separate runs) is what should bring that up.

The catalog is ordered **best first** by the generator, which is why
`CATALOG_MERGE_LIMIT = 400` takes from the top rather than sampling.

## If a run reports "no working proxy among 600 tried"

That is a data problem, not a code problem. It means every one of 600 candidates failed to
fetch sixteen bytes of the subscription file. Look at the generator, not at
`AutoModeProxyFinder`.
