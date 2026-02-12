package com.lesslag.monitor;

import com.lesslag.LessLag;
import com.lesslag.util.NotificationHelper;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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

    // Track previous GC stats to detect new collections (Timer thread only)
    private final Map<String, Long> lastCounts = new HashMap<>();
    private final Map<String, Long> lastTimes = new HashMap<>();

    // Cumulative stats
    private final AtomicLong totalCollections = new AtomicLong(0);
    private final AtomicLong totalTimeMs = new AtomicLong(0);

    // GC overhead tracking (Rolling 60s window)
    private final LinkedList<Long> gcTimeHistory = new LinkedList<>();
    private volatile double gcOverheadPercent = 0;
    private static final int ROLLING_WINDOW_SAMPLES = 30; // 30 samples * 2s = 60s

    // Per-collector stats — ConcurrentHashMap for cross-thread safety
    private final Map<String, CollectorStats> collectorStats = new ConcurrentHashMap<>();

    // JMX Bean Cache
    private List<GarbageCollectorMXBean> cachedGCs = Collections.emptyList();

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

        // Cache GC beans
        cachedGCs = ManagementFactory.getGarbageCollectorMXBeans();

        // Initialize with current GC stats
        for (GarbageCollectorMXBean gc : cachedGCs) {
            lastCounts.put(gc.getName(), gc.getCollectionCount());
            lastTimes.put(gc.getName(), gc.getCollectionTime());
            collectorStats.put(gc.getName(), new CollectorStats());
        }

        synchronized (gcTimeHistory) {
            gcTimeHistory.clear();
        }

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
        List<GarbageCollectorMXBean> gcs = cachedGCs;
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

                totalCollections.addAndGet(newCollections);
                totalTimeMs.addAndGet(newTimeMs);
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

        // Update rolling overhead calculation
        synchronized (gcTimeHistory) {
            gcTimeHistory.addLast(totalNewGcTime);
            if (gcTimeHistory.size() > ROLLING_WINDOW_SAMPLES) {
                gcTimeHistory.removeFirst();
            }

            long sum = 0;
            for (long val : gcTimeHistory) {
                sum += val;
            }

            // Calculate overhead %: (GC Time / Wall Time) * 100
            // Wall Time = samples * 2000ms
            long windowSizeMs = gcTimeHistory.size() * 2000L;
            if (windowSizeMs > 0) {
                gcOverheadPercent = (sum * 100.0) / windowSizeMs;
            } else {
                gcOverheadPercent = 0;
            }
        }
    }

    // ── Getters for health report ────────────────────────────

    public long getTotalCollections() {
        return totalCollections.get();
    }

    public long getTotalTimeMs() {
        return totalTimeMs.get();
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
        for (GarbageCollectorMXBean gc : cachedGCs) {
            if (sb.length() > 0)
                sb.append("\n");

            CollectorStats stats = collectorStats.get(gc.getName());
            long collections = stats != null ? stats.totalCollections : gc.getCollectionCount();
            long timeMs = stats != null ? stats.totalTimeMs : gc.getCollectionTime();
            sb.append("  &8▸ &f").append(gc.getName())
                    .append(" &8| &7Collections: &e").append(collections)
                    .append(" &8| &7Time: &e").append(timeMs).append("ms");

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
        volatile long totalCollections = 0;
        volatile long totalTimeMs = 0;
        volatile double worstPauseMs = 0;
    }
}
