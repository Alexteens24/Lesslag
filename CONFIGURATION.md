# Configuration Reference

This document provides a detailed explanation of all configuration options available in `config.yml` for the LessLag plugin.

## Core Settings

These settings control the basic behavior of the plugin.

### General

*   `core.prefix`: The string prefixed to all chat messages sent by the plugin. Use `&` for color codes.
    *   **Default**: `&b&lLessLag &8» &7`
*   `core.check-updates`: Whether to check for new versions of LessLag on server startup.
    *   **Default**: `true`
*   `core.language`: The language code for message localization (e.g., `en`, `vn`). This maps to a `messages_{language}.yml` file in the plugin folder.
    *   **Default**: `en`
*   `core.debug`: Enables verbose logging to the server console. Useful for troubleshooting but should be disabled on production servers to avoid console spam.
    *   **Default**: `false`

### Workload Management

*   `workload-limit-ms`: The maximum time in milliseconds the plugin is allowed to spend per tick on heavy background tasks (like chunk analysis or entity processing). The plugin will split large tasks across multiple ticks to maintain 20 TPS. Increasing this value speeds up operations but increases the risk of TPS drops.
    *   **Default**: `2`

---

## Modules

The plugin is divided into several modules, each targeting a specific area of performance. You can enable or disable modules individually based on your server's needs.

### Module Tiers

| Tier | Default | Description |
| :--- | :--- | :--- |
| **Core** | On | Safe for all servers. No tuning needed on installation. |
| **Advanced** | Off | Recommended for specific server types (trading halls, mob grinders, dense farms). Enable when you have confirmed the relevant lag source. |
| **Experimental** | Off | Complex heuristics that may produce false positives. Enable with caution and monitor after each change. |
| **Emergency** | Off | Disruptive last-resort actions. Enable only if you understand and accept the behavior. |

Advanced and Experimental modules are available via their respective config keys and `/lg` commands. See `config.yml` for tier labels on each section.

### Redstone Control (`modules.redstone`)

Prevents lag machines and optimizes redstone circuits by limiting activation frequency.

*   `enabled`: Master switch for this module.
*   `adaptive-monitoring`: If set to `true`, the plugin skips intensive redstone checks when the server TPS is above the `min-tps` threshold. This saves resources when the server is running smoothly.
    *   **Default**: `true`
*   `min-tps`: The TPS threshold below which adaptive monitoring engages full scanning and suppression.
    *   **Default**: `19.5`

#### Stabilization (Suppressor)

*   `max-activations-per-chunk`: The maximum number of redstone updates allowed in a single chunk within the `window-seconds` period. If this limit is exceeded, redstone in that chunk is temporarily disabled.
    *   **Default**: `200`
*   `cooldown-seconds`: The duration in seconds that redstone activity remains suppressed in a chunk after exceeding the limit.
    *   **Default**: `10`
*   `window-seconds`: The time window in seconds for counting activations.
    *   **Default**: `2`
*   `notify`: Whether to alert administrators (with `lesslag.notify` permission) when a chunk is suppressed.
    *   **Default**: `true`

#### Advanced Limiting

*   `advanced.enabled`: Enables individual block frequency limits and clock detection.
*   `advanced.max-frequency`: The maximum number of redstone events allowed per specific block per second. This prevents single rapid-pulsing wires.
    *   **Default**: `20`
*   `advanced.clock-detection.enabled`: Enables detection of rapid on/off cycling (clocks).
*   `advanced.clock-detection.threshold`: The number of activations within 5 seconds required to flag a circuit as a clock.
    *   **Default**: `100`

#### Piston Limiter

*   `advanced.piston-limit.enabled`: Limits the number of piston pushes per chunk to prevent piston-based lag machines and flying machines from crashing the server.
*   `advanced.piston-limit.track-moving-blocks`: If `true`, tracks blocks moved by pistons for complex Redstone clocks. Disabling this improves RedstoneMonitor performance but may reduce detection accuracy for flying machines.
    *   **Default**: `true`
*   `advanced.piston-limit.max-pushes-per-chunk`: Maximum piston pushes allowed per chunk per tick.
    *   **Default**: `50`

#### Long-Term Detection

Identifies slow but persistent clocks that might evade short-term detection (e.g., a clock that pulses once every 10 seconds but runs forever).

*   `advanced.long-term.enabled`: Enables long-term pattern analysis.
*   `advanced.long-term.window-seconds`: The observation period in seconds.
    *   **Default**: `120`
*   `advanced.long-term.max-activations`: The activation threshold within the window to trigger actions.
    *   **Default**: `100`
*   `advanced.long-term.actions`: List of actions to take when detected.
    *   Valid values: `notify-admin`, `break-block`.

---

### Entity Management (`modules.entities`)

Controls entity populations to prevent world overload.

*   `limits.enabled`: Enables hard limits on entity counts.
*   `limits.check-interval`: How often (in ticks) to check entity counts.
    *   **Default**: `600` (30 seconds)
*   `limits.per-chunk-limit`: Maximum number of entities allowed per chunk, categorized by type.
    *   `monster`: Zombies, Skeletons, Creepers, etc.
    *   `animal`: Cows, Sheep, Pigs, etc.
    *   `water_animal`: Squid, Dolphins, Fish.
    *   `ambient`: Bats.
    *   `misc`: Armor Stands, Item Frames, Dropped Items.
*   `limits.per-world-limit`: Hard cap on the total number of entities allowed in a world, categorized by type. This aims to prevent global overload even if individual chunks are within limits.
*   `limits.protected-metadata`: A list of metadata tags that prevent an entity from being removed (e.g., `NPC`, `Shop`). Useful for compatibility with plugins like Citizens or Shopkeepers.
*   `limits.protected-names`: A list of custom names that protect entities from removal. E.g., "Nametagged Mob".

#### Smart Chunk Limiter

*   `chunk-limiter.enabled`: Enables the periodic scanning and removal of excess entities.
*   `chunk-limiter.max-entities-per-chunk`: The threshold at which the limiter starts removing excess entities.
    *   **Default**: `50`
*   `chunk-limiter.scan-interval`: How often (in seconds) to scan loaded chunks.
*   `chunk-limiter.whitelist`: A list of entity types that are immune to chunk limiting.
    *   **Default**: `PLAYER`, `VILLAGER`, `WOLF` (and other pets).

---

### AI Optimization / Frustum Culling (`modules.mob-ai`)

Disables the AI of mobs that are distant or not visible to players, significantly reducing CPU load.

*   `enabled`: Master switch for this module.
*   `active-radius`: The radius in blocks around a player where mobs retain their AI. Mobs outside this radius have AI disabled.
    *   **Default**: `48`
*   `update-interval`: Frequency in ticks to update AI states.
*   `protected`: List of entity types that should arguably *always* have AI (e.g., `ENDER_DRAGON`, `WITHER`, `VILLAGER`).
*   `fov-degrees`: The field of view in degrees. Mobs outside this cone (behind the player) may have their AI disabled.
    *   **Default**: `70.0`
*   `behind-safe-radius`: A small radius behind the player where AI remains active to prevent "sneaking" mobs from freezing mid-attack.
    *   **Default**: `5.0`
*   `vertical-fov-factor`: Multiplier for vertical FOV flexibility.

---

### Villager Optimizer (`modules.villager-optimizer`)

Optimizes villagers, especially in trading halls, by disabling pathfinding for trapped villagers.

*   `enabled`: Master switch for this module.
*   `check-interval`: Frequency in ticks to scan for optimization candidates.
    *   **Default**: `600`
*   `ai-restore-duration`: Duration in seconds to restore AI after a player interacts with a villager (e.g., for trading).
    *   **Default**: `30`
*   `optimize-trapped`: If `true`, only optimizes villagers confined to a 1x1 space or similarly restricted area.
    *   **Default**: `true`

---

### Breeding Limiter (`modules.breeding-limiter`)

Prevents excessive animal breeding in farms.

*   `enabled`: Master switch for this module.
*   `max-animals-per-chunk`: The maximum number of same-type animals allowed in a chunk before breeding is blocked.
    *   **Default**: `20`
*   `message`: The message displayed to a player when breeding is prevented. Set to `""` to disable the message.

---

### Density Optimizer (`modules.density-optimizer`)

Reduces the impact of high-density mob farms (e.g., entity cramming) by disabling collisions and AI for excess mobs.

*   `enabled`: Master switch for this module.
*   `check-interval`: Frequency in ticks to scan for high-density areas.
    *   **Default**: `40`
*   `limits`: A map of EntityType to count (e.g., `COW: 10`). If a chunk contains more than the specified number of that entity, the excess will have their AI and collisions disabled.
*   `bypass-tamed`: Protects tamed animals from optimization.
*   `bypass-named`: Protects named animals from optimization.
*   `bypass-leashed`: Protects leashed animals from optimization.

---

### Chunk Management (`modules.chunks`)

Manages server view distance and chunk loading to maintain TPS.

*   `enabled`: Master switch for this module.
*   `view-distance`: Dynamic view distance settings.
    *   `min`: The minimum allowed view distance.
    *   `reduce-by`: How much to reduce view distance when load is high.
*   `simulation-distance`: Dynamic simulation distance settings.
    *   `min`: The minimum allowed simulation distance.
    *   `reduce-by`: How much to reduce simulation distance when load is high.

#### World Chunk Guard

*   `world-guard.enabled`: Monitors loaded chunks per world.
*   `world-guard.check-interval`: Frequency in ticks to check chunk counts.
*   `world-guard.max-chunks-per-player`: The target ratio of loaded chunks per player.
*   `world-guard.overload-multiplier`: The factor (e.g., 2.0 = 200%) above the limit that triggers aggressive unloading/actions.
*   `world-guard.evacuate-world`: The world name to teleport players to if the current world is severely overloaded.
*   `world-guard.actions`: Actions to take when overload is detected.
    *   Valid values: `notify-admin`, `reduce-view-distance`, `unload-unused`.

---

## System Monitoring

*   `system.tps-monitor`: Tracks TPS history.
    *   `history-length`: Seconds of TPS history to keep.
*   `system.gc-monitor`: Detects garbage collection pauses.
    *   `warn-threshold-ms`: Logs a warning if a GC pause exceeds this duration (e.g., 500ms).
*   `system.memory-leak-detection`: Monitors Old Gen memory usage.
    *   `notify`: Whether to send in-game alerts to admins. Set to `false` for console-only logging.
    *   `warn-slope-threshold`: Warns if memory usage grows faster than this rate (MB/min).
*   `system.lag-source-analyzer`: Identifies heavy entities/chunks.
    *   `auto-analyze-tps`: Automatically starts analysis if TPS drops below this value.
*   `system.tick-monitor`: Logs tick spikes.
    *   `threshold-ms`: Logs any tick taking longer than this to execute.
*   `system.bottleneck-analyzer`: Stack-sampling analyzer for long main-thread stalls.
    *   `threshold-ms`: Start sampling when elapsed time since last tick exceeds this value.
    *   `sample-interval-ms`: Sampling period while the stall is ongoing.
    *   `min-samples`: Minimum collected samples required before a spike is reported.
    *   `report-cooldown-ms`: Minimum delay between spike reports to avoid console spam.

---

## Automation

### Predictive Optimization

Uses trend analysis to act before lag hits.

*   `predictive-optimization.slope-threshold`: The rate of MSPT increase (ms/s) that triggers optimization.
*   `predictive-optimization.min-samples`: Number of data points required to confirm a trend.
*   `predictive-optimization.action`: The command or action to execute when a negative trend is detected (e.g., `notify-admin`).

### TPS Thresholds

Configures automatic responses to specific TPS levels.

*   `thresholds.minor` (e.g., TPS < 18.0)
*   `thresholds.moderate` (e.g., TPS < 15.0)
*   `thresholds.critical` (e.g., TPS < 10.0)

For each level, you can configure:
*   `notify`: Alert types (`actionbar`, `chat`, `sound`).
*   `actions`: A list of remedial actions.
    *   **Available Actions**:
        *   `clear-ground-items`
        *   `clear-xp-orbs`
        *   `clear-mobs`
        *   `kill-hostile-mobs`
        *   `reduce-view-distance`
        *   `reduce-simulation-distance`
        *   `disable-mob-ai`
        *   `force-gc`
        *   `chunk-clean`
        *   `enforce-entity-limits`
        *   `unload-world-chunks`
        *   `notify-admin`

---

## Compatibility

Ensures LessLag plays nicely with other plugins.

*   `compatibility.check-version`: Verifies the server version is supported.
*   `compatibility.auto-detect`: Automatically detects known conflicting plugins.
*   `compatibility.plugins`: Individual toggles to disable specific LessLag features if a conflicting plugin is found:
    *   `pufferfish-dab`: Disables LessLag AI optimization.
    *   `clearlag`: Disables LessLag entity clearing.
    *   `mobfarmmanager`: Disables LessLag stacker/spawner logic.

---

## Health Report

Configures what information is shown in the `/lg health` command.

*   `tps`: Ticks per second.
*   `mspt`: Milliseconds per tick.
*   `cpu`: Process CPU usage.
*   `memory`: RAM usage (Heap/Non-Heap).
*   `disk`: Disk usage and I/O.
*   `worlds`: Loaded chunks and entities per world.
*   `entity-breakdown`: Detailed count of entities by type.

---

## Practical Tuning Order (Production)

Use this order to reduce risk and isolate side effects:

1. **Observe first**
    - Run `/lg status`, `/lg health`, `/lg tickmonitor`, `/lg trace` before edits.
2. **Limit burst sources**
    - Tune `modules.redstone` and `modules.entities` first.
3. **Tune AI and farm load**
    - Then adjust `modules.mob-ai`, `modules.villager-optimizer`, `modules.density-optimizer`, `modules.breeding-limiter`.
4. **Tune chunk pressure**
    - Then adjust `modules.chunks` and optional `modules.chunks.world-guard`.
5. **Tune automation thresholds last**
    - Finalize `automation.thresholds` and `automation.predictive-optimization` once baseline is stable.

Change one section at a time and validate for at least one peak session window.

---

## Profile Baselines

These are baseline ideas, not strict presets.

### SMP (Balanced)

- `workload-limit-ms`: `2`
- `modules.mob-ai.active-radius`: `40-48`
- `modules.entities.chunk-limiter.max-entities-per-chunk`: `40-60`
- `modules.density-optimizer.check-interval`: `40`

### Farm-Heavy / Skyblock

- Keep `modules.density-optimizer.enabled: true`
- Lower `modules.density-optimizer.limits` for dominant farm species first
- Use `modules.breeding-limiter.max-animals-per-chunk` in `10-20`
- Consider lower `modules.entities.chunk-limiter.max-entities-per-chunk`

### Lobby / Minigame

- Keep farm modules conservative or disabled if irrelevant
- Focus on `modules.chunks`, `modules.redstone`, and `automation.thresholds`
- Keep notifications clear to avoid alert fatigue

---

## Troubleshooting by Symptom

### Symptom: Frequent spike warnings in `/lg tickmonitor`

- Check `system.tick-monitor.threshold-ms` and `system.bottleneck-analyzer.threshold-ms`
- Run `/lg trace` for culprit patterns
- Reduce burst load in redstone/farm modules before tightening global limits

### Symptom: TPS unstable during exploration

- Review `modules.chunks` and `modules.chunks.world-guard`
- Check world chunk pressure using `/lg worldguard` and `/lg chunks`
- Lower view/simulation distances incrementally

### Symptom: Farm lag despite limiter modules enabled

- Run `/lg density` and `/lg breeding` to verify runtime counters are changing
- Lower species limits under `modules.density-optimizer.limits`
- Re-check bypass settings (`bypass-tamed`, `bypass-named`, `bypass-leashed`)

### Symptom: Memory pressure trends upward over time

- Review `system.memory-leak-detection.*`
- Verify `notify` and slope thresholds are appropriate
- Cross-check with `/lg gcinfo` for GC frequency and pause times

---

## Safe Change Checklist

- Capture before/after snapshots with `/lg status` and `/lg tickmonitor`
- Keep edits small (one module group per change)
- Use `/lg reload` after each change batch
- Keep a rollback copy of `config.yml`
- Revisit threshold actions if they trigger too aggressively

---

## Web Integration

LessLag can synchronise its configuration with the web Setup Advisor at **https://lesslag-web.vercel.app**.

### Workflow

1. `/lg web link` — encodes the current hardware profile (CPU model, core count, heap size, server fork, MC version, live TPS, installed plugins) into a compact base64url+gzip URL and prints it to the console.
2. Open the URL in a browser — the advisor auto-detects your hardware benchmark tier (from a 191-entry database) and pre-fills all wizard steps.
3. Work through the 5-step wizard (Hardware → Profile → Aggressiveness → Review → Export).
4. Download the exported `lesslag-config.json` and place it in `plugins/LessLag/`.
5. `/lg apply` — diffs the JSON patch against the live config, stages the changes, and prints a summary before committing anything.
6. `/lg confirm <id>` — atomically writes the staged changes to `config.yml` and reloads the plugin.
7. `/lg verify` — checks that every key in the last apply snapshot matches the live config; reports any mismatches.
8. `/lg drift` — identifies keys that have diverged from the baseline since the last apply; useful after manual edits.

### Notes

- The exported JSON is a **patch format**: it only contains keys that differ from LessLag defaults.
- `/lg apply` never silently overwrites keys; all proposed changes are shown before `/lg confirm` is required.
- Running `/lg drift` after manual edits to `config.yml` is recommended before re-running the web advisor to keep the snapshot current.
- The URL token contains no sensitive data — only hardware specs and plugin names.
