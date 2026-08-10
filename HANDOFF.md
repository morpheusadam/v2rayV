# v2rayV — handoff

Written 2026-08-10. Branch `automode`, four commits on top of upstream `e8a82d98`.

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
| `automode/AutoModeEngine.kt` | The pipeline: fetch → import → filter → tcping → waved real ping → speed test → keep winners |
| `automode/AutoModeSpeedTester.kt` | Throughput measurement — no upstream equivalent |
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
| Auto Mode end to end | **Verified on the author's phone** — five servers with real measurements |
| Dashboard layout, swipe, drawer, Auto Mode card | Verified on the emulator, **disconnected state only** |
| App name, separate install, launcher icon | Verified — both packages coexist on the emulator |
| Dashboard **connected** state | **Never seen.** Green palette, live sparklines, elapsed timer, country flag and exit IP are all unexercised |
| Notification status icon in place | Built, not seen rendered |
| VPN behaviour on the emulator | Not meaningful; the emulator is not representative |

---

## Next steps

1. **Install `v2rayV-arm64-v8a.apk` on the phone, connect, and screenshot the dashboard.**
   Everything below depends on what that shows.
2. **Tune the speed-test budget from real numbers.** `AutoModeEngine` currently allows
   `MAX_SPEED_TEST = 10` new servers plus `MAX_CHAMPIONS_RETESTED = 8`, at roughly 8s each
   (`SPEED_TEST_SECONDS`), with `AutoModeSpeedTester.MAX_DOWNLOAD_MILLIS = 6000`. These
   were picked without hardware evidence.
3. **Confirm the update-check repository.** `AppConfig.APP_REPO` is set to
   `morpheusadam/v2rayV`, which is a guess. If the repo does not exist, "check for update"
   silently 404s.
4. **Decide on the second mockup screen.** `SpeedGauge` in `SecuroComponents.kt` is built
   and unused — it is the big "VPN SPEED" dial from
   `design/original-e029f0922dbf6b04ed8ed5e8a59801a1.webp`. It has no home yet.
5. **Consider a techno/digital typeface** for the readouts. The mockups use one; the
   current screen approximates it with the system font and wide tracking.
6. **Release signing.** A keystore exists but only debug APKs have been built and
   installed. Play Protect flags debug-signed VPN apps, which is what blocked the first
   install attempt — a signed release build should behave better.

## Open question for the author

Auto Mode kept 5 servers when `topCount` defaults to 10. Worth confirming whether that
was the speed-test stage rejecting the rest, or the funnel running out of candidates —
the run's own progress lines say which, and it decides whether the budgets above are too
tight.
