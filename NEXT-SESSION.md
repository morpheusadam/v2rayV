# Prompt for the next session

Paste this whole file as the opening message.

---

Project: `C:\Users\morph\Projects\V2ray\v2rayNG` — branch `automode`, pushed to
[morpheusadam/v2rayV](https://github.com/morpheusadam/v2rayV).

**Read `HANDOFF.md` first.** It carries the build environment, the traps, and the
decisions that must not be undone. Then read this, which is what changed after it was
last revised and what is still open.

---

## Where things stand

v2rayV is a fork of v2rayNG that connects itself. One press of power measures the user's
line, finds servers, and connects on the first one fast enough — then keeps going in the
background so the next press is instant.

Verified working on an emulator, end to end:

- Power press → VPN consent → Auto Mode run → connected, ~80 s on a cold install.
- Baseline measured at 3.89 MB/s → threshold 2.72 → accepted a server at 3.0. The 70%
  rule fired with real numbers.
- Catalog merged 76 links, imported 609 candidates, 39 tunnelled, 4 kept.
- Connected dashboard renders: green palette, flag, elapsed timer, exit IP.
- Countdown card and 7-step timeline, "next connection" pill, notice slot (empty).

**Never exercised: the censored-network path.** The proxy finder, the CDN mirrors and the
bundled snapshot exist, compile, and have unit tests, but have not once run on a network
that actually blocks GitHub. That is the single most valuable thing the next session can
get evidence about, and everything in `ProxiedFetch`, `AutoModeProxyFinder` and
`AutoModeNetwork` is unproven until it does.

---

## What was added since HANDOFF.md was written

| Area | Where |
|---|---|
| Countdown + 7-step timeline while connecting | `ui/dashboard/ConnectingCard.kt` |
| "Next connection" — walk the reserve, re-run when spent | `automode/AutoModeReserve.kt` |
| Protocol/country ranking before any test | `automode/AutoModeRanker.kt` |
| Remote notice slot + in-app update | `notice/`, `ui/dashboard/NoticeCard.kt` |
| Pill button used by both | `ui/dashboard/SecuroComponents.kt` |
| New logo (fox/M, blue) | `design/logo/`, copied into `res` |

### Decisions in these that are load-bearing

- **`AutoModeRanker` orders by protocol and country read off the config — not by
  measurement.** Ranking by *tcping* was measured to be worse than random (2.1% vs 7.5%)
  because the fastest responders are CDN edges fronting dead proxies. That finding still
  holds; delays are still only used to drop the dead. Randomness is kept *within* each
  tier so a run explores rather than re-confirming. Do not "improve" this into a sort.
- **Country order is DE, NL, FR, TR**, set by the owner. Turkey has the lowest ping of the
  four but the least capacity, so it sorts behind the European ones.
- **Protocol order follows 2026 reporting on Iranian DPI**: VLESS+REALITY (and
  XTLS-Vision) first, Hysteria2/TUIC next, WireGuard last because it is reliably detected.
- **`ThroughputProbe` measures both the user's line and every server.** They are divided
  by one another, so they must stay the same measurement. The line is deliberately *not*
  measured the textbook way (4–8 parallel streams) — do that and nothing ever clears 70%.
- **`AutoModeReserve.next` does not wrap.** Working through all ten and still being
  unhappy is evidence the batch is bad; handing back the first again would hide that.
- **The notice slot's normal state is drawing nothing at all.** Every failure path —
  no file, no network, bad JSON, wrong version, dismissed — ends with an empty slot.
- **`subs/all.txt` is a catalog of links, not servers.** It is merged into the source list.
  Fetching it *as* a source imports nothing, because the import stage strips bare
  subscription URLs on purpose.

---

## Open, in the order I would take them

1. **Test on an Iranian network.** Install the release APK, press power once. The run's
   progress lines name which rung of the route ladder worked (direct → CDN mirror →
   proxy). If it says "no working proxy among 600 tried", the proxy list is the problem,
   not the code — see `v2ray-config/proxies/PROMPT.md`, which specifies what that file has
   to contain and how to validate it.
2. **Live DOWNLOAD/UPLOAD read zero.** The pipe is proven — the elapsed timer rides the
   same broadcast — so on the emulator it was simply that nothing used the network. If it
   still reads zero on a phone while streaming, the cause is routing:
   `NotificationManager.publishTrafficStats` deliberately reports only *proxied* bytes, and
   traffic a routing rule sends direct is excluded. Decide whether that is still right.
3. **Play Protect still warns.** Confirmed on the owner's phone with the correctly signed
   release. The debug-certificate flag is fixed; what remains is a reputation judgement on
   the signing key that Google's own documentation says appeals cannot lift. The one
   remaining lever is dropping `REQUEST_INSTALL_PACKAGES` (added for in-app update) —
   one line in `AndroidManifest.xml`, and the notice's update button falls back to opening
   the release page. The owner chose in-app updating knowing this.
4. **`notice.json` is not published yet.** `v2ray-config/app/notice.json` and its README
   exist locally, but that folder is not a working copy of the public repo — the URL 404s,
   which is the correct "show nothing" path. Publish them when a banner is first needed.
5. **Refresh the bundled snapshots each release.** `app/src/main/assets/automode_*.txt`
   are hand-copied from `v2ray-config`. They only matter for a first run on a blocked
   network, and they go stale between releases.
6. **`acceptFraction` is 0.70 on no evidence.** It decides how long a first connection
   takes. Worth revisiting once there are real runs on a real line.

---

## Things that will waste your time if you do not know them

- **The emulator's software renderer ANRs.** Two "isn't responding" dialogs during testing
  were both graphics stalls — the main thread blocked in `HardwareRenderer.setStopped`
  with no app frame in the stack. Check the trace in `/data/anr/` before believing an ANR
  is yours. (One *was* worth acting on: it led to finding real main-thread store parsing,
  since moved to IO.)
- **`UtilsTest.test_isIpAddress` and `test_IsIpInCidr` fail on clean upstream too.** Do
  not chase them. Everything under `com.v2ray.ang.automode.*` and `notice.*` should be
  green — 74 tests at last count.
- **A release build needs `V2rayNG/signing.properties`**, which is gitignored. Four lines:
  `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. Without it the release comes out
  unsigned rather than debug-signed, on purpose. **Never regenerate the keystore** — Play
  Protect's reputation is attached to that certificate.
- **`gh` is installed and authenticated** as `morpheusadam`, despite the global note
  saying otherwise.
- **The `design/original-*.webp` mockups are untracked on purpose** — provenance unknown,
  so they were kept out of a GPL repository.

---

## How the owner works

Persian. Answer in Persian; keep code, paths, commands and product names in Latin script.
Shell is PowerShell 7. They test on a real phone in Iran and send screenshots, which is
the only source of truth for the censored path — read them carefully, including file names
and sizes, because more than once the screenshot was of an older APK than the one just
built.
