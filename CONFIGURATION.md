# LessLag Configuration Guide

This document provides a detailed explanation of every option available in `config.yml`. The plugin is designed to be modular, allowing you to enable or disable features as needed.

---

## 1. Core Settings
Global settings for the plugin.

*   **`core.prefix`**: The prefix shown before plugin messages in chat (Default: `&b&lLessLag &8» &7`).
*   **`core.check-updates`**: Automatically check for updates on startup (`true`/`false`).
*   **`core.language`**: Language for messages (e.g., `en`). Loads `messages_en.yml`.
*   **`core.debug`**: Enable debug mode to print detailed logs to the console (Recommended: `false` unless debugging).

---

## 2. Modules
Core features for lag reduction and optimization.

### 2.1. Redstone Control
Prevents lag machines and optimizes redstone circuits.

*   **`modules.redstone.enabled`**: Enable the entire redstone module.
*   **`modules.redstone.max-activations-per-chunk`**: Maximum redstone activations allowed in a chunk before suppression (Default: `200`).
*   **`modules.redstone.window-seconds`**: Time window to count activations (Default: `2`s).
    *   *Example: If a chunk has >200 activations in 2 seconds, it gets suppressed.*
*   **`modules.redstone.cooldown-seconds`**: Duration to freeze redstone in the chunk after suppression (Default: `10`s).
*   **`modules.redstone.notify`**: Notify admins when a chunk is suppressed.

#### Advanced Redstone Settings
*   **`modules.redstone.advanced.enabled`**: Enable advanced limiting features.
*   **`modules.redstone.advanced.max-frequency`**: Max frequency per individual block (Hz). Throttles fast clocks.
*   **`modules.redstone.advanced.clock-detection`**:
    *   **`threshold`**: Activations per 5s to trigger clock detection (Default: `100`).
*   **`modules.redstone.advanced.piston-limit`**:
    *   **`max-pushes-per-chunk`**: Max piston pushes per chunk per tick (Default: `50`). Prevents massive flying machines from crashing the server.

### 2.2. Entity Management
Controls entity counts and limits.

#### Limits
*   **`modules.entities.limits.enabled`**: Enable hard limits.
*   **`modules.entities.limits.check-interval`**: Tick interval to check limits (600 ticks = 30s).
*   **`modules.entities.limits.per-chunk-limit`**: Max entities **per chunk** by type (monster, animal, etc.).
*   **`modules.entities.limits.per-world-limit`**: Max entities **per world**. Prevents abuse of chunk loading to bypass per-chunk limits.
*   **`modules.entities.limits.protected-metadata`**: Entities with this metadata (e.g., "NPC", "Shop") are never removed.
*   **`modules.entities.limits.protected-names`**: Entities with these nametags are never removed.

#### Smart Chunk Limiter
*   **`modules.entities.chunk-limiter.enabled`**: active scanning for overloaded chunks.
*   **`modules.entities.chunk-limiter.max-entities-per-chunk`**: Global max entities per chunk (limit before cleanup triggers).
*   **`modules.entities.chunk-limiter.scan-interval`**: Scan interval in seconds.
*   **`modules.entities.chunk-limiter.whitelist`**: Mobs that are ignored by the limiter (e.g., `VILLAGER`, `IRON_GOLEM`).

### 2.3. Mob AI Optimization
Disables AI for mobs that are far away or out of sight to save TPS.

*   **`modules.mob-ai.enabled`**: Enable AI optimization.
*   **`modules.mob-ai.active-radius`**: Radius around players where mobs have AI (Default: `48`). Outside this, AI is disabled.
*   **`modules.mob-ai.update-interval`**: Check usage every X ticks.
*   **`modules.mob-ai.protected`**: Mobs that always keep AI (e.g., `ENDER_DRAGON`, `WITHER`).
*   **`modules.mob-ai.fov-degrees`**: Field of View angle. Mobs outside this angle (behind player) may have AI disabled.
*   **`modules.mob-ai.behind-safe-radius`**: Safe radius behind the player where AI is kept active to prevent "sneaking up" issues.

### 2.4. Chunk Management
Optimizes chunk loading and unloading.

*   **`modules.chunks.view-distance`**:
    *   **`min`**: Minimum view distance allowed when reducing.
    *   **`reduce-by`**: Amount to reduce view distance by when lag occurs.
*   **`modules.chunks.simulation-distance`**: Similar settings for simulation distance.
*   **`modules.chunks.world-guard`**: Protects worlds from excessive chunk loading.
    *   **`max-chunks-per-player`**: Average loaded chunks per player threshold.
    *   **`overload-multiplier`**: Multiplier to determine overload state.
    *   **`actions`**: Actions to take when overloaded (`unload-unused`, `reduce-view-distance`).
*   **`modules.chunks.unload-unused`**: Automatically unload unused chunks.
*   **`modules.chunks.keep-spawn-loaded`**: Keep spawn chunks loaded (`true`/`false`).

---

## 3. System Monitoring
Tools for performance diagnosis.

*   **`system.tps-monitor`**: Monitor TPS history.
*   **`system.gc-monitor`**: Monitor Garbage Collection pauses.
    *   **`warn-threshold-ms`**: Warn if GC pause > 500ms.
*   **`system.memory-leak-detection`**: Detects memory leaks.
    *   **`check-interval-minutes`**: Interval to check heap usage.
    *   **`warn-slope-threshold`**: Warn if memory usage slope > X MB/min.
*   **`system.lag-source-analyzer`**: Analyzes lag sources.
    *   **`auto-analyze-tps`**: Trigger analysis if TPS drops below this value (e.g., `15.0`).
*   **`system.tick-monitor`**: Detects single tick spikes.
    *   **`threshold-ms`**: Log ticks taking longer than X ms.

---

## 4. Automation

### 4.1. Predictive Optimization
Predicts lag based on MSPT trends before TPS drops.
*   **`automation.predictive-optimization.slope-threshold`**: MSPT slope triggering the prediction.
*   **`automation.predictive-optimization.action`**: Action to take (`notify-admin`).

### 4.2. Thresholds
Configurable actions based on TPS levels.

*   **`minor`** (TPS 18.0): Light actions (clear items).
*   **`moderate`** (TPS 15.0): Medium actions (disable mob AI).
*   **`critical`** (TPS 10.0): Heavy actions (kill mobs, force GC).

---

## 5. Compatibility
Ensures LessLag plays nice with other plugins.

*   **`compatibility.check-version`**: Verify Minecraft version support.
*   **`compatibility.auto-detect`**: Automatically detect conflicting plugins.
*   **`compatibility.plugins`**:
    *   **`pufferfish-dab`**: Disable internal AI optimization if Pufferfish/DAB is detected.
    *   **`clearlag`**: Disable entity clearing if ClearLag is detected.
    *   **`mobfarmmanager`**: Adjust chunk limits if MobFarmManager is detected.

---

## 6. Health Report
Configuration for the `/lg health` command output. Toggle sections (`tps`, `cpu`, `memory`, `worlds`, etc.) on or off.
