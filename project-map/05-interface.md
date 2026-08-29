# 05 — Interface

## The screen the app opens on

`ui/dashboard/`. Connection state, live throughput, session traffic, exit IP with flag, an
elapsed timer, the power button and the Auto Mode button — on one screen.

It is a **fixed dark instrument panel**, not a Material surface. `SecuroTokens.kt` holds an
absolute palette that does not follow the system light/dark setting, on purpose: this screen
looks the same whatever theme the rest of the app is in.

`SecuroComponents.kt` draws the tick rings, bars and sparklines on Canvas, and they are
**segmented rather than smooth on purpose** — it reads as an instrument, and quantising a
jittering measurement is honest about the precision it does not have.

### The power button has three states

`PowerRing` used to take a single `connected` boolean and its own docstring said "colour
alone separates the states". There were two: red, and green once the tunnel was up. Pressing
it started a run that takes minutes, and for those minutes the button was **pixel-identical
to the idle button** — same red, same power glyph, an unfilled ring. The ring's fill came
from `DashboardState.connecting`, which nothing in the app ever set to true, so the one
mechanism the design had for showing "working" was wired to a constant.

| connected | working | a press means | glyph | accent | ring | label |
|---|---|---|---|---|---|---|
| no | no | connect | power | red | empty | NOT CONNECTED |
| no | yes | stop the run | **stop** | violet | sweeping | CONNECTING *or* SCANNING |
| yes | no | disconnect | **stop** | green | full | CONNECTED |
| yes | yes | **stop the run**, not the tunnel | **stop** | green | full | CONNECTED |

The fourth row is not a corner case — a run carries on filling the reserve after it
connects, so it is the ordinary state for a couple of minutes after every first connection.
`MainActivity.handleFabAction` tests the run **first**, so a press there stops the scan and
leaves the tunnel up. The spoken label follows that (`acc_stop_scan`), because a screen
reader saying "stop service" over a press that does not stop the service is worse than
saying nothing.

CONNECTING and SCANNING are different claims and only one of them is ever true. A run
started by the power button ends in a connection; one started from the Auto Mode card, or
by the background schedule, does not. `DashboardState.connecting` finally carries that
distinction — it was dead state, never set true by anything, which is what made the ring's
fill inert in the first place.

The glyph carries the change as well as the colour, deliberately — colour alone excludes
anyone who cannot separate red from green, and this is the only control on the screen. The
ring **sweeps** rather than sitting full while working: a static full ring is
indistinguishable from a finished one, and a run has no honest completion fraction to show.

Note that commit `ec27a999` fixed the stop behaviour on `MainBottomBar`'s FAB — which
belongs to the server list, a screen the user is not on when they press start.

### While a run is happening

`ConnectingCard.kt` shows a countdown and a seven-step timeline rather than a spinner, so a
slow step is visible as a slow step instead of as a hang. The steps come from the
`AutoModeStage` enum rather than from parsing progress text, so the timeline cannot drift
out of step with the engine when a message is reworded.

### Live traffic

Traffic reaches the UI by broadcast (`MSG_TRAFFIC_STATS`) because the core's counters can
only be read in the core's process — **and reading them resets them**, so there is exactly
one reader. `NotificationManager.startSpeedNotification()` is it. It runs whenever the
tunnel is up rather than only when the speed notification is switched on, and ticks at 1s.

If those numbers read zero, check that `stats` and `policy` survived into the generated core
config. Upstream strips them unless the speed notification preference is on, and that
preference defaults to off — this was the cause of DOWNLOAD/UPLOAD reading zero in every
build before `3001ce58`.

## The drawer

`ui/main/MainDrawer.kt`, rewritten to take its palette from `Securo` rather than
`MaterialTheme`. A stock Material sheet sliding over the instrument panel read as a
different app.

Grouped rather than flat: the old drawer was nine items of equal weight, which made the one
that matters — Auto Mode — no easier to find than the licence screen. Now Auto Mode and
Servers are emphasised cards, then `CONFIGURE`, then `APP`.

**The drawer wraps the content, not the other way round.** A modal drawer nested inside a
horizontally scrolling page anchors to that page's moving origin and drifts across the
screen with the swipe. The pager is gone but the nesting order is still the one that
behaves.

## The server list

Upstream's list still exists and still works, but it is **no longer a swipe away**. Swiping
right off the dashboard used to land on furniture inherited wholesale from another app,
reachable by accident. It is now somewhere you go on purpose — from the dashboard or from
the drawer's Servers entry — and the dashboard is the whole of the main screen.

It was not deleted, because it is the only way to see and select an individual server by
hand.

## Auto Mode's settings

`ui/automode/AutoModeSourcesActivity.kt` — sources, how many to keep, Smart Switch, protocol
and country filters, and per-source statistics.

The source list is **collapsed to the best five by default**. The catalog merges hundreds of
links; rendering a row for each turned this screen into an endless scroll of URLs the user
did not choose and cannot usefully act on. Auto Mode decides which sources to spend a run
on, and it decides from measured evidence, not from what is visible here. "Show all" is
there for anyone who wants to audit.

Subscriptions proper — the user's own — live behind the drawer's **Subscriptions** entry
(`SubSettingActivity`), which is upstream's manager and unchanged.

## The notice slot

`notice/` and `ui/dashboard/NoticeCard.kt`. A remote JSON document can put an announcement
or an update prompt on the dashboard.

**Its normal state is drawing nothing at all.** No file, no network, bad JSON, wrong version,
already dismissed — every path ends with an empty slot. The URL currently 404s, which is the
correct "show nothing" case.

## Strings

New strings go in `res/values/strings.xml`. Upstream ships many locales; anything added here
falls back to English until translated, which is fine — but do not reword an upstream string
without checking what it breaks in the other twenty locale files.
