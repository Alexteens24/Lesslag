# LessLag

**LessLag** is a lightweight, modular stabilization plugin for Minecraft servers. It prevents lag machines, optimizes mob AI, manages entities, and provides detailed performance diagnostics.

## Features

### 🛡️ Protection Modules
*   **Redstone Suppressor**: Automatically detects and freezes chunk-crashing lag machines and rapid clocks.
*   **Entity Limiter**: Smart per-chunk and per-world limits for different entity types.
*   **Smart Chunk Guard**: Prevents world overload by unloading unused chunks and dynamically reducing view distance.

### 🚀 Optimization
*   **AI Culling**: Disables AI for mobs that are far away or behind players (Frustum Culling).
*   **Predictive Optimization**: Analyzes MSPT trends to apply fixes *before* TPS drops.

### 📊 Diagnostics
*   **Lag Source Analyzer**: Identifies exactly *what* is causing lag (Entities? Redstone? Physics?).
*   **Spark-like Reports**: `/lg health` gives you a breakdown of TPS, CPU, Memory, Disk, and Entity density.
*   **Heap & GC Monitoring**: Detects memory leaks and garbage collection spikes.

## Installation

1.  Download `LessLag.jar`.
2.  Place it in your server's `plugins` folder.
3.  Restart the server.
4.  Edit `config.yml` to tune settings (see [Configuration Guide](CONFIGURATION.md)).

## Commands

*   `/lg status`: Quick server status overview.
*   `/lg health`: Detailed performance report.
*   `/lg sources`: Analyze lag sources.
*   `/lg tps`: View TPS history.
*   `/lg clear [items|mobs|all]`: Manually clear entities.
*   `/lg reload`: Reload configuration.

## Configuration

LessLag is highly configurable. You can enable/disable entire modules to fit your server's needs. 
See the **[Configuration Guide](CONFIGURATION.md)** for detailed documentation.

## Compatibility

LessLag automatically detects and adjusts for:
*   **Pufferfish/DAB**: Defers AI optimization to the server core.
*   **ClearLag**: Avoids conflicting entity clearing tasks.
*   **MobFarmManager**: Adjusts chunk limits to allow farm management.
