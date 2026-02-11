package com.lesslag.monitor;

import com.lesslag.LessLag;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.*;

/**
 * Memory Leak Detector — Tracks post-GC memory baselines to detect leaks.
 *
 * How it works:
 * 1. Periodically samples the Old Gen memory pool AFTER GC.
 * (Uses MemoryPoolMXBean.getCollectionUsage() which reports memory
 * right after the last GC — the true "baseline" usage.)
 * 2. Maintains a sliding window of post-GC baselines.
 * 3. If the baseline steadily RISES over multiple samples → memory leak.
 * "Steadily rises" = linear regression slope is positive and exceeds
 * a configurable threshold (MB/min).
 * 4. Also tracks GC frequency, per-player memory ratio, and heap pool details.
 *
 * DEFAULT: Warning/notify only. No automatic actions are taken because
 * memory behavior is highly variable and false positives are likely.
 */
public class MemoryLeakDetector {

    private final LessLag plugin;
    private Timer timer;

    // Post-GC baseline history (MB values)
    private final LinkedList<PostGCSample> baselineHistory = new LinkedList<>();

    // GC frequency tracking
    private long lastGCCount = 0;
    private long lastGCCheckTime = 0;
    private volatile double gcFrequency = 0; // GC/minute

    // Leak detection state
    private volatile boolean leakSuspected = false;
    private volatile double lastSlope = 0; // MB/min
    private volatile double lastPostGCBaseline = 0; // MB
    private volatile int consecutiveLeakSignals = 0;
    private volatile long lastAlertTime = 0;

    // Heap pool info cache
    private volatile Map<String, PoolInfo> heapPools = Collections.emptyMap();

    public MemoryLeakDetector(LessLag plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("memory-leak-detector.enabled", true))
            return;

        int intervalSeconds = plugin.getConfig().getInt("memory-leak-detector.check-interval", 30);

        // Initialize GC count
        lastGCCount = getTotalGCCount();
        lastGCCheckTime = System.currentTimeMillis();

        // Use Timer (not Bukkit scheduler) since we read JMX data
        timer = new Timer("LessLag-MemLeakDetector", true);
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    sample();
                } catch (Exception e) {
                    plugin.getLogger().warning("[MemLeakDetector] Error during sample: " + e.getMessage());
                }
            }
        }, 10000, intervalSeconds * 1000L);

        plugin.getLogger().info("Memory Leak Detector started (interval: " + intervalSeconds + "s)");
    }

    public void stop() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    /**
     * Take a sample: record post-GC baseline, update GC frequency, check for leak.
     */
    private void sample() {
        int windowSize = plugin.getConfig().getInt("memory-leak-detector.window-size", 20);
        double slopeThreshold = plugin.getConfig().getDouble("memory-leak-detector.slope-threshold-mb-per-min", 5.0);
        int minSamples = plugin.getConfig().getInt("memory-leak-detector.min-samples", 8);
        int alertCooldownSec = plugin.getConfig().getInt("memory-leak-detector.alert-cooldown", 300);
        boolean notify = plugin.getConfig().getBoolean("memory-leak-detector.notify", true);

        // ── 1. Record post-GC baseline ──
        double postGCMB = getPostGCBaselineMB();
        if (postGCMB < 0) {
            // Fallback: use current used memory if collection usage unavailable
            Runtime rt = Runtime.getRuntime();
            postGCMB = (double) (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        }
        lastPostGCBaseline = postGCMB;

        long now = System.currentTimeMillis();
        synchronized (baselineHistory) {
            baselineHistory.addLast(new PostGCSample(now, postGCMB));
            while (baselineHistory.size() > windowSize) {
                baselineHistory.removeFirst();
            }
        }

        // ── 2. Update GC frequency ──
        long currentGCCount = getTotalGCCount();
        long elapsedMs = now - lastGCCheckTime;
        if (elapsedMs > 0) {
            long newGCs = currentGCCount - lastGCCount;
            gcFrequency = (newGCs * 60000.0) / elapsedMs; // GC per minute
        }
        lastGCCount = currentGCCount;
        lastGCCheckTime = now;

        // ── 3. Update heap pool info ──
        updateHeapPools();

        // ── 4. Check for leak (need enough samples) ──
        List<PostGCSample> samples;
        synchronized (baselineHistory) {
            samples = new ArrayList<>(baselineHistory);
        }

        if (samples.size() < minSamples)
            return;

        // Linear regression on post-GC baselines
        double slope = calculateSlope(samples); // MB per millisecond
        double slopeMBPerMin = slope * 60000.0;
        lastSlope = slopeMBPerMin;

        if (slopeMBPerMin > slopeThreshold) {
            consecutiveLeakSignals++;
            leakSuspected = true;

            // Only alert if we have sustained signal AND cooldown passed
            if (consecutiveLeakSignals >= 3 && notify) {
                if (now - lastAlertTime > alertCooldownSec * 1000L) {
                    lastAlertTime = now;
                    alertLeakDetected(slopeMBPerMin, postGCMB, samples);
                }
            }
        } else {
            if (consecutiveLeakSignals > 0) {
                consecutiveLeakSignals--;
            }
            if (consecutiveLeakSignals == 0) {
                leakSuspected = false;
            }
        }
    }

    /**
     * Get post-GC baseline from Old Gen pool.
     * Uses getCollectionUsage() which reports memory AFTER the last GC.
     */
    private double getPostGCBaselineMB() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != java.lang.management.MemoryType.HEAP)
                continue;
            String name = pool.getName().toLowerCase();

            // Old Gen / Tenured — this is where leaks manifest
            if (name.contains("old") || name.contains("tenured")) {
                MemoryUsage collectionUsage = pool.getCollectionUsage();
                if (collectionUsage != null && collectionUsage.getUsed() > 0) {
                    return (double) collectionUsage.getUsed() / (1024 * 1024);
                }
            }
        }
        return -1;
    }

    /**
     * Update heap pool snapshot for display.
     */
    private void updateHeapPools() {
        Map<String, PoolInfo> pools = new LinkedHashMap<>();
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != java.lang.management.MemoryType.HEAP)
                continue;
            MemoryUsage usage = pool.getUsage();
            MemoryUsage collUsage = pool.getCollectionUsage();

            long usedMB = usage.getUsed() / (1024 * 1024);
            long maxMB = usage.getMax() > 0 ? usage.getMax() / (1024 * 1024) : -1;
            long postGCMB = collUsage != null && collUsage.getUsed() > 0
                    ? collUsage.getUsed() / (1024 * 1024)
                    : -1;

            pools.put(pool.getName(), new PoolInfo(pool.getName(), usedMB, maxMB, postGCMB));
        }
        heapPools = Collections.unmodifiableMap(pools);
    }

    /**
     * Simple linear regression: returns slope in MB/ms.
     */
    private double calculateSlope(List<PostGCSample> samples) {
        int n = samples.size();
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;

        // Use relative time (ms from first sample)
        long baseTime = samples.get(0).timestamp;

        for (PostGCSample s : samples) {
            double x = s.timestamp - baseTime;
            double y = s.baselineMB;
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
        }

        double denom = n * sumX2 - sumX * sumX;
        if (denom == 0)
            return 0;
        return (n * sumXY - sumX * sumY) / denom;
    }

    private long getTotalGCCount() {
        long total = 0;
        for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc.getCollectionCount() > 0)
                total += gc.getCollectionCount();
        }
        return total;
    }

    /**
     * Alert admins about suspected memory leak.
     */
    private void alertLeakDetected(double slopeMBPerMin, double currentBaseline, List<PostGCSample> samples) {
        double firstBaseline = samples.get(0).baselineMB;
        long timeSpanMin = (samples.get(samples.size() - 1).timestamp - samples.get(0).timestamp) / 60000;

        Runtime rt = Runtime.getRuntime();
        long maxMB = rt.maxMemory() / (1024 * 1024);
        double pctUsed = (currentBaseline / maxMB) * 100;

        String severity;
        if (pctUsed > 85) {
            severity = "&4&l⚠ CRITICAL";
        } else if (pctUsed > 70) {
            severity = "&c⚠ WARNING";
        } else {
            severity = "&e⚠ NOTICE";
        }

        final String msg = severity + " &7[MemLeak] Post-GC memory rising steadily!"
                + "\n&7  Slope: &c+" + String.format("%.1f", slopeMBPerMin) + " MB/min"
                + "\n&7  Baseline: &f" + String.format("%.0f", firstBaseline) + "MB &7→ &c"
                + String.format("%.0f", currentBaseline) + "MB"
                + " &8(over " + timeSpanMin + " min)"
                + "\n&7  Old Gen: &f" + String.format("%.0f", currentBaseline) + "MB &8/ &f" + maxMB + "MB"
                + " &8(" + String.format("%.0f", pctUsed) + "%)"
                + "\n&7  GC Rate: &e" + String.format("%.1f", gcFrequency) + " &7/min"
                + "\n&8  Possible memory leak in a plugin or world data.";

        plugin.getLogger().warning("[MemLeakDetector] Suspected memory leak!"
                + " Post-GC baseline rising at +" + String.format("%.1f", slopeMBPerMin) + " MB/min"
                + " (current: " + String.format("%.0f", currentBaseline) + "MB)");

        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("lesslag.notify")) {
                    LessLag.sendMessage(player, plugin.getPrefix() + msg);
                }
            }
        });
    }

    // ── Getters ──────────────────────────────────────

    public boolean isLeakSuspected() {
        return leakSuspected;
    }

    public double getLastSlope() {
        return lastSlope;
    }

    public double getLastPostGCBaseline() {
        return lastPostGCBaseline;
    }

    public double getGcFrequency() {
        return gcFrequency;
    }

    public int getConsecutiveLeakSignals() {
        return consecutiveLeakSignals;
    }

    public Map<String, PoolInfo> getHeapPools() {
        return heapPools;
    }

    public List<PostGCSample> getBaselineHistory() {
        synchronized (baselineHistory) {
            return new ArrayList<>(baselineHistory);
        }
    }

    /**
     * Get per-player memory usage in MB.
     */
    public double getPerPlayerMemoryMB() {
        int players = Bukkit.getOnlinePlayers().size();
        if (players == 0)
            return 0;
        Runtime rt = Runtime.getRuntime();
        double usedMB = (double) (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        return usedMB / players;
    }

    // ── Data Classes ──────────────────────────────────

    public static class PostGCSample {
        public final long timestamp;
        public final double baselineMB;

        public PostGCSample(long timestamp, double baselineMB) {
            this.timestamp = timestamp;
            this.baselineMB = baselineMB;
        }
    }

    public static class PoolInfo {
        public final String name;
        public final long usedMB;
        public final long maxMB;
        public final long postGCMB;

        public PoolInfo(String name, long usedMB, long maxMB, long postGCMB) {
            this.name = name;
            this.usedMB = usedMB;
            this.maxMB = maxMB;
            this.postGCMB = postGCMB;
        }
    }
}
