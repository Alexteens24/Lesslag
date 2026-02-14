package com.lesslag.monitor;

import com.lesslag.LessLag;
import org.bukkit.Bukkit;

import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;

/**
 * Analyzes and identifies lag sources on the server.
 * Inspired by Spark's diagnostic approach.
 *
 * Detects:
 * - Entity hotspots (which entity types and worlds have the most entities)
 * - Entity density hotspots (50+ entities concentrated in a single chunk)
 * - Chunk load pressure (excessive loaded chunks per world)
 * - Chunk load/unload rates (exploration lag detection)
 * - Plugin task load (plugins with too many scheduled tasks)
 *
 * All analysis runs async, only reading data via sync snapshots.
 */
public class LagSourceAnalyzer {

    private final LessLag plugin;

    // Cached results (updated periodically)
    private volatile List<LagSource> lastAnalysis = Collections.emptyList();
    private volatile long lastAnalysisTime = 0;

    // Chunk load rate tracking — stores previous chunk counts per world
    private final Map<String, Integer> previousChunkCounts = new ConcurrentHashMap<>();
    private volatile long lastChunkSnapshotTime = 0;

    public LagSourceAnalyzer(LessLag plugin) {
        this.plugin = plugin;
    }

    /**
     * Run a full lag source analysis. Takes a snapshot incrementally on main
     * thread,
     * then processes it async. Returns results via CompletableFuture.
     */
    public CompletableFuture<List<LagSource>> analyzeAsync() {
        return analyzeFullAsync().thenApply(result -> result.sources);
    }

    /**
     * Get cached analysis results (non-blocking).
     */
    public List<LagSource> getCachedAnalysis() {
        return lastAnalysis;
    }

    public long getLastAnalysisTime() {
        return lastAnalysisTime;
    }

    // ══════════════════════════════════════════════════
    // Snapshot (runs on main thread)
    // ══════════════════════════════════════════════════

    private TaskSnapshot[] takeTaskSnapshot() {
        // Disabled by "CTO" optimization - getPendingTasks() is too heavy for main
        // thread.
        // Returning empty array to effectively disable this specific metric without
        // breaking API.
        return new TaskSnapshot[0];
    }

    private AnalysisConfig takeConfigSnapshot() {
        return new AnalysisConfig(
                plugin.getConfig().getInt("system.lag-source-analyzer.thresholds.entity-count", 500),
                plugin.getConfig().getInt("system.lag-source-analyzer.thresholds.chunk-count", 800),
                plugin.getConfig().getInt("system.lag-source-analyzer.thresholds.task-count", 20),
                plugin.getConfig().getInt("system.lag-source-analyzer.thresholds.top-entities", 3),
                plugin.getConfig().getInt("system.lag-source-analyzer.thresholds.density", 50),
                plugin.getConfig().getDouble("system.lag-source-analyzer.thresholds.chunk-rate-warn", 10.0));
    }

    // ══════════════════════════════════════════════════
    // Processing (runs async)
    // ══════════════════════════════════════════════════

    private List<LagSource> processSnapshots(WorldSnapshot[] worlds, TaskSnapshot[] tasks, long snapshotTime,
            AnalysisConfig config) {
        List<LagSource> sources = new ArrayList<>();
        int entityWarning = config.entityWarning;
        int chunkWarning = config.chunkWarning;
        int taskWarning = config.taskWarning;
        int topN = config.topN;
        int densityThreshold = config.densityThreshold;
        double chunkRateWarning = config.chunkRateWarning;

        // Entity analysis per world
        for (WorldSnapshot world : worlds) {
            // Flag worlds with too many entities
            if (world.totalEntities > entityWarning) {
                sources.add(new LagSource(LagSource.Type.ENTITY_OVERLOAD,
                        "&c" + world.totalEntities + " entities in " + world.name,
                        world.totalEntities));
            }

            // Top entity types in this world
            List<Map.Entry<String, Integer>> top = world.entityCounts.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(topN)
                    .collect(Collectors.toList());

            for (Map.Entry<String, Integer> entry : top) {
                if (entry.getValue() > 50) { // Only report significant counts
                    sources.add(new LagSource(LagSource.Type.ENTITY_TYPE,
                            "&e" + entry.getValue() + " " + entry.getKey() + " &7in &f" + world.name,
                            entry.getValue()));
                }
            }

            // Entity density hotspots — chunks with 50+ entities
            int hotspotCount = 0;
            int worstDensity = 0;
            for (Map.Entry<Long, Integer> chunk : world.chunkEntityCounts.entrySet()) {
                if (chunk.getValue() >= densityThreshold) {
                    hotspotCount++;
                    if (chunk.getValue() > worstDensity) {
                        worstDensity = chunk.getValue();
                    }
                }
            }
            if (hotspotCount > 0) {
                sources.add(new LagSource(LagSource.Type.ENTITY_DENSITY,
                        "&c" + hotspotCount + " chunk(s) with " + densityThreshold
                                + "+ entities in " + world.name
                                + " &7(worst: &c" + worstDensity + "&7)",
                        worstDensity));
            }
        }

        // Chunk analysis — total count
        for (WorldSnapshot world : worlds) {
            if (world.loadedChunks > chunkWarning) {
                sources.add(new LagSource(LagSource.Type.CHUNK_OVERLOAD,
                        "&c" + String.format("%,d", world.loadedChunks) + " loaded chunks in " + world.name,
                        world.loadedChunks));
            }
        }

        // Chunk load rate analysis — detect exploration lag
        if (lastChunkSnapshotTime > 0) {
            double elapsedSeconds = (snapshotTime - lastChunkSnapshotTime) / 1000.0;
            if (elapsedSeconds > 0) {
                for (WorldSnapshot world : worlds) {
                    int prevChunks = previousChunkCounts.getOrDefault(world.name, world.loadedChunks);
                    int delta = world.loadedChunks - prevChunks;
                    double chunksPerSecond = delta / elapsedSeconds;

                    if (chunksPerSecond > chunkRateWarning) {
                        sources.add(new LagSource(LagSource.Type.CHUNK_RATE,
                                "&c" + String.format("%.1f", chunksPerSecond)
                                        + " chunks/sec loading in " + world.name
                                        + " &7(exploration lag)",
                                (int) chunksPerSecond));
                    }
                }
            }
        }

        // Plugin task analysis
        for (TaskSnapshot task : tasks) {
            if (task.count > taskWarning) {
                sources.add(new LagSource(LagSource.Type.PLUGIN_TASKS,
                        "&e" + task.pluginName + " &7has &c" + task.count + " &7active tasks",
                        task.count));
            }
        }

        // Sort by severity (highest count first)
        sources.sort((a, b) -> Integer.compare(b.count, a.count));

        return sources;
    }

    /**
     * Format analysis results as chat-friendly lines (grouped by type).
     */
    public List<String> formatReport(List<LagSource> sources) {
        List<String> lines = new ArrayList<>();

        if (sources.isEmpty()) {
            lines.add("  &a✔ No significant lag sources detected.");
            return lines;
        }

        // Group by type
        Map<LagSource.Type, List<LagSource>> grouped = sources.stream()
                .collect(Collectors.groupingBy(s -> s.type));

        // Entities section
        boolean hasEntitySection = grouped.containsKey(LagSource.Type.ENTITY_OVERLOAD)
                || grouped.containsKey(LagSource.Type.ENTITY_TYPE)
                || grouped.containsKey(LagSource.Type.ENTITY_DENSITY);

        if (hasEntitySection) {
            lines.add("  &e&lTOP ENTITIES");
            if (grouped.containsKey(LagSource.Type.ENTITY_OVERLOAD)) {
                for (LagSource s : grouped.get(LagSource.Type.ENTITY_OVERLOAD)) {
                    lines.add("    &c⚠ " + s.description);
                }
            }
            if (grouped.containsKey(LagSource.Type.ENTITY_TYPE)) {
                for (LagSource s : grouped.get(LagSource.Type.ENTITY_TYPE)) {
                    lines.add("    &8▸ " + s.description);
                }
            }
            if (grouped.containsKey(LagSource.Type.ENTITY_DENSITY)) {
                for (LagSource s : grouped.get(LagSource.Type.ENTITY_DENSITY)) {
                    lines.add("    &c⚠ " + s.description);
                }
            }
        }

        // Chunks section
        boolean hasChunkSection = grouped.containsKey(LagSource.Type.CHUNK_OVERLOAD)
                || grouped.containsKey(LagSource.Type.CHUNK_RATE);

        if (hasChunkSection) {
            lines.add("  &e&lLOADED CHUNKS");
            if (grouped.containsKey(LagSource.Type.CHUNK_OVERLOAD)) {
                for (LagSource s : grouped.get(LagSource.Type.CHUNK_OVERLOAD)) {
                    lines.add("    &c⚠ " + s.description);
                }
            }
            if (grouped.containsKey(LagSource.Type.CHUNK_RATE)) {
                for (LagSource s : grouped.get(LagSource.Type.CHUNK_RATE)) {
                    lines.add("    &c⚠ " + s.description);
                }
            }
        }

        // Plugin tasks section
        if (grouped.containsKey(LagSource.Type.PLUGIN_TASKS)) {
            lines.add("  &e&lPLUGIN TASKS");
            for (LagSource s : grouped.get(LagSource.Type.PLUGIN_TASKS)) {
                lines.add("    &c⚠ " + s.description);
            }
        }

        return lines;
    }

    /**
     * Format a full detailed report for the /lg sources command.
     * Shows ALL worlds and ALL plugin tasks, flagging those over thresholds.
     */
    public List<String> formatFullReport(FullAnalysisResult result) {
        WorldSnapshot[] worldSnapshots = result.worldSnapshots;
        TaskSnapshot[] taskSnapshots = result.taskSnapshots;
        Map<String, Integer> previousChunkCounts = result.previousChunkCounts;
        long lastChunkSnapshotTime = result.lastChunkSnapshotTime;
        long snapshotTime = result.analysisTime;

        List<String> lines = new ArrayList<>();
        int entityWarning = plugin.getConfig().getInt("system.lag-source-analyzer.thresholds.entity-count", 500);
        int chunkWarning = plugin.getConfig().getInt("system.lag-source-analyzer.thresholds.chunk-count", 800);
        int taskWarning = plugin.getConfig().getInt("system.lag-source-analyzer.thresholds.task-count", 20);
        int topN = plugin.getConfig().getInt("system.lag-source-analyzer.thresholds.top-entities", 3);

        // ── TOP ENTITIES ──
        lines.add("  &e&lTOP ENTITIES");
        for (WorldSnapshot world : worldSnapshots) {
            List<Map.Entry<String, Integer>> top = world.entityCounts.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(topN)
                    .collect(Collectors.toList());

            if (top.isEmpty())
                continue;

            StringBuilder sb = new StringBuilder("    &8▸ &f" + world.name + ": ");
            for (int j = 0; j < top.size(); j++) {
                Map.Entry<String, Integer> entry = top.get(j);
                String color = entry.getValue() > 200 ? "&c" : entry.getValue() > 100 ? "&e" : "&a";
                sb.append(color).append(entry.getKey()).append(" &7(").append(entry.getValue()).append(")");
                if (j < top.size() - 1)
                    sb.append("&8, ");
            }
            if (world.totalEntities > entityWarning)
                sb.append(" &c⚠");
            lines.add(sb.toString());
        }

        // Entity density hotspots
        boolean hasDensity = false;
        int densityThreshold = plugin.getConfig().getInt("system.lag-source-analyzer.thresholds.density", 50);
        for (WorldSnapshot world : worldSnapshots) {
            int hotspots = 0;
            int worst = 0;
            for (Map.Entry<Long, Integer> chunk : world.chunkEntityCounts.entrySet()) {
                if (chunk.getValue() >= densityThreshold) {
                    hotspots++;
                    if (chunk.getValue() > worst)
                        worst = chunk.getValue();
                }
            }
            if (hotspots > 0) {
                if (!hasDensity) {
                    lines.add("  &e&lENTITY HOTSPOTS");
                    hasDensity = true;
                }
                lines.add("    &c⚠ &f" + world.name + ": &c" + hotspots + " chunk(s) "
                        + "with " + densityThreshold + "+ entities &7(worst: " + worst + ")");
            }
        }

        // ── LOADED CHUNKS ──
        lines.add("  &e&lLOADED CHUNKS");
        for (WorldSnapshot world : worldSnapshots) {
            String chkColor = world.loadedChunks > chunkWarning ? "&c"
                    : world.loadedChunks > chunkWarning / 2 ? "&e" : "&a";
            String warn = world.loadedChunks > chunkWarning ? " &c⚠" : "";
            lines.add("    &8▸ &f" + world.name + ": " + chkColor
                    + String.format("%,d", world.loadedChunks) + " chunks" + warn);
        }

        // Chunk load rates (if we have previous data)
        if (lastChunkSnapshotTime > 0) {
            long elapsed = snapshotTime - lastChunkSnapshotTime;
            if (elapsed > 0) {
                double elapsedSec = elapsed / 1000.0;
                double rateWarning = plugin.getConfig()
                        .getDouble("system.lag-source-analyzer.thresholds.chunk-rate-warn", 10.0);
                for (WorldSnapshot world : worldSnapshots) {
                    int prev = previousChunkCounts.getOrDefault(world.name, world.loadedChunks);
                    int delta = world.loadedChunks - prev;
                    double rate = delta / elapsedSec;
                    if (rate > rateWarning) {
                        lines.add("    &c⚠ &f" + world.name + ": &c"
                                + String.format("%.1f", rate) + " chunks/sec &7(exploration lag)");
                    }
                }
            }
        }

        // ── PLUGIN TASKS ──
        lines.add("  &e&lPLUGIN TASKS");
        // Sort tasks by count descending
        Arrays.sort(taskSnapshots, (a, b) -> Integer.compare(b.count, a.count));
        int shown = 0;
        for (TaskSnapshot task : taskSnapshots) {
            if (shown >= 10)
                break;
            String color = task.count > taskWarning ? "&c"
                    : task.count > taskWarning / 2 ? "&e" : "&a";
            String warn = task.count > taskWarning ? " &c⚠" : "";
            lines.add("    &8▸ &f" + task.pluginName + ": " + color + task.count + " tasks" + warn);
            shown++;
        }
        if (taskSnapshots.length > 10) {
            lines.add("    &8  ... and " + (taskSnapshots.length - 10) + " more plugins");
        }

        return lines;
    }

    /**
     * Format a compact summary for threshold alert messages (max 3 lines).
     */
    public List<String> formatCompactReport(List<LagSource> sources) {
        List<String> lines = new ArrayList<>();
        if (sources.isEmpty())
            return lines;

        int shown = 0;
        for (LagSource source : sources) {
            if (shown >= 3)
                break;
            lines.add("  &8→ " + source.description);
            shown++;
        }

        if (sources.size() > 3) {
            lines.add("  &8  ... and " + (sources.size() - 3) + " more issues");
        }

        return lines;
    }

    /**
     * Analyze and return full detail including raw snapshots.
     * Used by /lg sources for the detailed report format.
     */
    public CompletableFuture<FullAnalysisResult> analyzeFullAsync() {
        CompletableFuture<FullAnalysisResult> future = new CompletableFuture<>();

        Bukkit.getScheduler().runTask(plugin, () -> {
            new IncrementalSnapshotBuilder(plugin, (worldSnaps) -> {
                try {
                    TaskSnapshot[] taskSnaps = takeTaskSnapshot();
                    AnalysisConfig config = takeConfigSnapshot();
                    long snapshotTime = System.currentTimeMillis();

                    if (plugin.getAsyncExecutor() == null || plugin.getAsyncExecutor().isShutdown()) {
                        future.completeExceptionally(new IllegalStateException("Async executor unavailable"));
                        return;
                    }

                    try {
                        plugin.getAsyncExecutor().execute(() -> {
                            try {
                                // Capture previous state before updating
                                Map<String, Integer> oldChunkCounts = new HashMap<>(previousChunkCounts);
                                long oldTime = lastChunkSnapshotTime;

                                List<LagSource> results = processSnapshots(worldSnaps, taskSnaps, snapshotTime, config);
                                lastAnalysis = results;
                                lastAnalysisTime = snapshotTime;

                                // Update chunk tracking
                                for (WorldSnapshot ws : worldSnaps) {
                                    previousChunkCounts.put(ws.name, ws.loadedChunks);
                                }
                                lastChunkSnapshotTime = snapshotTime;

                                future.complete(new FullAnalysisResult(results, worldSnaps, taskSnaps, oldChunkCounts,
                                        oldTime, snapshotTime));
                            } catch (Exception e) {
                                future.completeExceptionally(e);
                            }
                        });
                    } catch (RejectedExecutionException e) {
                        future.completeExceptionally(e);
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    }
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            }).start();
        });

        return future;
    }

    // ══════════════════════════════════════════════════
    // Data Classes
    // ══════════════════════════════════════════════════

    public static class LagSource {
        public enum Type {
            ENTITY_OVERLOAD,
            ENTITY_TYPE,
            ENTITY_DENSITY,
            CHUNK_OVERLOAD,
            CHUNK_RATE,
            PLUGIN_TASKS
        }

        public final Type type;
        public final String description;
        public final int count;

        public LagSource(Type type, String description, int count) {
            this.type = type;
            this.description = description;
            this.count = count;
        }
    }

    /**
     * Holds both analysis results and raw snapshots for detailed reporting.
     */
    public static class FullAnalysisResult {
        public final List<LagSource> sources;
        public final WorldSnapshot[] worldSnapshots;
        public final TaskSnapshot[] taskSnapshots;
        public final Map<String, Integer> previousChunkCounts;
        public final long lastChunkSnapshotTime;
        public final long analysisTime;

        FullAnalysisResult(List<LagSource> sources, WorldSnapshot[] worldSnapshots,
                TaskSnapshot[] taskSnapshots, Map<String, Integer> previousChunkCounts,
                long lastChunkSnapshotTime, long analysisTime) {
            this.sources = sources;
            this.worldSnapshots = worldSnapshots;
            this.taskSnapshots = taskSnapshots;
            this.previousChunkCounts = previousChunkCounts;
            this.lastChunkSnapshotTime = lastChunkSnapshotTime;
            this.analysisTime = analysisTime;
        }
    }

    // Package-visible for formatFullReport access
    static class WorldSnapshot {
        final String name;
        final int totalEntities;
        final int loadedChunks;
        final Map<String, Integer> entityCounts;
        final Map<Long, Integer> chunkEntityCounts;

        WorldSnapshot(String name, int totalEntities, int loadedChunks,
                Map<String, Integer> entityCounts, Map<Long, Integer> chunkEntityCounts) {
            this.name = name;
            this.totalEntities = totalEntities;
            this.loadedChunks = loadedChunks;
            this.entityCounts = entityCounts;
            this.chunkEntityCounts = chunkEntityCounts;
        }
    }

    static class TaskSnapshot {
        final String pluginName;
        final int count;

        TaskSnapshot(String pluginName, int count) {
            this.pluginName = pluginName;
            this.count = count;
        }
    }

    static class AnalysisConfig {
        final int entityWarning;
        final int chunkWarning;
        final int taskWarning;
        final int topN;
        final int densityThreshold;
        final double chunkRateWarning;

        AnalysisConfig(int entityWarning, int chunkWarning, int taskWarning, int topN, int densityThreshold,
                double chunkRateWarning) {
            this.entityWarning = entityWarning;
            this.chunkWarning = chunkWarning;
            this.taskWarning = taskWarning;
            this.topN = topN;
            this.densityThreshold = densityThreshold;
            this.chunkRateWarning = chunkRateWarning;
        }
    }
}
