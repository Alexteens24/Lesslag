# LessLag

**Server performance guardian for Paper/Spigot (1.20+) with Folia & Luminol support.**

LessLag is built for production servers that need stable TPS without disruptive “wipe everything” behavior. It focuses on three things:

- **Detect lag early**
- **Explain what is causing it**
- **Apply safe, configurable mitigation**

## Why use LessLag?

- **Actionable diagnostics**, not guesswork
- **Adaptive responses** based on live server pressure
- **Modular controls** for entities, redstone, AI, chunks, memory, and farms
- **Low overhead**: workload budgeting + on-demand deep metrics

## Highlights

- **`/lg status` dashboard** with health score, MSPT percentiles (`P50`, `P95`, `P99`), uptime, queue status, and spike counters
- **`/lg tickmonitor` diagnostics** with MSPT distribution + avg/min/max
- **Entity management** with protections for named/tamed/NPC entities
- **Redstone safeguards** (activation limits, clock detection, piston pressure control)
- **Farm protection — Core**: breeding limiter + spawner limiter, always on, zero config required
- **Farm optimization — Advanced** (opt-in): villager optimizer, density optimizer, mob farm optimizer
- **Lag source analysis — Advanced** (opt-in): `/lg sources` and `/lg trace` to identify hotspots
- **Mob AI optimization — Experimental** (opt-in): frustum/distance-based AI throttling
- **Predictive optimization** from MSPT trend analysis
- **Web Setup Advisor** via `/lg web link`: hardware-aware config baseline; apply-verify-drift workflow via `/lg apply`, `/lg verify`, `/lg drift`

## What makes it practical in production?

- **Operator-focused workflows** for daily checks and incident response
- **Targeted mitigation** before emergency-wide cleanup
- **Farm-aware controls** for villager halls and high-density breeding setups
- **Low-overhead telemetry model** (on-demand deep stats + workload budgeting)

## Useful Commands

- `/lg status` — live performance dashboard
- `/lg health` — health report and context
- `/lg tickmonitor` — tick/MSPT distribution diagnostics
- `/lg sources` — lag source analysis
- `/lg trace` — runtime bottleneck summary
- `/lg density` — density optimizer limits + suppression stats
- `/lg breeding` — breeding limiter blocked-event stats
- `/lg reload` — reload config/messages without restart
- `/lg web link` — generate hardware-encoded URL for the web Setup Advisor
- `/lg apply` — stage and apply web-exported `lesslag-config.json` (diff shown before commit)
- `/lg verify` — verify live config matches the last apply snapshot
- `/lg drift` — detect config drift since the last apply snapshot

## Quick Operations Workflow

0. **First deployment**: run `/lg web link`, open the URL, complete the Setup Advisor wizard, drop `lesslag-config.json` in `plugins/LessLag/`, then `/lg apply` → `/lg verify`
1. Baseline with `/lg status` and `/lg health`
2. During spikes, run `/lg tickmonitor` + `/lg trace`
3. Use `/lg sources` to identify likely hotspots
4. Mitigate minimally (`/lg clear hostile`, `/lg ai disable`) only when needed
5. Tune one module group, then `/lg reload`
6. Validate recovery through MSPT percentile and spike trend improvements
7. After manual config edits, run `/lg drift` to reconcile against the last apply snapshot

## Tuning by server style

- **SMP**: keep balanced defaults, tune entities/chunks gradually
- **Farm-heavy/Skyblock**: enable and tune the Advanced farm modules (`villager-optimizer`, `density-optimizer`, `mob-farm-optimizer`)
- **Lobby/Minigame**: farm modules are off by default — focus on chunks, block limits, and notifications

## Permissions

- `lesslag.admin` — full command access
- `lesslag.notify` — receive performance alerts
- `lesslag.setup` — setup advisor access

## Compatibility

- Paper/Spigot `1.20+`
- Folia supported (threaded-region scheduler auto-detected)
- Luminol supported (LuminolMC fork auto-detected)
- Compatibility toggles for Pufferfish DAB, ClearLag, and MobFarmManager

## Install

1. Put `LessLag.jar` in your `/plugins` folder
2. Restart the server
3. Configure `plugins/LessLag/config.yml`
4. Run `/lg status` and `/lg setup` to verify baseline

## Links

- Website: https://lesslag-web.vercel.app
- Source/Issues: https://github.com/Alexteens24/Lesslag

If you report an issue, include your server version, plugin list, and output from `/lg status`, `/lg tickmonitor`, and `/lg trace`.
