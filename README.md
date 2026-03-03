# LessLag

LessLag is an advanced server performance intelligence system for Minecraft, designed to automatically monitor, diagnose, and mitigate sources of lag. Unlike basic entity-clearing plugins, LessLag employs adaptive monitoring and granular control to maintain optimal Tick Per Second (TPS) rates without disrupting legitimate player activity.

The plugin features a modular architecture, allowing administrators to enable or disable specific optimizations for Redstone, Entities, Mob AI, and Chunk Loading.

## Documentation Index

- [Configuration Reference](CONFIGURATION.md)
- [Operations Runbook](OPERATIONS.md)
- [SpigotMC Description Draft](SPIGOTMC_DESCRIPTION.md)
- [Modrinth Description Draft](MODRINTH_DESCRIPTION.md)

## Key Features

### Redstone Control
The Redstone Control module prevents lag machines and optimizes redstone circuits by identifying and throttling high-frequency activations.

*   **Adaptive Monitoring**: The system automatically disables intensive redstone checks when the server TPS is above a configurable threshold (default: 19.5), conserving resources during stable periods.
*   **Circuit Stabilization**: If a single chunk exceeds the maximum allowed redstone updates per second, the plugin temporarily "freezes" redstone activity in that chunk to prevent a server crash.
*   **Clock Detection**: Advanced algorithms detect rapid on/off cycling (clocks) and can automatically break or disable the source blocks.
*   **Piston Limiter**: Limits the number of piston extends/retracts per chunk per tick to prevent piston-based lag machines or flyers from degrading performance.

### Entity Management
This module provides strict control over entity populations to prevent world overload and entity cramming.

*   **Global & Per-Chunk Limits**: Enforces hard limits on specific entity categories (Monsters, Animals, Ambient, etc.) per chunk and per world.
*   **Smart Cleaning**: The `ActionExecutor` intelligently removes the "least important" entities first (e.g., furthest from players) when limits are reached.
*   **Protection System**: Critical entities are automatically protected from removal. This includes:
    *   Named mobs (Name tags)
    *   Tamed animals
    *   NPCs (Citizens/Shopkeepers)
    *   Entities with specific metadata tags
    *   Vehicles with passengers

### AI Optimization (Frustum Culling)
The AI Optimization module reduces the server-side processing overhead of mob AI, which is often the leading cause of tick lag.

*   **Frustum Culling**: Entities that are outside a player's field of view (behind them) or obstructed by blocks have their AI usage restricted.
*   **Distance-based Deactivation**: Mobs beyond a certain range from players have their AI completely disabled until a player approaches.
*   **Piston/Farm Safety**: The module is designed to be safe for mob farms, ensuring that gravity and collision physics remain active even when AI is disabled.

### Farm & Villager Optimization
Targeted optimizations for gameplay elements that historically cause performance issues.

*   **Villager Optimizer**: Villagers trapped in trading halls (1x1 spaces) have their pathfinding AI disabled. This eliminates the heavy calculations caused by villagers trying to pathfind out of their cells. AI is temporarily restored when a player interacts for trading.
*   **Breeding Limiter**: Prevents animal breeding if the chunk limit for that specific species is already reached, stopping exponential growth in farms.
*   **Density Optimizer**: In high-density areas (e.g., cow crushers), the plugin selectively disables collisions and AI for excess mobs to maintain performance.

### System Monitoring & Diagnostics
LessLag provides real-time insights into server health, helping administrators identify the root cause of lag.

*   **Lag Source Analyzer**: A powerful diagnostic tool that scans all loaded chunks and active threads to identify:
    *   **Entity Hotspots**: Chunks with abnormal entity counts.
    *   **Chunk Loading**: Worlds with excessive chunk loading rates (exploration lag).
    *   **Plugin Load**: Other plugins consuming excessive tick time.
*   **Predictive Optimization**: Analyzes MSPT (Milliseconds Per Tick) trends to detect performance degradation *before* it affects TPS, triggering proactive cleanup measures.
*   **Garbage Collection Monitor**: Alerts administrators to frequent or prolonged Java Garbage Collection pauses, which often indicate memory leaks or insufficient RAM.

## Installation

1.  Download the latest `LessLag.jar` file.
2.  Place the file into your server's `plugins` directory.
3.  Restart the server to generate the configuration files.
4.  Navigate to `plugins/LessLag/config.yml` to adjust settings to your preference.

## 5-Minute Operator Quick Start

If this is your first deployment, use this sequence to baseline safely:

1. Start server and run `/lg status` to confirm all core modules are loaded.
2. Run `/lg health` to capture a pre-change snapshot (TPS/MSPT/memory/world load).
3. Run `/lg tickmonitor` and `/lg trace` during your busiest period.
4. If farms are your hotspot, check `/lg density`, `/lg breeding`, `/lg villager`.
5. Tune only one section at a time in `config.yml`, then use `/lg reload`.
6. Re-check `/lg status` and `/lg tickmonitor` after 10-15 minutes.

This avoids over-tuning and makes it easy to attribute wins/regressions.

## Commands

All commands require the `lesslag.admin` permission.

| Command | Description |
| :--- | :--- |
| `/lg status` | Performance dashboard with health score, MSPT percentiles, uptime, module state, queue status, and spike counters. |
| `/lg health` | Comprehensive health report (TPS, MSPT, memory pressure, entities, and recommendation context). |
| `/lg tps` | Real-time TPS and MSPT metrics with 1m, 5m, and 15m context windows. |
| `/lg tickmonitor` | Tick diagnostics including MSPT distribution (`P50`, `P95`, `P99`), avg/min/max, and spike insights. |
| `/lg sources` | Runs Lag Source Analyzer to detect expensive chunks, entities, and plugin/thread hotspots. |
| `/lg trace` | Runtime bottleneck tracing summary with total spikes, worst spike culprit, and recent spike context. |
| `/lg density` | Density Optimizer status: configured limits, bypass rules, and runtime suppression statistics. |
| `/lg breeding` | Breeding Limiter status: current limit, blocked events, and last blocked location/type. |
| `/lg entities` | Entity limiter and cleanup status summary. |
| `/lg redstone` | Redstone monitor and suppressor overview. |
| `/lg predictive` | Predictive optimization trend status and trigger details. |
| `/lg frustum` | Mob AI/frustum optimization status and active counts. |
| `/lg villager` | Villager optimizer status and optimized/restored counts. |
| `/lg chunks` | Chunk/world guard controls and loaded chunk pressure view. |
| `/lg worldguard` | World chunk guard overview and mitigation state. |
| `/lg thresholds` | Auto-response thresholds and configured actions by severity. |
| `/lg memory` | Memory monitor state and leak-trend summary. |
| `/lg gc` | Current RAM usage and GC safety info (manual GC intentionally restricted). |
| `/lg gcinfo` | Detailed GC collector statistics and timing. |
| `/lg clear` | Controlled cleanup actions (`items`, `xp`, `mobs`, `hostile`, `all`). |
| `/lg ai` | Mob AI emergency controls (`disable`, `restore`, `status`). |
| `/lg restore` | Restores temporary emergency changes made by protection actions. |
| `/lg setup` | Starts Setup Advisor for guided baseline tuning. |
| `/lg web` | Web integration status and analyzer endpoints. |
| `/lg reload` | Reloads `config.yml` and `messages.yml` without restart. |

### Command Usage Patterns (Recommended)

- **Daily checks**: `/lg status`, `/lg health`
- **During lag spikes**: `/lg tickmonitor`, `/lg trace`, `/lg sources`
- **Farm pressure triage**: `/lg density`, `/lg breeding`, `/lg entities`
- **Emergency response**: `/lg ai disable`, `/lg clear hostile`, `/lg restore`
- **After config edits**: `/lg reload` then re-check `/lg status`

## Permissions

*   `lesslag.admin`: Grants full access to all LessLag commands and notifications.
*   `lesslag.notify`: Allows receiving automatic lag alerts and performance warnings.

## Configuration

The plugin is highly configurable. For a detailed explanation of every option, please refer to the [Configuration Documentation](CONFIGURATION.md).

For practical production operations (incident workflow, tuning order, and validation checklists), see the [Operations Runbook](OPERATIONS.md).

## Tuning Profiles (Safe Starting Points)

These are suggested starting points before custom tuning:

- **Survival SMP (10-40 players)**
    - Keep `workload-limit-ms: 2`
    - Keep `mob-ai.active-radius` around `40-48`
    - Keep `entities.chunk-limiter.max-entities-per-chunk` around `40-60`
- **Skyblock / Farm-heavy**
    - Keep `density-optimizer.enabled: true`
    - Lower `density-optimizer.limits` for known farm species first
    - Keep `breeding-limiter.max-animals-per-chunk` conservative (`10-20`)
- **Minigame / Lobby network**
    - Disable modules you do not need (e.g. farm-focused modules)
    - Focus on `chunks.world-guard`, thresholds, and notifications

Apply changes incrementally and compare metrics before/after each change window.

## Incident Runbook (When TPS drops)

1. **Confirm impact** with `/lg status` and `/lg tickmonitor`.
2. **Identify likely source** with `/lg trace` and `/lg sources`.
3. **Apply minimal mitigation** (`/lg clear`, `/lg ai disable`) only if needed.
4. **Stabilize config** in one module at a time, then `/lg reload`.
5. **Validate recovery** via MSPT percentiles and spike count trend.
6. **Restore temporary emergency actions** using `/lg restore`.

Avoid stacking multiple aggressive actions without measurement between steps.

## Notes on Performance Overhead

LessLag command responses are designed to stay cheap during runtime:

- Most telemetry counters are incremented inside existing event/tick paths (no extra scheduler tasks).
- Percentiles (`P50`, `P95`, `P99`) are computed on-demand only when diagnostic commands are executed.
- Heavy analysis operations are spread over ticks via workload budgeting.
