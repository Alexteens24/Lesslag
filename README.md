# LessLag

LessLag is an advanced server performance intelligence system for Minecraft, designed to automatically monitor, diagnose, and mitigate sources of lag. Unlike basic entity-clearing plugins, LessLag employs adaptive monitoring and granular control to maintain optimal Tick Per Second (TPS) rates without disrupting legitimate player activity.

The plugin features a modular architecture, allowing administrators to enable or disable specific optimizations for Redstone, Entities, Mob AI, and Chunk Loading.

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

## Commands

All commands require the `lesslag.admin` permission.

| Command | Description |
| :--- | :--- |
| `/lg status` | Displays the current status (Enabled/Disabled) of all LessLag modules. |
| `/lg health` | Shows a comprehensive server health report including TPS, RAM usage, CPU load, and entity counts. |
| `/lg tps` | Displays real-time TPS and MSPT metrics with 1m, 5m, and 15m averages. |
| `/lg sources` | Runs the Lag Source Analyzer to detect specific entities, chunks, or plugins causing lag. |
| `/lg gc` | Displays detailed Garbage Collection statistics and memory usage info. |
| `/lg check <world>` | Checks a specific world for entity and chunk counts. |
| `/lg kill <type> [radius]` | Removes entities of a specific type (e.g., `hostile`, `all`, `items`) within a radius. |
| `/lg unload` | Manually triggers the unloading of unused chunks. |
| `/lg reload` | Reloads the `config.yml` and `messages.yml` files. |
| `/lg help` | Displays the list of available commands. |

## Permissions

*   `lesslag.admin`: Grants full access to all LessLag commands and notifications.
*   `lesslag.notify`: Allows receiving automatic lag alerts and performance warnings.

## Configuration

The plugin is highly configurable. For a detailed explanation of every option, please refer to the [Configuration Documentation](CONFIGURATION.md).
