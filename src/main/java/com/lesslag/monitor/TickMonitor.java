package com.lesslag.monitor;

import com.lesslag.LessLag;
import com.lesslag.util.NotificationHelper;
import com.lesslag.util.SchedulerAdapter;

/**
 * Monitors individual tick durations and reports spikes.
 * Tick measurement is sync, notifications are dispatched async.
 */
public class TickMonitor {

    private final LessLag plugin;

    // Config (cached)
    private double thresholdMs;
    private boolean notifyEnabled;
    private volatile boolean enabled = false;

    private volatile double lastTickMs;

    // Stats (volatile for cross-thread reads)
    private volatile long spikeCount = 0;
    private volatile double worstTickMs = 0;

    private long lastNotifyTime = 0;
    private static final long NOTIFY_COOLDOWN_MS = 1000;

    public TickMonitor(LessLag plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    private void loadConfig() {
        thresholdMs = plugin.getConfig().getDouble("system.tick-monitor.threshold-ms", 100);
        notifyEnabled = plugin.getConfig().getBoolean("system.tick-monitor.notify", true); // I didn't add this key,
                                                                                           // assuming default true or
                                                                                           // using "log-to-console"
                                                                                           // from new config?
        // New config has `log-to-console`. It doesn't have `notify`.
        // I should probably map `notify` to `log-to-console` OR add `notify` to config.
        // Let's check `config.yml` content again.
        // system.tick-monitor: enabled, threshold-ms, log-to-console.
        // I will use `log-to-console` for `notifyEnabled` for now, or just default to
        // true.
        // Actually `notifyAdminsAsync` sends to chat. `log-to-console` sends to
        // console.
        // I'll leave `notify` as a hidden config or map it to `log-to-console` for now
        // to be safe.
        // Or better, I will use `log-to-console` as the key for the field
        // `notifyEnabled` but rename the field if I could.
        // For minimal code change, I'll just map `notify` to
        // `system.tick-monitor.log-to-console` AND assume it means notify admins too?
        // No, `log-to-console` implies console.
        // I'll stick to `notify` key even if not in my `config.yml` snippet (which
        // means it uses default).
        // Wait, I should probably stick to what I wrote in `config.yml`.
        // `log-to-console: true`.
        // I will change `notifyEnabled` to use `log-to-console` for this variable.
        notifyEnabled = plugin.getConfig().getBoolean("system.tick-monitor.log-to-console", true);
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("system.tick-monitor.enabled", true))
            return;
        enabled = true;
        // No own per-tick task — TPSMonitor calls tick(double) from its single tick lambda
        plugin.getLogger().info("Tick Monitor started (threshold: " + thresholdMs + "ms, piggybacked on TPSMonitor)");
    }

    public void stop() {
        enabled = false;
    }

    /**
     * Called from TPSMonitor's per-tick lambda with the already-computed tick duration.
     * Eliminates a separate runGlobalRepeating(1L, 1L) task.
     */
    public void tick(double tickMs) {
        if (!enabled) return;
        lastTickMs = tickMs;

        if (tickMs > worstTickMs) {
            worstTickMs = tickMs;
        }

        if (tickMs > thresholdMs) {
            spikeCount++;

            if (notifyEnabled) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastNotifyTime >= NOTIFY_COOLDOWN_MS) {
                    lastNotifyTime = currentTime;
                    final double duration = tickMs;
                    // Send notification ASYNC to avoid blocking the main thread
                    SchedulerAdapter.runAsync(plugin, () -> notifySpike(duration));
                }
            }
        }
    }

    /**
     * Send spike notification — runs ASYNC.
     * Dispatches player messages to main thread for Bukkit API safety.
     */
    private void notifySpike(double durationMs) {
        String message = plugin.getConfig().getString("messages.tick-spike",
                "<yellow>⚠ Tick spike: <white>{duration}ms <gray>(normal: 50ms)")
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
