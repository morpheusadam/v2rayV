# v2rayV

A V2Ray/Xray client for Android that finds working servers for you. One press tests your
subscription links and keeps the fastest servers — no more hunting through a list of dead
entries every morning.

Built on [2dust/v2rayNG](https://github.com/2dust/v2rayNG), carrying the Auto Mode feature
from its desktop sibling [v2rayN-Pro-Max](https://github.com/morpheusadam/v2rayN-Pro-Max).

[![API](https://img.shields.io/badge/API-24%2B-yellow.svg?style=flat)](https://developer.android.com/about/versions/lollipop)
[![Kotlin Version](https://img.shields.io/badge/Kotlin-2.4.0-blue.svg)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)

> **Status: pre-release.** Auto Mode has been verified end to end on a real phone. There is
> no published release yet — build from source for now.

---

## What is different from v2rayNG

### Auto Mode

One button. It fetches your subscription sources, imports what it finds, filters, measures,
and keeps the winners as ready-to-use servers.

The pipeline is `fetch → import → filter → tcping → real ping → speed test → keep winners`.
A run on a real phone produced five ranked servers, `5.7 MB/s · 30 ms` down to
`0.3 MB/s · 571 ms`.

A few things it does deliberately:

- **TCP reachability drops hosts, it never ranks them.** Measured on a real pool, ranking by
  lowest tcping passed 2.1% against 7.5% for a random draw — the fastest-answering hosts are
  CDN edges fronting dead proxies.
- **Speed tests run strictly one at a time.** Two downloads racing over one radio measure the
  radio, not the servers.
- **A server that wins again keeps its existing entry**, so the one you selected — and the one
  the tunnel is running on — does not get deleted out from under you.
- **Bare subscription URLs found inside a fetched body are stripped** before import, so a source
  that is really a list of other subscriptions cannot silently add itself to your sources.
- Source health is tracked with [Thompson sampling](https://en.wikipedia.org/wiki/Thompson_sampling)
  over Beta evidence, so sources that keep producing good servers get tried more often.

Per-source statistics, filters and the source list live under **Auto Mode → Sources**.

### Dashboard

The app opens on a dashboard instead of the server list — connection state, live throughput,
session traffic and the Auto Mode button on one screen. The server list is one swipe to the right.

It is a fixed dark instrument panel rather than a Material theme, with segmented tick rings,
bars and sparklines drawn on Canvas — quantised on purpose, so a jittering measurement reads
as an instrument rather than implying precision it does not have.

### Separate app

`applicationId` is `com.v2rayv.app`, so v2rayV installs alongside v2rayNG with its own data,
its own launcher icon and its own notification mark.

---

## Building

Requires JDK 21, Android SDK platform 37 with build-tools 37.0.0, and NDK 29.

```bash
cd V2rayNG
./gradlew assemblePlaystoreDebug -PABI_FILTERS=arm64-v8a
./gradlew testPlaystoreDebugUnitTest --tests "com.v2ray.ang.automode.*"
```

Two things are not in the repository and must be fetched before the first build:

- **`libv2ray.aar`** — 56 MB, from the [AndroidLibXrayLite releases](https://github.com/2dust/AndroidLibXrayLite/releases)
  whose tag matches the pinned submodule. Drop it in `V2rayNG/app/libs/`.
- **The `hev-socks5-tunnel` native libraries.** Run `compile-hevtun.ps1` (Windows) to build them
  into `V2rayNG/app/libs/<abi>/`. On Windows this also materialises the submodule's git-symlink
  headers as real files, without which the compiler reads a path string as C.

The Kotlin namespace stays `com.v2ray.ang` even though the application id changed — hev registers
its JNI methods against that package name.

### Notes inherited from upstream

- geoip.dat and geosite.dat live in `Android/data/com.v2rayv.app/files/assets` (the path differs on
  some devices). The download feature pulls the enhanced build from
  [v2ray-rules-dat](https://github.com/Loyalsoldier/v2ray-rules-dat), which needs a working proxy first.
- The aar can be rebuilt from [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite).
- On WSA, VPN permission needs `appops set com.v2rayv.app ACTIVATE_VPN allow`.

---

## Credits and licence

v2rayV is a fork of [v2rayNG](https://github.com/2dust/v2rayNG) by [2dust](https://github.com/2dust),
which does all of the heavy lifting — the cores, the protocols, the tunnel. Auto Mode and the
dashboard are the additions here.

Licensed under **GPL-3.0**, the same as upstream. See [LICENSE](LICENSE).

Cores: [Xray-core](https://github.com/XTLS/Xray-core) · [v2fly-core](https://github.com/v2fly/v2ray-core) ·
[hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)
