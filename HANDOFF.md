# v2rayV — handoff

Written 2026-08-10, revised the same day. Branch `automode`, pushed to
[morpheusadam/v2rayV](https://github.com/morpheusadam/v2rayV).

## What this is

A fork of [2dust/v2rayNG](https://github.com/2dust/v2rayNG) 2.3.3, turned into an
independent app called **v2rayV** ("for the victory"), carrying the Auto Mode feature
ported from the author's desktop fork
[v2rayN-Pro-Max](https://github.com/morpheusadam/v2rayN-Pro-Max), plus a new dashboard
home screen.

Three things were added, in this order:

| Commit | What |
|---|---|
| `9a012cd1` | Auto Mode: one press tests the user's subscription links and keeps the fastest servers |
| `b64e1350` | Dashboard home screen, live traffic plumbing |
| `bf839c02` | Rebrand to v2rayV, separate application id |
| `6c963afd` | Logo across launcher, TV banner and notification |

A second round then made the button actually usable from a censored network,
and made "fast enough" mean something:

| Commit | What |
|---|---|
| `48d9acf2` | Reach the sources from a network that blocks them — mirrors, a public-proxy finder, a hand-written HTTP/SOCKS client, byte-range sampling |
| `eebe446b` | Judge servers against the user's own line speed, and connect on the first one that reaches 70% of it |
| `e76fe721` | One press of power finds a server and connects; a six-hourly refresh keeps ten ready |
| `2e2c3fbe` | Route the whole device; drop per-app proxy and `QUERY_ALL_PACKAGES` |
| `e7502ec1` | Sign releases with a real key instead of the debug certificate |

---

## Environment — read this first

**Nothing is on PATH.** Every tool was installed under `C:\Users\morph\Projects\V2ray\.buildtools`
during the first session and must be pointed at explicitly.

```powershell
$env:JAVA_HOME    = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
$env:ANDROID_HOME = "C:\Users\morph\Projects\V2ray\.buildtools\android-sdk"
$env:NDK_HOME     = "C:\Users\morph\Projects\V2ray\.buildtools\android-sdk\ndk\29.0.14206865"
```

| Tool | Path / note |
|---|---|
| JDK 21 | `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot` |
| Android SDK | `.buildtools\android-sdk` — platform 37, build-tools 37.0.0, NDK 29 |
| adb | `.buildtools\android-sdk\platform-tools\adb.exe` |
| Emulator AVD | named `automode`, Android 35 x86_64 (`emulator -avd automode -no-snapshot-save -gpu swiftshader_indirect`) |
| Release keystore | `.buildtools\automode-release.jks`, alias/passwords all `automode` |

**`V2rayNG/signing.properties` is required for a release build and is gitignored**, so a
fresh clone has to recreate it. Four lines: `storeFile`, `storePassword`, `keyAlias`,
`keyPassword`. Without it a release build comes out unsigned rather than debug-signed,
which is deliberate. **Never regenerate the keystore** — Play Protect's reputation is
attached to the certificate, and a new key restarts it from nothing.

Build:

```powershell
cd C:\Users\morph\Projects\V2ray\v2rayNG\V2rayNG
.\gradlew.bat assemblePlaystoreDebug -PABI_FILTERS=arm64-v8a   # phone
.\gradlew.bat assemblePlaystoreDebug -PABI_FILTERS=x86_64      # emulator
.\gradlew.bat testPlaystoreDebugUnitTest --tests "com.v2ray.ang.automode.*"
```

### Traps that cost time last session

- **`Select-Object -First N` on a gradle pipeline kills the build.** It terminates the
  upstream pipeline early. Capture into a variable first, then filter:
  `$out = & .\gradlew.bat ... 2>&1; $out | Select-String ...`
- **`UtilsTest.test_isIpAddress` and `test_IsIpInCidr` fail on a clean upstream
  checkout too.** Verified by stashing. Not caused by this work; do not chase them.
  Use `--continue` when a full test run must not block a build.
- **The `hev-socks5-tunnel` submodule shows as dirty and should stay that way.** Its
  public headers are git symlinks that Windows checks out as text files containing the
  target path, which the compiler then reads as C. `compile-hevtun.ps1` materialises
  them as real copies before building; it is idempotent.
- **`compile-hevtun.ps1` only needs re-running if the hev submodule changes.** The
  built `.so` files already sit in `V2rayNG/app/libs/<abi>/` and are gitignored.
- **`libv2ray.aar` is gitignored too** — 56 MB, downloaded from the
  [AndroidLibXrayLite release](https://github.com/2dust/AndroidLibXrayLite/releases)
  whose tag matches the pinned submodule (`v26.7.31`). Re-download if `app/libs` is empty.

---

## Auto Mode

Ported from the desktop `ServiceLib`. **Verified working on the author's phone**: a run
produced five servers ranked `5.7MB/s · 30ms` down to `0.3MB/s · 571ms`.

| File | Role |
|---|---|
| `automode/AutoModeEngine.kt` | The pipeline: baseline → route → catalog → fetch → import → filter → tcping → waved real ping → speed test → accept → keep winners |
| `automode/AutoModeSpeedTester.kt` | Throughput measurement — no upstream equivalent |
| `automode/ThroughputProbe.kt` | The one place throughput is measured, for the line and for servers alike |
| `automode/AutoModeBaseline.kt` | What this connection does on its own; the number everything is a ratio against |
| `automode/ProxiedFetch.kt` | HTTP GET over HTTP CONNECT / SOCKS4a / SOCKS5, addressing the target by name |
| `automode/AutoModeProxy.kt` | Parses scraped `ip:port` lists; guesses protocol from the port |
| `automode/AutoModeProxyFinder.kt` | Races hundreds of proxies for one that reaches the subscription host |
| `automode/AutoModeNetwork.kt` | Route ladder (host → CDN mirrors → proxy), bundled snapshots, byte-range sampling |
| `automode/AutoModeScheduler.kt` | Six-hourly background refresh that keeps the reserve stocked |
| `automode/AutoModeSourceManager.kt` | Source list and health, persisted as one JSON blob in its own MMKV store |
| `automode/BetaSampler.kt` | Thompson sampling over Beta evidence |
| `automode/CountryHint.kt` | Country from a remark, and from a measured IP lookup |
| `service/AutoModeRunService.kt` | Foreground service hosting a run, in the core's process |
| `ui/automode/` | Sources, filters and per-source statistics screen |

### Decisions worth not undoing

- **TCP reachability never ranks, only drops.** Measured on a real pool, ranking by
  lowest tcping passed 2.1% against 7.5% for a random draw — the fastest-answering hosts
  are CDN edges fronting dead proxies.
- **Speed tests run strictly one at a time.** Two downloads racing over one radio measure
  the radio, not the servers.
- **Champions keep their existing guid** when they win again. Minting a fresh one each run
  would delete the entry the user has selected — and the one the tunnel is running on.
- **Bare subscription URLs are stripped from a fetched body** before it reaches
  `AngConfigManager.importBatchConfig`, which would otherwise treat them as subscriptions
  to *add* and then refresh everything the user has. Many free sources are exactly that.
- **Stage budgets are smaller than desktop's** because a phone run competes with the
  battery and one shared radio. They are guesses tuned on one device — see below.
- **The baseline and the server tests must stay the same measurement.** They are divided by
  one another. Measuring the line the textbook way — four to eight parallel streams — while
  measuring servers serially would mean nothing ever clears 70%. Both go through
  `ThroughputProbe`; do not "improve" one of them alone.
- **The destination is never resolved locally when a proxy is in play.** Anything taking a
  `java.net.Proxy` resolves first and hands over an IP, which is useless on a network that
  lies in DNS. It also sidesteps the JVM's broken SOCKS4 client (square/okhttp#1359), which
  matters because a quarter of scraped proxies listen on 4145.
- **`subs/all.txt` is a catalog of links, not a list of servers.** It is merged into the
  source list. Fetching it *as* a source imports nothing, because the import stage strips
  bare subscription URLs on purpose.
- **The app excludes itself from its own VPN**, which is what lets the baseline be measured
  while connected. Do not "fix" that exclusion.

36 unit tests in `app/src/test/java/com/v2ray/ang/automode/`, all passing.

---

## Dashboard

`ui/dashboard/` — the screen the app opens on; the server list is one swipe right.

- `SecuroTokens.kt` holds the palette. It is deliberately **not** a Material theme: the
  screen is a fixed dark instrument panel and looks the same in any app theme.
- `SecuroComponents.kt` has the segmented tick ring, segmented bars and sparkline, all
  drawn on Canvas. Segmented rather than smooth on purpose — it reads as an instrument
  and quantises a jittering measurement instead of implying precision it does not have.
- Traffic reaches the UI by broadcast (`MSG_TRAFFIC_STATS`) because the core's counters
  can only be read in the core's process, and **reading them resets them** — so there is
  exactly one reader. `NotificationManager.startSpeedNotification()` is that reader; it
  now runs whenever the tunnel is up rather than only when the speed notification is
  switched on, and ticks at 1s instead of 3s.
- The drawer wraps the pager, not a page. A modal drawer nested inside a horizontally
  scrolling page anchors to that page's moving origin and drifts across the screen.

---

## Branding

- `applicationId = "com.v2rayv.app"` — installs alongside v2rayNG, own data.
- `AppConfig.APP_REPO` is `morpheusadam/v2rayV`, and **that repository now exists**, so the
  update check resolves. It returns an empty list until a release is published, which is
  not an error.
- **The Kotlin namespace stays `com.v2ray.ang` on purpose.** hev registers its JNI methods
  against that class package (`-DPKGNAME` in `compile-hevtun`), so moving it means
  rebuilding the native libraries for a string nobody sees.
- Logo lives in `design/logo/`, generated by its own `build.js`. Its `android/` folder
  copies straight into `res`. **Do not hand-edit the copies** — re-run their generator.
- Notification status icon is the monochrome mark; its density-qualified PNGs were
  deleted because they would win over the vector on every real device.

---

## What is verified, and what is not

| | Status |
|---|---|
| Auto Mode end to end | **Verified on the author's phone** (first round) and on the emulator (second round) |
| One press of power → run → auto-connect | **Verified on the emulator.** Consent, run, connect, ~80s on a cold install |
| The 70% rule | **Verified with real numbers.** Baseline 3.89 MB/s → threshold 2.72 → accepted a server at 3.0 |
| Catalog merge | **Verified.** 76 links merged, 609 candidates imported, 39 working, 4 kept |
| Dashboard **connected** state | **Verified.** Green palette, elapsed timer, country flag and exit IP all render |
| "Your line" / "Through VPN" cards, live testing meter | **Verified on the emulator** — 3.9 vs 0.7, and 0.3 live mid-test |
| App name, separate install, launcher icon | Verified — both packages coexist on the emulator |
| Notification status icon in place | Built, not seen rendered |
| Release-signed APK | Built and signature-verified; **not yet installed on a phone** |
| The blocked-network path | **Never exercised.** The proxy finder, the mirrors and the bundled snapshot have not run on a network that actually blocks GitHub |
| Live DOWNLOAD/UPLOAD counters | Read zero on the emulator. The pipe is proven (the elapsed timer rides the same message) but no traffic was ever pushed through the tunnel to move them |
| VPN behaviour on the emulator | Not meaningful; the emulator is not representative |

---

## Next steps

1. **Install the release APK on a phone in Iran and press power once.** This is the only
   thing that exercises the whole point of the second round — the mirrors, the proxy race,
   the bundled snapshot. Everything about that path is written and unproven. The run's
   progress lines name which rung of the ladder worked.
2. **Check the live DOWNLOAD/UPLOAD cards while actually browsing.** They read zero on the
   emulator because nothing used the network. If they still read zero on a phone during a
   video, the cause is routing rather than the counters: `publishTrafficStats` deliberately
   reports only *proxied* bytes, and traffic sent direct by a routing rule is excluded.
3. **Tune the speed-test budget from real numbers.** `AutoModeEngine` allows
   `MAX_SPEED_TEST = 10` new servers plus `MAX_CHAMPIONS_RETESTED = 8`, at roughly 8s each
   (`SPEED_TEST_SECONDS`), with `ThroughputProbe.MAX_DOWNLOAD_MILLIS = 6000`. Still guesses.
   The observed run kept 4 of a possible 10, so the funnel, not the cap, was the limit.
4. **Refresh the bundled snapshots at each release.** `app/src/main/assets/automode_*.txt`
   are copies of the `v2ray-config` repo taken by hand. They only matter on a first run
   from a blocked network, but they go stale between releases.
5. **Decide on the second mockup screen.** `SpeedGauge` in `SecuroComponents.kt` is built
   and unused — the big "VPN SPEED" dial from the mockups. It has no home yet.
6. **Consider a techno/digital typeface** for the readouts. The mockups use one; the
   current screen approximates it with the system font and wide tracking.

## Open questions for the author

- **`acceptFraction` is 0.70 on no evidence.** It is the number that decides how long a
  first connection takes: higher means a longer hunt for a better server. Worth revisiting
  once there are real runs on a real line.
- **The design mockups in `design/original-*.webp` are still untracked.** Their provenance
  is unknown, so they were left out of the repository rather than published under GPL.
- **Play Protect will still warn on install.** The debug-certificate problem is fixed; the
  "developer we have not seen before" warning is a reputation judgement on the signing key
  that Google says appeals cannot lift. It only fades with installs on the same key.
