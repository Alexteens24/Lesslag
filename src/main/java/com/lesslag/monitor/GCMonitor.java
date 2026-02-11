package com.lesslag.monitor;

import com.lesslag.LessLag;
import com.lesslag.util.NotificationHelper;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Monitors garbage collection events and reports significant pauses.
 *
 * Enhancements over v1:
 * - Tracks GC overhead % (time spent in GC vs wall clock time)
 * - Per-collector breakdown stats
 * - Config cached in fields
 */
public class GCMonitor {

    private final LessLag plugin;
    private Timer timer;

    // Config (cached)
    private long minDuration;
    private boolean notifyEnabled;

    // Track previous GC stats to detect new collections
    private final Map<String, Long> lastCounts = new HashMap<>();
    private final Map<String, Long> lastTimes = new HashMap<>();

    // Cumulative stats
    private long totalCollections = 0;
    private long totalTimeMs = 0;

    // GC overhead tracking
    private long overheadWindowStartTime = 0;
    private long overheadWindowGcTime = 0;
    private volatile double gcOverheadPercent = 0;
    private static final long OVERHEAD_WINDOW_MS = 60_000; // 60s rolling window

    // Per-collector stats
    private final Map<String, CollectorStats> collectorStats = new HashMap<>();

    public GCMonitor(LessLag plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    private void loadConfig() {
        minDuration = plugin.getConfig().getLong("gc-monitor.min-duration-ms", 50);
        notifyEnabled = plugin.getConfig().getBoolean("gc-monitor.notify", true);
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("gc-monitor.enabled", true))
            return;

        // Initialize with current GC stats
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            lastCounts.put(gc.getName(), gc.getCollectionCount());
            lastTimes.put(gc.getName(), gc.getCollectionTime());
            collectorStats.put(gc.getName(), new CollectorStats());
        }

        overheadWindowStartTime = System.currentTimeMillis();
        overheadWindowGcTime = 0;

        // Use a Timer (not Bukkit scheduler) since GC can freeze the server
        timer = new Timer("LessLag-GCMonitor", true);
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                checkGC();
            }
        }, 2000, 2000); // Check every 2 seconds

        plugin.getLogger().info("GC Monitor started (overhead tracking enabled)");
    }

    public void stop() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    private void checkGC() {
        List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
        long totalNewGcTime = 0;

        for (GarbageCollectorMXBean gc : gcs) {
            String name = gc.getName();
            long currentCount = gc.getCollectionCount();
            long currentTime = gc.getCollectionTime();

            Long prevCount = lastCounts.getOrDefault(name, 0L);
            Long prevTime = lastTimes.getOrDefault(name, 0L);

            if (currentCount > prevCount) {
                long newCollections = currentCount - prevCount;
                long newTimeMs = currentTime - prevTime;

                totalCollections += newCollections;
                totalTimeMs += newTimeMs;
                totalNewGcTime += newTimeMs;

                // Update per-collector stats
                CollectorStats stats = collectorStats.computeIfAbsent(name, k -> new CollectorStats());
                stats.totalCollections += newCollections;
                stats.totalTimeMs += newTimeMs;

                double avgPauseMs = (double) newTimeMs / newCollections;
                if (avgPauseMs > stats.worstPauseMs) {
                    stats.worstPauseMs = avgPauseMs;
                }

                if (avgPauseMs >= minDuration && notifyEnabled) {
                    String type = name.toLowerCase().contains("old") || name.toLowerCase().contains("major")
                            ? "Major"
                            : "Minor";
                    final String msg = "&e⚠ GC " + type + " &8(" + name + ")&e: " +
                            newCollections + " collection(s), " +
                            String.format("%.0f", avgPauseMs) + "ms avg pause";

                    NotificationHelper.notifyAdminsAsync(msg);
                }
            }

            lastCounts.put(name, currentCount);
            lastTimes.put(name, currentTime);
        }

        // Update overhead calculation
        overheadWindowGcTime += totalNewGcTime;
        long now = System.currentTimeMillis();
        long windowElapsed = now - overheadWindowStartTime;

        if (windowElapsed >= OVERHEAD_WINDOW_MS) {
            // Calculate overhead % for the window and reset
            gcOverheadPercent = (windowElapsed > 0)
                    ? (overheadWindowGcTime * 100.0) / windowElapsed
                    : 0;
            overheadWindowStartTime = now;
            overheadWindowGcTime = 0;
        }
    }

    // ── Getters for health report ────────────────────────────

    public long getTotalCollections() {
        return totalCollections;
    }

    public long getTotalTimeMs() {
        return totalTimeMs;
    }

    /**
     * Get GC overhead as a percentage of wall-clock time (rolling 60s window).
     * Example: 5.2 means 5.2% of the last minute was spent in GC.
     */
    public double getGCOverheadPercent() {
        return gcOverheadPercent;
    }

    /**
     * Get a formatted summary of all GC collectors with enhanced stats.
     */
    public String getGCSummary() {
        StringBuilder sb = new StringBuilder();
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (sb.length() > 0)
                sb.append("\n");

            CollectorStats stats = collectorStats.get(gc.getName());
            sb.append("  &8▸ &f").append(gc.getName())
                    .append(" &8| &7Collections: &e").append(gc.getCollectionCount())
                    .append(" &8| &7Time: &e").append(gc.getCollectionTime()).append("ms");

            if (stats != null && stats.worstPauseMs > 0) {
                sb.append(" &8| &7Worst: &c").append(String.format("%.0f", stats.worstPauseMs)).append("ms");
            }
        }

        // Append overhead line
        sb.append("\n  &8▸ &7GC Overhead: ");
        if (gcOverheadPercent > 10) {
            sb.append("&c");
        } else if (gcOverheadPercent > 5) {
            sb.append("&e");
        } else {
            sb.append("&a");
        }
        sb.append(String.format("%.1f%%", gcOverheadPercent)).append(" &7(60s window)");

        return sb.toString();
    }

    /**
     * Per-collector statistics.
     */
    private static class CollectorStats {
        long totalCollections = 0;
        long totalTimeMs = 0;
        double worstPauseMs = 0;
    }
}
