# LessLag

LessLag is a comprehensive server performance guardian for Minecraft, designed to automatically monitor, diagnose, and mitigate lag sources. It provides a robust suite of tools for optimizing entity management, redstone activity, AI processing, and chunk loading, ensuring a stable and responsive gameplay experience.

## Overview

Unlike basic clear-lag plugins that simply remove entities on a timer, LessLag employs intelligent monitoring and adaptive optimization strategies. It actively analyzes server performance (TPS, MSPT, GC activity) and dynamically adjusts its operations to maintain optimal performance levels without impacting legitimate player activities.

The plugin operates on a modular architecture, allowing server administrators to enable or disable specific features based on their needs.

## Key Features

### Redstone Control
The Redstone Control module prevents lag machines and optimizes redstone circuits by monitoring activation frequencies.

*   **Adaptive Monitoring**: Intelligently skips intensive checks when the server TPS is high, conserving resources.
*   **Clock Detection**: Identifies and mitigates rapid redstone clocks that can degrade server performance.
*   **Piston Limiter**: Restricts the number of piston pushes per chunk per tick to prevent piston-based lag machines.
*   **Suppression**: Temporarily disables redstone in chunks that exceed activation thresholds, preventing server crashes.

### Entity Management
This module provides granular control over entity counts to prevent world overload.

*   **Global & Per-Chunk Limits**: Enforces strict limits on different entity categories (monsters, animals, ambient, etc.) per chunk and per world.
*   **Smart Chunk Limiter**: Periodically scans loaded chunks to remove excess entities while preserving important ones (e.g., named mobs, villagers, tamed animals).
*   **Protection**: Includes safeguards for named mobs, NPCs, and specific metadata to prevent accidental removal of important entities.

### AI Optimization (Frustum Culling)
The AI Optimization module significantly reduces the processing overhead of mob AI.

*   **Frustum Culling**: Disables the AI of mobs that are outside the player's field of view or behind blocks, reducing unnecessary calculations.
*   **Distance-Based Disabling**: Automatically disables AI for mobs beyond a configured distance from players.
*   **Selective Activation**: Ensures mobs reactivate instantly when they come into view or are interacted with.

### Farm & Villager Optimization
Targeted optimizations for player farms and trading halls.

*   **Villager Optimizer**: Disables the AI of villagers trapped in trading cells (1x1 spaces) to eliminate pathfinding overhead while keeping them interactive for trading.
*   **Breeding Limiter**: Prevents animal breeding if the chunk limit for that species is reached, preventing exponential farm growth.
*   **Density Optimizer**: Selectively disables AI and collisions for mobs in high-density areas (e.g., cow crushers) without removing them.

### Chunk Management
Manages chunk loading and unloading to reduce memory usage.

*   **Dynamic View/Simulation Distance**: Automatically adjusts the server's view and simulation distances based on performance metrics.
*   **World Guard**: Monitors loaded chunks per player and unloads unused chunks in overloaded worlds to free up memory.
*   **Spawn Chunk Control**: Options to keep spawn chunks loaded or unload them to save resources.

### Monitoring & Diagnostics
Provides real-time insights into server health.

*   **TPS & MSPT Monitoring**: Tracks Ticks Per Second and Milliseconds Per Tick to detect performance trends.
*   **Lag Source Analyzer**: Identifies specific entities, chunks, or redstone contraptions causing performance drops.
*   **Garbage Collection Monitor**: Alerts administrators to frequent or long garbage collection pauses, indicating memory issues.
*   **Memory Leak Detection**: Monitors memory usage trends to warn about potential memory leaks.

### Automation
Proactive features to maintain server stability.

*   **Predictive Optimization**: Analyzes performance trends (e.g., rising MSPT) to trigger optimizations before lag becomes noticeable.
*   **Threshold-Based Actions**: Configurable reaction tiers (Minor, Moderate, Critical) that trigger specific commands or optimizations when TPS drops below defined levels.

## Installation

1.  Download the latest `LessLag.jar`.
2.  Place the jar file into your server's `plugins` folder.
3.  Restart the server.
4.  Configure the plugin in `plugins/LessLag/config.yml`.

## Commands

All commands require the `lesslag.admin` permission.

*   `/lg status`: Displays the current status of all LessLag modules.
*   `/lg health`: Shows a comprehensive health report of the server (TPS, RAM, CPU, etc.).
*   `/lg tps`: Displays current TPS and recent history.
*   `/lg gc`: Forces a garbage collection (use with caution).
*   `/lg sources`: Runs the lag source analyzer to find performance bottlenecks.
*   `/lg help`: vital command list.

## Configuration

For a detailed explanation of all configuration options, please refer to the [CONFIGURATION.md](CONFIGURATION.md) file.
