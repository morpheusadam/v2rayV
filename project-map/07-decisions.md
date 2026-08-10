# 07 — Decisions not to undo

Each of these looks like something worth tidying. Each one is load-bearing, and most of them
are the result of a measurement rather than a preference. If you are about to change one,
read the reason first.

---

### TCP reachability drops hosts, it never ranks them

Measured on a real pool: taking the **lowest-latency** candidates gave a **2.1%** pass rate
against **7.5%** for a random draw from the same pool. The hosts that answer fastest are CDN
edges in front of dead proxies.

Sorting the tcping results looks like an obvious improvement. It is worse than doing nothing.

---

### The ranker shuffles within each tier

`AutoModeRanker.prioritise` is `items.shuffled().sortedBy { score(it) }`. The shuffle keeps a
run exploring instead of re-confirming yesterday's servers.

It also means the order of equally-scored candidates is not deterministic. A test once
asserted a literal order and passed about one run in three. Assert the property, not the
sequence.

---

### Both sides of the 70% ratio use the same probe, single stream

`ThroughputProbe` measures the user's line and every server. The two numbers are **divided
by one another**, so they have to be the same measurement.

The accepted way to measure a *line* is 4–8 parallel streams. Do that while measuring
servers serially and the ratio is meaningless — nothing ever clears 70%. The baseline is
deliberately not the line's capacity; it is what one stream achieves right now, which is the
only thing a server can be asked to match.

---

### The line is measured under the tcping stage, never under the fetch

Sharing the radio with the source fetches measures the line as slower than it is, which
lowers the bar every server is then judged against — failing in the direction of accepting
worse servers. The liveness stage opens thousands of short connections and moves almost no
bytes, which is why it is the safe window.

---

### Exactly one speed test at a time

Two downloads racing over one radio measure the radio, not the servers.

Starting the *next* core during the current download is fine — it binds a loopback socket
and sends nothing over the air. That distinction is the whole reason prewarming is allowed
and parallel testing is not.

---

### The reserve does not wrap

Working through all ten and still being unhappy is evidence the batch is bad. Handing back
the first one again would hide that and leave the user tapping in a circle.

---

### Bare subscription URLs are stripped out of any fetched body

`AngConfigManager.importBatchConfig` treats a line that looks like a subscription URL as one
to **add**, and then refreshes every subscription the user has. Many free sources are
exactly that — a list of other people's links — so without stripping, a run would quietly
fill the user's subscription list with whatever it fetched.

This is also why the catalog cannot be used as a source: fed through import it yields
nothing at all, by design.

---

### The app excludes itself from its own VPN

`addDisallowedApplication` on the package itself, in every branch of the per-app
configuration. This is what lets the baseline be measured while the tunnel is up. It is not
a bug.

---

### The destination is never resolved locally when a proxy is in play

Anything taking a `java.net.Proxy` resolves first and hands over an IP, which is useless on
a network that lies in DNS — the network the whole feature exists for. It also sidesteps the
JVM's broken SOCKS4 client, which matters because a quarter of scraped proxies listen on
4145.

---

### The route race staggers by 250 ms rather than firing at once

Without the stagger, every run on an open network opens three sockets it never needed. With
it, the later routes are only reached if the first is genuinely slow. The fast path stays as
cheap as it was; only the blocked path changes.

---

### An unknown `# format:` version is logged, never rejected

Refusing it would turn a cosmetic edit in the companion repo's generator into a censored
network with no proxies at all, on the one path with no fallback left. The marker exists to
make drift findable, not to give the client a way to lock itself out.

---

### Smart Switch never acts on an idle connection

An idle server and a dead one report exactly the same zero, and a phone in a pocket reports
it all night. Judging on throughput alone would drop a working connection nightly. A verdict
is only reached while the uplink shows the user actually asking for something.

It also does not start a fresh Auto Mode run when the reserve empties. A run is expensive
and very visible, and beginning one unprompted from a background service — possibly while
the user is asleep — is not a thing to do on the strength of eight bad seconds.

---

### The notice slot's normal state is drawing nothing

No file, no network, bad JSON, wrong version, dismissed — every failure path ends with an
empty slot. The URL 404ing is the correct quiet case, not a bug to fix.

---

### The Kotlin namespace stays `com.v2ray.ang`

Even though `applicationId` is `com.v2rayv.app`. `hev-socks5-tunnel` registers its JNI
methods against that class package (`-DPKGNAME` in `compile-hevtun`), so moving it means
rebuilding the native libraries for a string nobody sees.

---

### The catalog merge is capped at 400

The store is one JSON blob rewritten in full on every save, and parsing a much smaller
version of it on the main thread has already caused an ANR. A run spends eight sources, and
Thompson sampling needs repeated pulls before a source's evidence means anything — 1,500
sources is not a wider net, it is the same net with nothing learned.

---

## Open questions, honestly

- **`acceptFraction = 0.70` is set on judgement, not evidence.** It decides how long a first
  connection takes. Worth revisiting with real runs.
- **The censored path has never run on a censored network.** See [03](03-censorship.md).
- **Smart Switch's thresholds are reasoned, not measured.** Eight seconds, a quarter of the
  reference speed, four switches an hour — all defensible, none observed.
