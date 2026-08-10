# 02 — Auto Mode

The pipeline, stage by stage, with every constant and where it came from.
Source: `automode/AutoModeEngine.kt`.

## The shape

```
press
  │
  ├─ MEASURING   is there a usable line measurement already?  (cached 6h, per network)
  │
  ├─ ROUTING     can we reach the lists at all? if not, find a proxy      → 03
  │
  ├─ FETCHING    pull the curated bundle + a sample of catalog sources
  │
  ├─ IMPORTING   parse into candidate profiles, one scratch group per source
  │
  ├─ PROBING     tcping, to drop the dead        ← the line is measured here, in parallel
  │
  ├─ TUNNELING   real ping, to find what actually carries a request
  │
  └─ MEASURING_SERVERS   throughput, one at a time
         │
         ├─ first server ≥ threshold  → published, connected, user is done
         └─ keep going                → fill the reserve to `topCount`
```

Each stage is timed and the run ends by printing where its clock went:

```
Timing: line 8.1s · route 0.9s · fetch 7.6s · import 2.9s · probe 19.8s · tunnel 15.2s · speed 16.4s
```

That line is the only real evidence about this pipeline's cost. Everything before it was
argued from `estimateSeconds`, which exists to drive a countdown.

## The acceptance rule

A server is good enough when it delivers **70% of what the bare connection delivers**.

- Both numbers come from `ThroughputProbe`, single stream, same URL, same duration.
- They are *divided by one another*, so they must be the same measurement. The textbook way
  to measure a line is 4–8 parallel streams; do that and nothing ever clears 70%.
- The baseline is therefore **not** the line's capacity. It is what one stream achieves on
  this connection right now, which is the only thing a server can be asked to match.
- With no baseline the threshold is zero and the first server carrying any traffic is
  accepted — worse than a measured bar, better than making an unmeasurable user wait.

`acceptFraction = 0.70` is a judgement, not a measurement. It is the number that decides how
long a first connection takes. It is worth revisiting with real data.

## Where the line is measured, and why there

Nothing needs the baseline until the speed test. It used to be measured first, blocking
everything. It now runs *under the tcping stage*, and the choice of stage is the point:

- **Not during fetch.** The probe is a full-throttle download; sharing the radio with the
  source fetches measures the line as slower than it is, which lowers the bar every server
  is then judged against. Failing in the direction of accepting worse servers.
- **During tcping is safe.** That stage opens thousands of short connections and moves
  almost no bytes.

A cached baseline (6 hours, keyed by network type and carrier) skips it entirely.

## Selection: what is ranked and what is not

| Stage | Uses measurement to… |
|---|---|
| tcping | **drop only.** Never to rank |
| ranker | not at all — it ranks on protocol and country read off the config |
| real ping | drop only |
| speed test | rank the winners |

**tcping never ranks.** Measured on a real pool: taking the lowest-latency candidates gave a
2.1% pass rate against 7.5% for a random draw. The fastest-answering hosts are CDN edges in
front of dead proxies.

**The ranker is a prior, not a measurement.** `AutoModeRanker` orders by protocol tier then
country tier, and **shuffles within each tier**. The shuffle is load-bearing: without it a
run re-confirms yesterday's servers instead of exploring. It also means the order of equal
candidates is not deterministic, which a test once asserted on and flaked one run in three.

- Protocol order: VLESS+REALITY / XTLS-Vision → Hysteria2 / TUIC → the rest → WireGuard
  last. Follows 2026 reporting on Iranian DPI; WireGuard is reliably detected.
- Country order: DE → NL → FR → TR. Turkey has the lowest ping of the four and the least
  capacity, so it sorts behind the Europeans.

## Sources

Two kinds, and confusing them wastes a run:

- **A bundle** returns servers. `DEFAULT_SOURCE_URL` points at `subs/bundles/best.txt` —
  configs from sources the companion repo scored 85+, deduplicated, best first. Added as a
  source at the top of every run.
- **A catalog** returns *links to other people's subscriptions*. `DEFAULT_SUBS_URL` points
  at `subs/all.txt`. It cannot be used as a source: the import stage deliberately strips
  bare subscription URLs out of any body it parses, so a catalog fed through it yields
  exactly nothing. It is merged into the source list instead.

`CATALOG_MERGE_LIMIT = 400`. The catalog grew past 1,500 links; taking all of them made the
settings store enormous, and the store is one JSON blob rewritten in full on every save. The
file is generated best-first, so the top of it is the part worth having. A run spends eight
sources anyway, and Thompson sampling needs to pull each source repeatedly before its
evidence means anything — 1,500 sources is not a wider net, it is the same net with nothing
learned from it.

Source health is Thompson sampling over Beta evidence, decayed each run
(`BetaSampler`, `AutoModeSourceManager`). Sources that keep producing get tried more often
without ever starving a new one.

## The budgets

| Constant | Value | Why |
|---|---|---|
| `MAX_POOL_SIZE` | 900 | Configs imported per run; keeps the profile store small |
| `MAX_TCPING` | 800 | The cheap stage |
| `REAL_PING_BATCH` | 100 | Submitted at once… |
| `MAX_REAL_PING_ROUNDS` | 4 | …up to four times |
| `MAX_REAL_PING` | 400 | Hard ceiling |
| `MAX_SPEED_TEST` | 10 | New servers into the expensive stage |
| `MAX_CHAMPIONS_RETESTED` | 8 | Existing keepers defending a slot |
| `MAX_ACCEPTABLE_DELAY` | 2500 ms | Unusable beyond this however fast it downloads |
| `SPEED_TEST_SECONDS` | 8.0 | Only for the countdown |

The real-ping stage stops the moment it has enough survivors, mid-batch — the worker emits a
result per server, and the stage decides on each one. It used to wait for all hundred while
needing twenty.

## The expensive stage

Speed tests run **strictly one at a time**. Two downloads racing over one radio measure the
radio, not the servers. Nothing in this pipeline may break that rule.

What *is* allowed to overlap, because it moves no bytes over the air:

- Starting the next server's core while the current one downloads (`prewarm`).
- The exit-country lookup, which happens *after* the user is connected — it is a label, the
  acceptance test does not read it, and it used to sit between throughput being known and
  the user being connected, holding them behind a round trip with a ten-second timeout.

## Champions

Servers already in the reserve are re-tested each run rather than grandfathered, so one that
has since died loses its slot on its own. They go into the speed test first and are never
squeezed out by the newcomer cap: a slot is lost by being beaten, not by arriving later.

A champion that wins again **keeps its existing guid**. Minting a fresh one would delete the
entry the user has selected — and the one the tunnel is currently running on.

## The reserve, and Smart Switch

`AutoModeReserve` walks the top group. **It does not wrap.** Working through all ten and
still being unhappy is evidence the batch is bad; handing back the first again would hide
that and leave the user tapping in a circle.

`SmartSwitch` makes the same decision automatically, off by default. The hard part is not
detection but restraint — an idle server and a dead one report exactly the same zero. So no
verdict is reached on throughput alone, only while the uplink shows the user actually asking
for something. Then two shapes count: requests leaving with nothing returning, and
throughput at a fraction of what this server measured. Both must hold for eight seconds,
there is a 45-second cooldown, and it is capped at four an hour.

It runs in the core process (`SmartSwitchController`), beside the one reader of the
counters. That reader stops on `ACTION_SCREEN_OFF`, which suits it: with the screen off
nobody is waiting for a page.
