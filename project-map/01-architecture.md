# 01 — Architecture

## What is ours and what is upstream's

Almost all of this repository is [2dust/v2rayNG](https://github.com/2dust/v2rayNG). The cores,
the protocol parsers, the VPN service, the tunnel, the config generation — untouched, and
worth keeping untouched so that upstream can still be merged.

Everything this fork adds lives in four places:

```
app/src/main/java/com/v2ray/ang/
├── automode/          the whole of Auto Mode — this is the fork
├── notice/            remote notices and in-app update
└── ui/
    ├── dashboard/     the home screen
    └── automode/      Auto Mode's own settings screen
```

Changes outside those are deliberate and few. The ones that exist:

| File | Change | Why |
|---|---|---|
| `core/CoreConfigManager.kt` | Traffic statistics are always enabled | Upstream strips them unless the speed notification is on; the dashboard reads them whenever the tunnel is up |
| `handler/SettingsManager.kt` | Default routing preset is Iran, not China | See [07](07-decisions.md) |
| `handler/NotificationManager.kt` | The stats loop runs whenever the tunnel is up, ticks at 1s, and feeds Smart Switch | Same reason; it is the only reader of the core's counters |
| `ui/main/MainScreen.kt` | Dashboard is the whole main screen | The inherited server list is no longer a swipe away |
| `ui/main/MainDrawer.kt` | Rewritten in the dashboard's visual language | See [05](05-interface.md) |
| `AndroidManifest.xml` | Separate `applicationId`, no `QUERY_ALL_PACKAGES` | Installs alongside v2rayNG; the permission was only needed for per-app proxy, which was dropped |

## Two processes, and why it matters

The app runs in two processes. This is upstream's design and it explains several things
that otherwise look strange.

```
  UI process                        core process (:core)
  ─────────────                     ────────────────────
  MainActivity                      V2RayVpnService
  MainViewModel                     CoreServiceManager   ← owns the Xray core
  MainRepository                    NotificationManager  ← the only reader of the counters
        ▲                           AutoModeRunService   ← hosts an Auto Mode run
        │                           SmartSwitchController
        └───────── broadcasts ──────────────┘
```

Consequences you will hit:

- **The core's traffic counters reset when read.** There can therefore be exactly one
  reader, and it is `NotificationManager`. Anything that wants those numbers gets them by
  broadcast, not by asking the core.
- **An Auto Mode run happens in the core process**, because it starts and stops cores.
  Progress reaches the UI as `MSG_AUTOMODE_*` broadcasts.
- **MMKV is opened in multi-process mode.** Both sides read and write the same store, which
  is why a run can leave statistics that the settings screen picks up on `onResume`.
- **Anything that must work with the app closed belongs in the core process.** Smart Switch
  was written in the view model first and moved for exactly this reason.

## One press, end to end

```
power button
   │
   ▼
MainViewModel.handleFabAction ──► nothing ready? ──► AutoModeRunService (core process)
   │                                                      │
   │                                                      ▼
   │                                              AutoModeEngine.run()
   │                                                      │
   │                              first server ≥ threshold │  MSG_AUTOMODE_READY
   │  ◄───────────────────────────────────────────────────┘
   ▼
startTunnel(guid) ──► V2RayVpnService ──► core up ──► NotificationManager.startSpeedNotification
                                                              │
                                            MSG_TRAFFIC_STATS │  every 1s
                                                              ▼
                                              dashboard meters · SmartSwitchController
```

The run does not stop when the first server is accepted. It carries on filling the reserve,
which is what makes the *next* press instant. See [02](02-auto-mode.md).

## Where state lives

| What | Where | Notes |
|---|---|---|
| Server profiles | MMKV, upstream's stores | Auto Mode writes into scratch groups and one "top" group |
| Auto Mode settings and source health | MMKV `AUTO_MODE` store, one JSON blob | `AutoModeSourceManager`; rewritten in full on every `save()`, so keep it small |
| The reserve | The `automode-top` server group | Ordered best first; `AutoModeReserve` walks it |
| Last fetched lists | `filesDir/automode/` | `AutoModeCache`; see [03](03-censorship.md) |
| Shipped fallback lists | `assets/automode_*.txt` | Only for a phone that has never fetched successfully |

The source store being a single JSON blob is a real constraint. It is parsed and rewritten
whole, and parsing it on the main thread has caused an ANR before. This is why the catalog
merge is capped — see [02](02-auto-mode.md).
