<div align="center">

<img src="design/logo/png/mark/logo-mark-256.png" alt="v2rayV" width="128" height="128">

# v2rayV

**A V2Ray/Xray client for Android that connects itself.**

One press measures your line, finds servers, and connects on the first one that is fast
enough — then keeps working in the background so the next press is instant.

[![API](https://img.shields.io/badge/API-24%2B-yellow.svg?style=flat-square)](https://developer.android.com/about/versions/nougat)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.0-7f52ff.svg?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Version](https://img.shields.io/badge/version-2.3.3-0aa36b.svg?style=flat-square)](V2rayNG/app/build.gradle.kts)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg?style=flat-square)](LICENSE)

Built on [2dust/v2rayNG](https://github.com/2dust/v2rayNG), carrying the Auto Mode feature
from its desktop sibling [v2rayN-Pro-Max](https://github.com/morpheusadam/v2rayN-Pro-Max).

</div>

> [!NOTE]
> **Status: pre-release.** Auto Mode is verified end to end on a real device — power press to
> connected in about 80 seconds on a cold install. There is no published release yet; build
> from source for now. The censored-network fallback path (CDN mirrors → discovered proxies)
> compiles and is unit-tested but has not yet been exercised on a network that blocks GitHub.

---

## Contents

- [Why](#why)
- [Auto Mode](#auto-mode)
- [How a run works](#how-a-run-works)
- [Dashboard](#dashboard)
- [Installs alongside v2rayNG](#installs-alongside-v2rayng)
- [Building](#building)
- [Project layout](#project-layout)
- [Roadmap](#roadmap)
- [Credits and licence](#credits-and-licence)

---

## Why

A subscription link gives you a few hundred servers, most of which are dead, and the app
leaves you to find the live ones by tapping through them. v2rayV does that work itself:
it imports every source you have, throws away what cannot carry traffic, measures what is
left against **your own connection speed**, and connects on the first server that clears
the bar.

---

## Auto Mode

One button. It fetches your subscription sources, imports what it finds, filters, measures,
and keeps the winners as ready-to-use servers.

A run on a real device: **76 links merged → 609 candidates imported → 39 tunnelled → 4 kept**,
with the line measured at 3.89 MB/s and the first accepted server delivering 3.0 MB/s.

### Design decisions that are load-bearing

| Decision | Why |
|---|---|
| **Acceptance is relative, not absolute.** A server is good enough when it delivers ≥ 70% of what the bare connection delivers. | An absolute MB/s target is meaningless — it fails everyone on a slow line and under-serves everyone on a fast one. |
| **Your line and each server are measured by the same probe** (`ThroughputProbe`), deliberately single-stream. | The two numbers are divided by one another, so they must be the same measurement. Measure the line the textbook way with 4–8 parallel streams and nothing ever clears 70%. |
| **TCP reachability drops hosts; it never ranks them.** | Measured on a real pool, ranking by lowest tcping passed **2.1%** against **7.5%** for a random draw — the fastest-answering hosts are CDN edges fronting dead proxies. |
| **Candidates are ordered by protocol and country read off the config, not by measurement** (`AutoModeRanker`). Randomness is kept *within* each tier. | Ordering is free; measuring is not. Randomness inside a tier makes a run explore rather than re-confirm. |
| **Protocol order:** VLESS+REALITY / XTLS-Vision → Hysteria2 / TUIC → the rest → WireGuard last. | Follows 2026 reporting on Iranian DPI. WireGuard is reliably detected. |
| **Country order:** DE → NL → FR → TR. | Turkey has the lowest ping of the four but the least capacity, so it sorts behind the European ones. |
| **Speed tests run strictly one at a time.** | Two downloads racing over one radio measure the radio, not the servers. |
| **A server that wins again keeps its existing entry.** | The one you selected — and the one the tunnel is running on — does not get deleted out from under you. |
| **Bare subscription URLs found inside a fetched body are stripped before import.** | A source that is really a list of *other* subscriptions cannot silently add itself to your source list. |
| **The reserve does not wrap** (`AutoModeReserve`). | Working through all ten and still being unhappy is evidence the batch is bad; handing back the first again would hide that. |
| **Source health is [Thompson sampling](https://en.wikipedia.org/wiki/Thompson_sampling) over Beta evidence** (`BetaSampler`). | Sources that keep producing good servers get tried more often, without ever starving a new one. |

Per-source statistics, filters and the source list live under **Auto Mode → Sources**.

### Reaching the internet when the internet is blocked

Fetching a subscription is itself censored. `ProxiedFetch` walks a route ladder and reports
which rung worked:

```
direct  →  CDN mirror  →  discovered proxy  →  bundled snapshot
```

The last rung, `app/src/main/assets/automode_subs.txt` and `automode_proxies.txt`, ships
inside the APK so that a **first run on an already-blocked network** has something to work
with. `AutoModeProxyFinder` sweeps a candidate list to refill the ladder for later runs.

---

## How a run works

```
                                   ┌──────────────────┐
   power press  ─────────────────► │  baseline probe  │  your line, single stream
                                   └────────┬─────────┘
                                            │  threshold = line × 0.70
                                            ▼
   sources ──► fetch ──► import ──► filter ──► rank ──► tcping ──► speed test
   (Thompson    (route    (strip     (dedupe,  (protocol  (drop      (one at a
    sampled)     ladder)   nested     region)   country)   the dead)   time)
                           subs)                                        │
                                                                        ▼
                                          connect on the first server ≥ threshold
                                                                        │
                                            keep going in the background │
                                                                        ▼
                                     reserve of 10 ──► next press is instant
```

While it runs, the app shows a countdown and a seven-step timeline rather than a spinner,
so a slow step is visible as a slow step.

---

## Dashboard

The app opens on a dashboard instead of the server list — connection state, live throughput,
session traffic, exit IP with flag, an elapsed timer, and the Auto Mode button on one screen.
The server list is one swipe to the right.

It is a fixed dark instrument panel rather than a Material theme, with segmented tick rings,
bars and sparklines drawn on Canvas — quantised on purpose, so a jittering measurement reads
as an instrument rather than implying precision it does not have.

A remote **notice slot** can carry an announcement or an in-app update prompt. Its normal
state is drawing nothing at all: no file, no network, bad JSON, wrong version and dismissed
all end with an empty slot.

---

## Installs alongside v2rayNG

`applicationId` is `com.v2rayv.app`, so v2rayV installs next to v2rayNG with its own data,
its own launcher icon and its own notification mark. The Kotlin namespace stays `com.v2ray.ang`
— `hev-socks5-tunnel` registers its JNI methods against that package name.

---

## Building

Requires **JDK 21**, **Android SDK platform 37** with **build-tools 37.0.0**, and **NDK 29**.

```bash
git clone --recurse-submodules https://github.com/morpheusadam/v2rayV.git
cd v2rayV/V2rayNG

./gradlew assemblePlaystoreDebug -PABI_FILTERS=arm64-v8a
./gradlew testPlaystoreDebugUnitTest --tests "com.v2ray.ang.automode.*"
```

Two things are not in the repository and must be fetched before the first build:

1. **`libv2ray.aar`** — 56 MB, from the [AndroidLibXrayLite releases](https://github.com/2dust/AndroidLibXrayLite/releases)
   whose tag matches the pinned submodule. Drop it in `V2rayNG/app/libs/`.
2. **The `hev-socks5-tunnel` native libraries.** Run `compile-hevtun.ps1` (Windows) or
   `compile-hevtun.sh` (POSIX) to build them into `V2rayNG/app/libs/<abi>/`. On Windows the
   script also materialises the submodule's git-symlink headers as real files, without which
   the compiler reads a path string as C.

A **release** build additionally needs `V2rayNG/signing.properties` (gitignored) with
`storeFile`, `storePassword`, `keyAlias`, `keyPassword`. Without it the release comes out
unsigned rather than debug-signed, on purpose.

### Notes inherited from upstream

- `geoip.dat` and `geosite.dat` live in `Android/data/com.v2rayv.app/files/assets` (the path
  differs on some devices). The download feature pulls the enhanced build from
  [v2ray-rules-dat](https://github.com/Loyalsoldier/v2ray-rules-dat), which needs a working
  proxy first.
- The aar can be rebuilt from [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite).
- On WSA, VPN permission needs `appops set com.v2rayv.app ACTIVATE_VPN allow`.

---

## Project layout

Everything below is added by this fork; the rest of the tree is upstream v2rayNG.

```
V2rayNG/app/src/main/java/com/v2ray/ang/
├── automode/
│   ├── AutoModeEngine.kt        the run: fetch → import → filter → measure → keep
│   ├── AutoModeBaseline.kt      your line, and the 70% acceptance threshold
│   ├── ThroughputProbe.kt       the one measurement used for both sides of that ratio
│   ├── AutoModeRanker.kt        protocol/country ordering, randomised within a tier
│   ├── AutoModeSpeedTester.kt   serialised speed tests over the live tunnel
│   ├── AutoModeReserve.kt       the next-connection queue; does not wrap
│   ├── AutoModeSourceManager.kt sources, filters, per-source statistics
│   ├── BetaSampler.kt           Thompson sampling over source evidence
│   ├── ProxiedFetch.kt          direct → CDN mirror → proxy → bundled snapshot
│   ├── AutoModeProxyFinder.kt   sweeps candidates to refill the ladder
│   └── AutoModeScheduler.kt     background top-ups when the reserve runs low
├── notice/                      remote notice slot and in-app update
└── ui/
    ├── dashboard/               the instrument panel, connecting card, notice card
    └── automode/                Auto Mode screens and the source list
design/logo/                     logo sources, generator and exported assets
```

---

## Roadmap

- [ ] Exercise the censored path on a genuinely blocked network — the single most valuable
      piece of missing evidence. Until then `ProxiedFetch`, `AutoModeProxyFinder` and
      `AutoModeNetwork` are unproven.
- [ ] Confirm live DOWNLOAD/UPLOAD on a real device. Traffic statistics deliberately report
      only *proxied* bytes; anything a routing rule sends direct is excluded.
- [ ] Revisit `acceptFraction = 0.70`, currently set on judgement rather than evidence.
- [ ] Refresh the bundled `automode_*.txt` snapshots each release — they go stale.
- [ ] First tagged release.

---

## Credits and licence

v2rayV is a fork of [v2rayNG](https://github.com/2dust/v2rayNG) by [2dust](https://github.com/2dust),
which does all of the heavy lifting — the cores, the protocols, the tunnel. Auto Mode, the
route ladder and the dashboard are the additions here.

Licensed under **GPL-3.0**, the same as upstream. See [LICENSE](LICENSE).

Cores and components:
[Xray-core](https://github.com/XTLS/Xray-core) ·
[v2fly-core](https://github.com/v2fly/v2ray-core) ·
[AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite) ·
[hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)
