# CDN

A static mirror served from `cdn.bineret.com`, shared by any project that needs files served
from somewhere other than GitHub.

It exists because GitHub is blocked from many of the networks this app runs on. Anything the
app must fetch to work at all — the subscription lists, the update notice — needs a second
place to come from, and that place has to answer even when GitHub does not.

## How it is put together

Nothing is served by PHP. Cron pulls the upstream files into a directory and the web server
serves them as ordinary static files. On shared hosting that is the difference between the
plan's execution, memory and concurrency limits sitting between a user and their file, and
not. It also means the mirror keeps working if PHP is disabled or the account hits a process
cap, and that there is no request-time code to have a bug in.

The cost is that only the files named in [`sources.json`](sources.json) are carried rather
than anything under an upstream repository. For a mirror whose contents are a short, known
list that is not a cost — and it is what makes the thing auditable.

Three ideas make it more than one project's mirror:

**Namespaces.** Each project owns a directory. Two projects can both publish `app/notice.json`
without colliding, and a URL says which project a file belongs to.

**Roots are a list.** One run publishes the same content to every root, which is what makes
moving between domains a manifest edit instead of an outage: publish to both, let the old
clients drain, delete the retiring root.

**Prefixes are a list too.** A namespace can appear at more than one path inside a root. That
is how the layout changes without breaking clients that already shipped asking for the old
one.

A file is fetched once per run however many places it is written to.

## Layout

```
~/cdn/                       code and config, outside public_html
  sync.php
  sources.json
  logs/sync.log

<root>/                      served — https://cdn.bineret.com/
  index.html                 generated each run from the manifest
  status.json                generated each run: per-file size, digest, last-changed
  .htaccess
  v2ray/                     a namespace
    subs/bundles/best.txt
    subs/all.txt
    proxies/all.txt
    app/notice.json
```

## Adding a project

Add an entry to `sources.json` and upload it. No code changes.

```json
{
  "name": "kargah",
  "title": "What this namespace is for",
  "upstream": "https://raw.githubusercontent.com/user/repo/main/",
  "files": ["path/relative/to/upstream.json"]
}
```

`prefixes` defaults to the namespace's own name, so the example above is published at
`https://cdn.bineret.com/kargah/path/relative/to/upstream.json`. Give it an explicit
`prefixes` list only when a file has to appear at more than one path.

## Deploy

```sh
# code and config, outside public_html so they cannot be run from a browser
scp -P 65002 sync.php sources.json u523965318@82.29.185.21:~/cdn/

# the served directory
scp -P 65002 public/.htaccess u523965318@82.29.185.21:<root>/
```

Cron, every 30 minutes — set in hPanel, since `crontab` is not available over SSH on this
host:

```
/usr/bin/php /home/u523965318/cdn/sync.php >> /home/u523965318/cdn/logs/sync.log 2>&1
```

## Checking it

`https://cdn.bineret.com/status.json` reports every file's size, digest and when it last
actually changed — `last_changed` means changed, not "when cron last ran", because a file
whose content matches is not rewritten. A `last_error` on a file means the last run could not
fetch it and left the old copy in place.

That is deliberately an HTTP GET rather than an SSH session: the moment anybody wants to know
whether the mirror is fresh is the moment an app cannot reach its list, and that is a bad time
to need a shell.

## DNS

`bineret.com` is on Cloudflare, not on the host's nameservers, so creating a subdomain in
hPanel does not create the DNS record. `cdn.bineret.com` needs an A record pointing at
`82.29.185.21` added at Cloudflare.

Until then — and as a permanent fallback — the same directory is reachable at
`https://bineret.com/cdn/`, which needs no record of its own because the directory is already
inside the site that resolves.
