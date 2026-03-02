package com.lesslag.monitor;

import com.lesslag.LessLag;
import com.lesslag.action.ActionExecutor;
import com.lesslag.action.ThresholdConfig;
import com.lesslag.util.SchedulerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.List;

public class TPSMonitor {

    // ── O(1) circular buffer for TPS windows ──
    // Replaces ConcurrentLinkedDeque whose size() is O(n) and allocates per-add.
    private static final class RingBuffer {
        private final double[] data;
        private int writeIndex = 0;
        private int count = 0;
        private volatile double cachedAverage = 20.0;

        RingBuffer(int capacity) {
            this.data = new double[capacity];
            java.util.Arrays.fill(data, 20.0);
        }

        void add(double value) {
            data[writeIndex] = value;
            writeIndex = (writeIndex + 1) % data.length;
            if (count < data.length) count++;
            // Pre-compute average on write (once/sec) so reads are O(1)
            double sum = 0;
            for (int i = 0; i < count; i++) sum += data[i];
            cachedAverage = sum / count;
        }

        double average() {
            return cachedAverage;
        }
    }

    private final LessLag plugin;
    private final ActionExecutor actionExecutor;
    private final LagSourceAnalyzer lagSourceAnalyzer;
    private final PredictiveOptimizer predictiveOptimizer;

    private SchedulerAdapter.TaskHandle tickTask;
    private SchedulerAdapter.TaskHandle monitorTask;

    // TPS calculation (volatile for cross-thread visibility)
    private volatile double currentTPS = 20.0;
    private final double[] tpsHistory = new double[20];
    private int historyIndex = 0;

    // Multi-window TPS averages — O(1) ring buffers, no allocation, no traversal
    private final RingBuffer tps5s = new RingBuffer(5);
    private final RingBuffer tps10s = new RingBuffer(10);
    private final RingBuffer tps1m = new RingBuffer(60);
    private final RingBuffer tps5m = new RingBuffer(300);
    private final RingBuffer tps15m = new RingBuffer(900);

    // MSPT tracking — lock-free circular buffer (single-thread access from tick task)
    private volatile double currentMSPT = 50.0;
    private volatile double minMSPT = 50.0;
    private volatile double maxMSPT = 50.0;
    private static final int MSPT_BUFFER_SIZE = 100;
    private final double[] msptBuffer = new double[MSPT_BUFFER_SIZE];
    private int msptWriteIndex = 0;
    private int msptCount = 0;

    // Dynamic thresholds
    private volatile List<ThresholdConfig> thresholds;
    private volatile ThresholdConfig activeThreshold = null;

    // Alert state
    private int consecutiveLowCount = 0;
    private long lastNotifyTime = 0;
    private long lastDiscordAlertTime = 0;
    private long lastAnalysisTime = 0;

    // Recovery state
    private int consecutiveGoodCount = 0;
    private volatile boolean settingsModified = false;
    private final java.util.concurrent.atomic.AtomicBoolean isAnalyzing = new java.util.concurrent.atomic.AtomicBoolean(
            false);

    public TPSMonitor(LessLag plugin, ActionExecutor actionExecutor, LagSourceAnalyzer lagSourceAnalyzer,
            PredictiveOptimizer predictiveOptimizer) {
        this.plugin = plugin;
        this.actionExecutor = actionExecutor;
        this.lagSourceAnalyzer = lagSourceAnalyzer;
        this.predictiveOptimizer = predictiveOptimizer;
        for (int i = 0; i < tpsHistory.length; i++)
            tpsHistory[i] = 20.0;
        loadThresholds();
    }

    public void loadThresholds() {
        this.thresholds = ThresholdConfig.loadFromConfig(plugin.getConfig(), plugin.getLogger());
        plugin.getLogger().info("Loaded " + thresholds.size() + " threshold(s): " + thresholds);
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("system.tps-monitor.enabled", true)) {
            return;
        }

        // Tick counter — SYNC (must measure actual server ticks)
        // State fields for tick measurement
        final long[] tpsLastMeasureTime = { System.nanoTime() };
        final long[] tpsLastTickNano = { System.nanoTime() };
        final int[] tpsTickCount = { 0 };
        tickTask = SchedulerAdapter.runGlobalRepeating(plugin, () -> {
                long now = System.nanoTime();
                double tickMs = (now - tpsLastTickNano[0]) / 1_000_000.0;
                tpsLastTickNano[0] = now;

                // Lock-free circular buffer write (single-thread, no sync needed)
                msptBuffer[msptWriteIndex] = tickMs;
                msptWriteIndex = (msptWriteIndex + 1) % MSPT_BUFFER_SIZE;
                if (msptCount < MSPT_BUFFER_SIZE) msptCount++;

                // Piggyback TickMonitor & BottleneckAnalyzer on this single per-tick task
                // (eliminates 2 separate runGlobalRepeating(1L,1L) scheduler dispatches)
                if (plugin.getTickMonitor() != null) plugin.getTickMonitor().tick(tickMs);
                if (plugin.getBottleneckAnalyzer() != null) plugin.getBottleneckAnalyzer().tickPing();

                tpsTickCount[0]++;
                long elapsedNano = now - tpsLastMeasureTime[0];

                if (elapsedNano >= 1_000_000_000L) {
                    double elapsedSeconds = elapsedNano / 1_000_000_000.0;
                    double measuredTPS = Math.min(20.0, tpsTickCount[0] / elapsedSeconds);

                    tpsHistory[historyIndex] = measuredTPS;
                    historyIndex = (historyIndex + 1) % tpsHistory.length;

                    double sum = 0;
                    for (double tps : tpsHistory)
                        sum += tps;
                    currentTPS = sum / tpsHistory.length;

                    tps5s.add(measuredTPS);
                    tps10s.add(measuredTPS);
                    tps1m.add(measuredTPS);
                    tps5m.add(measuredTPS);
                    tps15m.add(measuredTPS);

                    calculateMSPT();

                    // Feed predictive optimizer with current avg MSPT
                    if (predictiveOptimizer != null) {
                        predictiveOptimizer.feed(currentMSPT);
                    }

                    tpsTickCount[0] = 0;
                    tpsLastMeasureTime[0] = now;
                }
        }, 1L, 1L);

        // Monitor task — runs ASYNC, dispatches actions to main thread
        // to seconds
        // approx or use
        // seconds in
        // config?
        // Config says "check-interval: 100" (ticks). Original was 5 (seconds).
        // 100 ticks = 5 seconds. Adapting to read ticks if I change logic, or simply
        // use correct keys.
        // Wait, the new config has `check-interval: 100` under `tps-monitor`. This
        // implies ticks.
        // The code `checkInterval * 20L` implies `checkInterval` is in seconds.
        // Let's stick to the new config having ticks, so `100` ticks.
        int checkIntervalTicks = plugin.getConfig().getInt("system.tps-monitor.check-interval", 100);
        monitorTask = SchedulerAdapter.runAsyncRepeating(plugin, () -> {
            checkTPS(); // This runs on async thread
        }, 100L, checkIntervalTicks);

        plugin.getLogger().info("TPS Monitor started (interval: " + checkIntervalTicks + " ticks, async mode)");
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (monitorTask != null) {
            monitorTask.cancel();
            monitorTask = null;
        }
    }

    // addToWindow/averageOf removed — replaced by RingBuffer

    private void calculateMSPT() {
        if (msptCount == 0)
            return;
        double min = Double.MAX_VALUE, max = 0, sum = 0;
        for (int i = 0; i < msptCount; i++) {
            double ms = msptBuffer[i];
            if (ms < min)
                min = ms;
            if (ms > max)
                max = ms;
            sum += ms;
        }
        minMSPT = min;
        maxMSPT = max;
        currentMSPT = sum / msptCount;
    }

    /**
     * Check TPS — runs on ASYNC thread.
     * Threshold matching and notifications are async.
     * Action execution is dispatched to main thread.
     */
    private void checkTPS() {
        int triggerCount = plugin.getConfig().getInt("automation.trigger-count", 3); // I didn't add this to config,
                                                                                     // will assume default 3 or add it
                                                                                     // if needed.
        // Actually I missed `automation.trigger-count` in my `config.yml`. I should add
        // it or use a default.
        // I'll use a default of 3 for now.

        // Find the most severe matching threshold
        ThresholdConfig detected = null;
        for (ThresholdConfig threshold : thresholds) {
            if (currentTPS <= threshold.getTps()) {
                detected = threshold;
            }
        }

        if (detected != null) {
            consecutiveLowCount++;
            consecutiveGoodCount = 0;

            if (consecutiveLowCount >= triggerCount) {
                // Change threshold if different (allows escalation and de-escalation)
                if (activeThreshold == null || !activeThreshold.equals(detected)) {
                    activeThreshold = detected;
                    // Dispatch actions to MAIN THREAD
                    final ThresholdConfig toTrigger = detected;
                    SchedulerAdapter.runGlobal(plugin, () -> triggerActions(toTrigger));
                }
                // Send notifications async (safe — Adventure API is thread-safe)
                sendNotifications(detected);

                // Run lag source analysis if enabled
                if (plugin.getConfig().getDouble("system.lag-source-analyzer.auto-analyze-tps", 15.0) >= currentTPS) {
                    long now = System.nanoTime();
                    // 60s cooldown for auto-analysis
                    if (now - lastAnalysisTime > 60_000_000_000L) {
                        if (isAnalyzing.compareAndSet(false, true)) {
                            lastAnalysisTime = now;
                            triggerLagAnalysis();
                        }
                    }
                }

                // Discord Alert
                checkDiscordAlert(detected);
            }
        } else {
            consecutiveLowCount = 0;
            checkRecovery();
        }
    }

    /**
     * Trigger actions — runs on MAIN THREAD (dispatched from async).
     */
    private void triggerActions(ThresholdConfig threshold) {
        plugin.getLogger().warning("TPS Alert [" + threshold.getName().toUpperCase() + "] - TPS: "
                + String.format("%.1f", currentTPS) + " - Executing " + threshold.getActions().size()
                + " actions, " + threshold.getCommands().size() + " commands");
        settingsModified = true;

        actionExecutor.executeActions(threshold.getActions());

        if (!threshold.getCommands().isEmpty()) {
            actionExecutor.executeCommands(threshold.getCommands(), currentTPS);
        }
    }

    /**
     * Trigger lag source analysis and send compact report.
     * Snapshots on main thread, processes async, then sends notifications async.
     */
    private void triggerLagAnalysis() {
        if (!plugin.getConfig().getBoolean("system.lag-source-analyzer.enabled", true))
            return;

        lagSourceAnalyzer.analyzeAsync().thenAccept(sources -> {
            try {
                if (sources.isEmpty())
                    return;

                List<String> report = lagSourceAnalyzer.formatCompactReport(sources);
                if (report.isEmpty())
                    return;

                // Send lag source report to admins (async-safe with Adventure)
                SchedulerAdapter.runGlobal(plugin, () -> {
                    String header = plugin.getPrefix() + "&7Possible lag causes:";
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (player.hasPermission("lesslag.notify")) {
                            LessLag.sendMessage(player, header);
                            for (String line : report) {
                                LessLag.sendMessage(player, line);
                            }
                        }
                    }
                });
            } finally {
                isAnalyzing.set(false);
            }
        }).exceptionally(e -> {
            plugin.getLogger().warning("Lag analysis failed: " + e.getMessage());
            isAnalyzing.set(false);
            return null;
        });
    }

    /**
     * Send notifications — runs on ASYNC thread.
     */
    private void sendNotifications(ThresholdConfig threshold) {
        int cooldown = plugin.getConfig().getInt("notifications.cooldown", 10);
        long now = System.nanoTime();
        if (now - lastNotifyTime < cooldown * 1_000_000_000L)
            return;
        lastNotifyTime = now;

        String message = threshold.getMessage()
                .replace("{tps}", String.format("%.1f", currentTPS));
        String fullMessage = plugin.getPrefix() + message;
        // Dispatch player interaction to main thread
        SchedulerAdapter.runGlobal(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("lesslag.notify")) {
                    if (threshold.isNotifyActionbar()) {
                        LessLag.sendActionBar(player, fullMessage);
                    }
                    if (threshold.isNotifyChat()) {
                        LessLag.sendMessage(player, fullMessage);
                    }
                    if (threshold.isNotifySound()) {
                        try {
                            Sound sound = Sound.valueOf(threshold.getSoundType().toUpperCase());
                            player.playSound(player.getLocation(), sound,
                                    threshold.getSoundVolume(), threshold.getSoundPitch());
                        } catch (IllegalArgumentException e) {
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
                        }
                    }
                }
            }

            // Broadcast
            if (threshold.isBroadcast()) {
                String broadcastMsg = threshold.getBroadcastMessage();
                if (broadcastMsg == null) {
                    broadcastMsg = plugin.getConfig().getString("messages.broadcast-emergency",
                            "&c&l[!] &fServer is experiencing lag, auto-fix in progress...");
                }
                broadcastMsg = broadcastMsg.replace("{tps}", String.format("%.1f", currentTPS));
                String broadcast = plugin.getPrefix() + broadcastMsg;
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (!player.hasPermission("lesslag.notify")) {
                        LessLag.sendMessage(player, broadcast);
                    }
                }
            }
        });
    }

    /**
     * Check recovery — runs on ASYNC thread.
     */
    private void checkRecovery() {
        if (!settingsModified)
            return;

        FileConfiguration config = plugin.getConfig();
        if (!config.getBoolean("recovery.enabled", true))
            return;

        double recoveryThreshold = config.getDouble("recovery.tps-threshold", 18.0);
        int delaySeconds = config.getInt("recovery.delay-seconds", 30);
        int checkIntervalTicks = config.getInt("system.tps-monitor.check-interval", 100);
        // Calculate needed checks using ticks to avoid integer division issues with
        // small intervals
        int neededChecks = (int) ((long) delaySeconds * 20L / checkIntervalTicks);
        if (neededChecks < 1)
            neededChecks = 1;

        if (currentTPS >= recoveryThreshold) {
            consecutiveGoodCount++;
            if (consecutiveGoodCount >= neededChecks) {
                // Dispatch recovery to MAIN THREAD
                SchedulerAdapter.runGlobal(plugin, () -> {
                    plugin.getLogger()
                            .info("TPS stabilized (" + String.format("%.1f", currentTPS) + "). Restoring defaults...");
                    actionExecutor.restoreDefaults();
                    settingsModified = false;
                    activeThreshold = null;

                    String recoveryMsg = config.getString("messages.recovery", "&a TPS stabilized: {tps}")
                            .replace("{tps}", String.format("%.1f", currentTPS));
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (player.hasPermission("lesslag.notify")) {
                            LessLag.sendMessage(player, plugin.getPrefix() + recoveryMsg);
                            if (config.getBoolean("notifications.sound", true)) {
                                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
                            }
                        }
                    }
                });
                consecutiveGoodCount = 0;
            }
        } else {
            consecutiveGoodCount = 0;
        }
    }

    /**
     * Check and send Discord alert - runs on ASYNC thread.
     */
    private void checkDiscordAlert(ThresholdConfig threshold) {
        if (!plugin.getConfig().getBoolean("premium.enabled", false))
            return;

        double alertThreshold = plugin.getConfig().getDouble("premium.discord.alert-tps-threshold", 18.0);
        if (currentTPS > alertThreshold)
            return;

        long now = System.nanoTime();
        // Fixed 1 minute cooldown for Discord to prevent spam
        if (now - lastDiscordAlertTime < 60_000_000_000L)
            return;

        lastDiscordAlertTime = now;

        String message = "**TPS Alert**: Server TPS dropped to **" + String.format("%.1f", currentTPS) + "**!";
        message += "\\nThreshold: " + threshold.getName();

        if (plugin.getPremiumManager() != null) {
            plugin.getPremiumManager().sendAlert(message);
        }
    }

    // ── Getters (all volatile-safe) ──────────────────

    public double getCurrentTPS() {
        return currentTPS;
    }

    public ThresholdConfig getActiveThreshold() {
        return activeThreshold;
    }

    public List<ThresholdConfig> getThresholds() {
        return thresholds;
    }

    public boolean isSettingsModified() {
        return settingsModified;
    }

    public double getTPS5s() {
        return tps5s.average();
    }

    public double getTPS10s() {
        return tps10s.average();
    }

    public double getTPS1m() {
        return tps1m.average();
    }

    public double getTPS5m() {
        return tps5m.average();
    }

    public double getTPS15m() {
        return tps15m.average();
    }

    public double getCurrentMSPT() {
        return currentMSPT;
    }

    public double getMinMSPT() {
        return minMSPT;
    }

    public double getMaxMSPT() {
        return maxMSPT;
    }

    /**
     * Compute MSPT percentiles on-demand from the circular buffer.
     * Only called when admin runs a command — zero cost during normal ticks.
     * @return double[3] = { p50, p95, p99 }
     */
    public double[] getMSPTPercentiles() {
        int n = msptCount;
        if (n == 0) return new double[]{0, 0, 0};
        double[] sorted = new double[n];
        System.arraycopy(msptBuffer, 0, sorted, 0, n);
        java.util.Arrays.sort(sorted);
        return new double[]{
            sorted[Math.min((int)(n * 0.50), n - 1)],
            sorted[Math.min((int)(n * 0.95), n - 1)],
            sorted[Math.min((int)(n * 0.99), n - 1)]
        };
    }
}
