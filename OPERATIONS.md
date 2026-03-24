# Operations Runbook

This guide is for server operators who need a reliable process for diagnosing and mitigating lag with LessLag.

## 0) Web-Assisted Baseline (Recommended First-Time Setup)

The fastest way to get a sensible `config.yml` for your hardware:

1. Run `/lg web link` in-game.
2. Open the generated link in a browser — the Setup Advisor auto-detects your hardware tier (CPU, cores, heap) and pre-fills all steps.
3. Choose a game profile (SMP / Skyblock / Minigame / Creative) and aggressiveness level.
4. Download the exported `lesslag-config.json` and place it in `plugins/LessLag/`.
5. Run `/lg apply` to atomically apply the config changes.
6. Run `/lg verify` to confirm the live server state matches expectations.
7. After your first peak session, run `/lg drift` to catch any manual edits that diverged from the snapshot.

Skip to §1 if you prefer manual tuning or are an experienced operator.

## 1) Daily Operating Workflow

1. Run `/lg status` for quick health/percentile view.
2. Run `/lg health` for expanded system context.
3. If anomalies appear, run `/lg tickmonitor` and `/lg trace`.
4. For farm-heavy servers, check `/lg breeding`. If you have enabled the Advanced farm modules, also check `/lg density` and `/lg villager`.

Use these outputs as your baseline before touching config.

## 2) Incident Workflow (TPS drop)

### Stage A — Confirm impact

- `/lg status`
- `/lg tickmonitor`

Look for:
- Low health score trend
- Rising MSPT percentiles (`P95`/`P99`)
- Increasing spike frequency

### Stage B — Identify likely source

- `/lg trace` for runtime spike/caller context
- `/lg sources` for hotspot analysis
- Optional module checks: `/lg redstone`, `/lg entities`, `/lg chunks`
  - If Advanced farm modules are enabled: `/lg density`, `/lg villager`
  - `/lg worldguard` only if `modules.chunks.world-guard.enabled: true` (Emergency — off by default)

### Stage C — Stabilize quickly (minimal blast radius)

Apply only what is necessary:

- `/lg clear hostile` or `/lg clear items`
- `/lg ai disable` (short-term emergency only)
- Tighten one config section, then `/lg reload`

### Stage D — Validate and recover

- Re-run `/lg status` and `/lg tickmonitor` after 10-15 minutes.
- Confirm percentile and spike trends improve.
- Use `/lg restore` to revert temporary emergency toggles.
- If the fix involved config changes, run `/lg drift` to confirm no unintended divergence from the last apply snapshot.

## 3) Command Matrix

### Observe

- `/lg status` — compact health dashboard
- `/lg health` — broad health report
- `/lg tps` — TPS/MSPT time windows
- `/lg tickmonitor` — spike + percentile diagnostics
- `/lg gcinfo` — GC collector and pause stats

### Diagnose

- `/lg trace` — bottleneck summary
- `/lg sources` — lag source analysis
- `/lg entities` — entity pressure view
- `/lg chunks` / `/lg worldguard` — chunk pressure view

### Mitigate

- `/lg clear <items|xp|mobs|hostile|all>`
- `/lg ai <disable|restore|status>`
- `/lg restore`
- `/lg reload`

### Farm & AI-specific

**Core (on by default)**
- `/lg breeding`

**Advanced (opt-in — enable in `config.yml`)**
- `/lg villager` — requires `modules.villager-optimizer.enabled: true`
- `/lg density` — requires `modules.density-optimizer.enabled: true`
- `/lg sources` — requires `system.lag-source-analyzer.enabled: true` (on by default)

**Experimental (opt-in)**
- `/lg frustum` — requires `modules.mob-ai.enabled: true`
- `/lg trace` — requires `system.bottleneck-analyzer.enabled: true`
- `/lg memory` — requires `system.memory-leak-detection.enabled: true`

**Emergency (off by default — disruptive)**
- `/lg worldguard` — requires `modules.chunks.world-guard.enabled: true`

### Web / Config Sync

- `/lg web link` — generate hardware-encoded URL for the web Setup Advisor
- `/lg apply` — atomically apply `lesslag-config.json` from the web export
- `/lg verify` — verify live config matches the last apply snapshot
- `/lg drift` — detect keys that have changed since the last apply snapshot
- `/lg confirm` — confirm a pending staged patch from `/lg apply`

## 4) Tuning Strategy

- Tune **redstone/entities** before aggressive global actions.
- Tune **density/breeding/villager/frustum** for farm-centric lag.
- Tune **chunks/world-guard** for exploration or multi-world pressure.
- Tune **automation thresholds** last.

Always change one module group at a time.

## 5) Metrics Interpretation

- **TPS** near 20 with stable MSPT percentiles indicates healthy load.
- **Rising P95/P99 MSPT** with stable TPS can indicate pending instability.
- **Frequent long spikes** usually indicate burst tasks, plugin contention, or chunk pressure.
- **Increasing GC pause time/frequency** can indicate memory pressure or leaks.

## 6) Anti-Patterns to Avoid

- Applying many aggressive actions at once without measuring impact.
- Tightening every limit globally before identifying hotspots.
- Running emergency AI disable permanently.
- Ignoring compatibility toggles when using other optimization plugins.
- Manually editing `config.yml` after `/lg apply` without running `/lg drift` to reconcile.

## 7) Change Management

Before major events or resets:

1. Snapshot current `config.yml`.
2. Apply one tuning batch.
3. Observe at peak load.
4. Keep notes of command outputs and changes.
5. Roll back quickly if player-facing behavior regresses.
6. After stabilising, re-export from the web Setup Advisor and `/lg apply` to keep the snapshot fresh.

- Tune **chunks/world-guard** for exploration or multi-world pressure.
- Tune **automation thresholds** last.

Always change one module group at a time.

## 5) Metrics Interpretation

- **TPS** near 20 with stable MSPT percentiles indicates healthy load.
- **Rising P95/P99 MSPT** with stable TPS can indicate pending instability.
- **Frequent long spikes** usually indicate burst tasks, plugin contention, or chunk pressure.
- **Increasing GC pause time/frequency** can indicate memory pressure or leaks.

## 6) Anti-Patterns to Avoid

- Applying many aggressive actions at once without measuring impact.
- Tightening every limit globally before identifying hotspots.
- Running emergency AI disable permanently.
- Ignoring compatibility toggles when using other optimization plugins.

## 7) Change Management

Before major events or resets:

1. Snapshot current `config.yml`.
2. Apply one tuning batch.
3. Observe at peak load.
4. Keep notes of command outputs and changes.
5. Roll back quickly if player-facing behavior regresses.
