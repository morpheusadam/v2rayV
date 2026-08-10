# project-map

How v2rayV is put together, why it is put together that way, and what will bite you if you
change it.

This is written for whoever works on it next — including a version of me with no memory of
today. It is not API documentation; the source has that. It is the part that does not
survive in code: the reasoning, the measurements behind the constants, and the handful of
decisions that look wrong until you know what they are for.

## Read in this order

| | |
|---|---|
| [01 — Architecture](01-architecture.md) | What the pieces are and how a press of the button travels through them |
| [02 — Auto Mode](02-auto-mode.md) | The pipeline, stage by stage, and every number in it |
| [03 — Reaching a blocked network](03-censorship.md) | The route ladder, the proxy race, and the caches under it |
| [04 — The companion repository](04-companion-repo.md) | The contract with `v2ray-config`, which nothing but a test enforces |
| [05 — Interface](05-interface.md) | The dashboard, the drawer, and why they are not Material |
| [06 — Build and release](06-build-and-release.md) | Toolchain, signing, the traps, and how a release is cut |
| [07 — Decisions not to undo](07-decisions.md) | The short list. Read this one even if you read nothing else |

## The shape of it in one paragraph

v2rayV is a fork of [2dust/v2rayNG](https://github.com/2dust/v2rayNG). Upstream supplies the
cores, the protocols and the tunnel, and all of that is untouched. What this fork adds is a
way to answer the question upstream leaves to the user — *which of these three hundred
servers actually works?* — by measuring them, and a screen built around the answer instead
of around the list. Everything new lives under `com.v2ray.ang.automode`,
`com.v2ray.ang.notice` and `com.v2ray.ang.ui.dashboard`; changes elsewhere are small and
noted where they occur.

## The one thing to know before changing anything

Several constants in this project are the result of measurement, and at least one of them
is the opposite of what you would guess. Ranking candidate servers by lowest TCP ping
performed **worse than picking at random** — 2.1% against 7.5% — because the hosts that
answer fastest are CDN edges in front of dead proxies. If a change here makes the code look
more sensible, check [07](07-decisions.md) before assuming it is an improvement.

## What has never been tested

The path that gets the app working from a network that blocks GitHub — mirrors, then
discovered proxies, then the cache, then the bundled snapshot — compiles, has unit tests,
and has never once run on a genuinely blocked connection. Everything in
[03](03-censorship.md) is unproven. It is the most valuable evidence the project can get.
