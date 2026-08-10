# 03 — Reaching a blocked network

**None of this has ever run on a genuinely blocked connection.** It compiles, it is unit
tested, and it is unproven. Read that first, because the rest of this document describes
behaviour nobody has watched happen.

The problem it solves: fetching a subscription is itself censored. A client that cannot
download its list produces nothing at all, on exactly the networks where it matters most.
The failure an Iranian tester hit was not "no servers found" — it was *"sources downloaded
but contained no usable server"*, because the fetches had returned a block page.

## The ladder

Every fetch walks this, and reports which rung worked:

```
  1. the host itself      raw.githubusercontent.com
  2. CDN mirrors          cdn.jsdelivr.net · raw.githack.com · gitcdn.link
  3. a discovered proxy   raced out of a scraped list
  4. this phone's cache   the last list it managed to fetch
  5. the APK's assets     frozen at build time
```

Rungs 1–3 are `AutoModeNetwork` + `AutoModeProxyFinder`. Rungs 4–5 are `AutoModeCache` and
`assets/automode_*.txt`.

### Rungs 1 and 2 are raced, not walked

They are independent hostnames, so a blocked one costs a full connect timeout before the
next is tried — with four routes at six seconds each, twenty-four seconds just to learn the
host is blocked, paid again by every fetch of every run.

`RouteRace` starts them **250 ms apart** and takes the first answer. This is
[RFC 8305](https://www.rfc-editor.org/rfc/rfc8305) ("Happy Eyeballs v2") applied to mirrors
instead of address families.

**The stagger is as important as the race.** Firing all four at once would open three
useless sockets on every run on an open network, where rung 1 always wins. With the stagger
the later routes are never opened at all unless the first is genuinely slow — the fast path
stays exactly as cheap as before, and only the blocked path gets faster.

Losing attempts are cancelled but **not waited for**. They are blocking socket reads that no
coroutine cancellation interrupts; waiting would give back everything the race won.

The rung that answered is remembered (`lastRouteIndex`, 6-hour TTL) so later fetches start
there.

### Rung 3: the proxy race

`AutoModeProxyFinder`. The economics are the opposite of the server pipeline: a scraped
proxy list is mostly dead, but only **one** entry has to work and the payload is a few
hundred kilobytes of text. So it is a wide shallow race rather than a careful funnel — 24 at
a time, in waves, up to 600 tried, stopping the moment one answers.

The probe is a byte-range request for the **real subscription file**, not a synthetic
reachability check. A proxy that returns those sixteen bytes has proven everything that
matters at once: the protocol guess was right, it allows CONNECT to 443, it resolves the
host, and the host is not blocked from where it sits. A captive portal or block page fails
it.

A working proxy is remembered for six hours, so later runs are one round trip rather than a
race.

### Why not OkHttp — `ProxiedFetch`

Two reasons, both non-negotiable:

1. **Every client that takes a `java.net.Proxy` resolves the destination locally** and hands
   the proxy an IP. On a network that answers DNS with a lie — which is the network this
   whole feature exists for — that defeats the point of using a proxy at all. So the
   destination is always addressed **by name** and resolved at the far end: SOCKS5's domain
   address type, SOCKS4a's hostname extension, CONNECT's authority form. Nothing here ever
   calls `InetAddress.getByName` on the target.
2. **The JVM's SOCKS4 client is broken** (square/okhttp#1359) — it opens with a SOCKS5
   greeting, reads two bytes of an eight-byte SOCKS4 reply, then writes a SOCKS4 request
   onto a desynchronised socket. A quarter of scraped proxies listen on 4145, the
   conventional SOCKS4 port, so that path cannot be skipped.

TLS is still negotiated with the real hostname, so SNI and certificate validation are
unaffected by the detour.

### Rung 4: the cache — why the app no longer needs rebuilding

`AutoModeCache` writes every successfully fetched list to `filesDir/automode/`. The next run
that cannot reach the network reads that instead of the APK's assets.

This is what stops the daily updates upstream from requiring a new build. Ordinary use never
needed one — the lists are read live. But the *fallback* was frozen at build time, and a
proxy list from three months ago is close to useless: its entries are long dead, and the
whole point of that rung is to find one that is not.

After a single successful run the phone carries yesterday's list rather than the build
date's, whatever happens to the network afterwards.

There is deliberately **no expiry**. A cached list goes stale eventually, but it goes stale
*later* than the thing it would fall back to, and on this rung the alternative to a stale
list is no list at all. Writes go to a temp file and are renamed into place, so a kill
mid-write cannot leave a half-file that parses into a handful of entries and looks real.

### Rung 5: the bundled snapshot

`assets/automode_proxies.txt` and `automode_subs.txt`, copied by hand from the companion
repo. They now only matter for a phone that has **never once** managed a fetch — a first run
on an already blocked network. That is the circular bootstrap: the proxy list you need in
order to reach GitHub lives on GitHub.

Refresh them when cutting a release. `BundledSnapshotTest` runs the real parsers over the
real shipped files, because if they stop parsing the failure is silent, total, and lands on
the users who have no alternative — and would never show up in testing on an open
connection, where these files are never opened.

## Sampling enormous files

`raw.githubusercontent.com` answers `Accept-Ranges: bytes`. A file over 3 MB is read as six
random 256 KB windows rather than downloaded, so the cost of a run does not grow with the
size of the source, and the sample is uniform rather than the first N lines — which on a
sorted list would be the same servers every run. The first and last line of each window are
dropped, because a window starts and ends mid-line and half a `vless://` URI parses as
nothing at best and as a corrupted server at worst.

## What to look for on a real blocked network

The run's progress lines name the rung. If the timing line shows `route` costing seconds
rather than milliseconds, the ladder is doing work. If it says *"no working proxy among 600
tried"*, the proxy list is the problem and not the code — that is a
[companion repo](04-companion-repo.md) matter.
