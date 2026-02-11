package com.lesslag.monitor;

import com.lesslag.LessLag;
import com.lesslag.util.NotificationHelper;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

/**
 * Monitors individual tick durations and reports spikes.
 * Tick measurement is sync, notifications are dispatched async.
 */
public class TickMonitor {

    private final LessLag plugin;
    private BukkitTask task;

    // Config (cached)
    private double thresholdMs;
    private boolean notifyEnabled;

    private long lastTickNano;
    private volatile double lastTickMs;

    // Stats (volatile for cross-thread reads)
    private volatile long spikeCount = 0;
    private volatile double worstTickMs = 0;

    public TickMonitor(LessLag plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    private void loadConfig() {
        thresholdMs = plugin.getConfig().getDouble("tick-monitor.threshold-ms", 100);
        notifyEnabled = plugin.getConfig().getBoolean("tick-monitor.notify", true);
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("tick-monitor.enabled", true))
            return;

        lastTickNano = System.nanoTime();

        // Tick measurement MUST be sync (we're measuring actual tick duration)
        task = new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.nanoTime();
                lastTickMs = (now - lastTickNano) / 1_000_000.0;
                lastTickNano = now;

                if (lastTickMs > worstTickMs) {
                    worstTickMs = lastTickMs;
                }

                if (lastTickMs > thresholdMs) {
                    spikeCount++;

                    if (notifyEnabled) {
                        final double duration = lastTickMs;
                        // Send notification ASYNC to avoid blocking the main thread
                        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> notifySpike(duration));
                    }
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);

        plugin.getLogger().info("Tick Monitor started (threshold: " + thresholdMs + "ms, async notifications)");
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /**
     * Send spike notification — runs ASYNC.
     * Dispatches player messages to main thread for Bukkit API safety.
     */
    private void notifySpike(double durationMs) {
        String message = plugin.getConfig().getString("messages.tick-spike",
                "&e⚠ Tick spike: &f{duration}ms &7(normal: 50ms)")
                .replace("{duration}", String.format("%.1f", durationMs));
        // Dispatch to main thread via NotificationHelper
        NotificationHelper.notifyAdminsAsync(message);
    }

    // ── Getters (volatile-safe) ────────────────────────────

    public long getSpikeCount() {
        return spikeCount;
    }

    public double getWorstTickMs() {
        return worstTickMs;
    }

    public double getLastTickMs() {
        return lastTickMs;
    }

    public void resetStats() {
        spikeCount = 0;
        worstTickMs = 0;
    }
}
