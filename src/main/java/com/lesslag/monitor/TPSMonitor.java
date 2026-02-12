package com.lesslag.monitor;

import com.lesslag.LessLag;
import com.lesslag.action.ActionExecutor;
import com.lesslag.action.ThresholdConfig;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.LinkedList;
import java.util.List;

public class TPSMonitor {

    private final LessLag plugin;
    private final ActionExecutor actionExecutor;
    private final LagSourceAnalyzer lagSourceAnalyzer;
    private final PredictiveOptimizer predictiveOptimizer;

    private BukkitTask tickTask;
    private BukkitTask monitorTask;

    // TPS calculation (volatile for cross-thread visibility)
    private volatile double currentTPS = 20.0;
    private final double[] tpsHistory = new double[20];
    private int historyIndex = 0;

    // Multi-window TPS averages
    private final LinkedList<Double> tps5s = new LinkedList<>();
    private final LinkedList<Double> tps10s = new LinkedList<>();
    private final LinkedList<Double> tps1m = new LinkedList<>();
    private final LinkedList<Double> tps5m = new LinkedList<>();
    private final LinkedList<Double> tps15m = new LinkedList<>();

    // MSPT tracking
    private volatile double currentMSPT = 50.0;
    private volatile double minMSPT = 50.0;
    private volatile double maxMSPT = 50.0;
    private final LinkedList<Double> msptHistory = new LinkedList<>();

    // Dynamic thresholds
    private volatile List<ThresholdConfig> thresholds;
    private volatile ThresholdConfig activeThreshold = null;

    // Alert state
    private int consecutiveLowCount = 0;
    private long lastNotifyTime = 0;

    // Recovery state
    private int consecutiveGoodCount = 0;
    private volatile boolean settingsModified = false;

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
        // Tick counter — SYNC (must measure actual server ticks)
        tickTask = new BukkitRunnable() {
            private int tickCount = 0;
            private long lastMeasureTime = System.nanoTime();
            private long lastTickNano = System.nanoTime();

            @Override
            public void run() {
                long now = System.nanoTime();
                double tickMs = (now - lastTickNano) / 1_000_000.0;
                lastTickNano = now;

                synchronized (msptHistory) {
                    msptHistory.addLast(tickMs);
                    if (msptHistory.size() > 100)
                        msptHistory.removeFirst();
                }

                tickCount++;
                long elapsedNano = now - lastMeasureTime;

                if (elapsedNano >= 1_000_000_000L) {
                    double elapsedSeconds = elapsedNano / 1_000_000_000.0;
                    double measuredTPS = Math.min(20.0, tickCount / elapsedSeconds);

                    tpsHistory[historyIndex] = measuredTPS;
                    historyIndex = (historyIndex + 1) % tpsHistory.length;

                    double sum = 0;
                    for (double tps : tpsHistory)
                        sum += tps;
                    currentTPS = sum / tpsHistory.length;

                    addToWindow(tps5s, measuredTPS, 5);
                    addToWindow(tps10s, measuredTPS, 10);
                    addToWindow(tps1m, measuredTPS, 60);
                    addToWindow(tps5m, measuredTPS, 300);
                    addToWindow(tps15m, measuredTPS, 900);

                    calculateMSPT();

                    // Feed predictive optimizer with current avg MSPT
                    if (predictiveOptimizer != null) {
                        predictiveOptimizer.feed(currentMSPT);
                    }

                    tickCount = 0;
                    lastMeasureTime = now;
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);

        // Monitor task — runs ASYNC, dispatches actions to main thread
        int checkInterval = plugin.getConfig().getInt("system.tps-monitor.check-interval", 100) / 20; // Convert ticks
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
        monitorTask = new BukkitRunnable() {
            @Override
            public void run() {
                checkTPS(); // This runs on async thread
            }
        }.runTaskTimerAsynchronously(plugin, 100L, checkIntervalTicks);

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

    private void addToWindow(LinkedList<Double> window, double value, int maxSize) {
        window.addLast(value);
        while (window.size() > maxSize)
            window.removeFirst();
    }

    private double averageOf(LinkedList<Double> list) {
        if (list.isEmpty())
            return 20.0;
        double sum = 0;
        for (double v : list)
            sum += v;
        return sum / list.size();
    }

    private void calculateMSPT() {
        synchronized (msptHistory) {
            if (msptHistory.isEmpty())
                return;
            double min = Double.MAX_VALUE, max = 0, sum = 0;
            for (double ms : msptHistory) {
                if (ms < min)
                    min = ms;
                if (ms > max)
                    max = ms;
                sum += ms;
            }
            minMSPT = min;
            maxMSPT = max;
            currentMSPT = sum / msptHistory.size();
        }
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
                if (activeThreshold == null || getSeverityIndex(detected) > getSeverityIndex(activeThreshold)) {
                    activeThreshold = detected;
                    // Dispatch actions to MAIN THREAD
                    final ThresholdConfig toTrigger = detected;
                    Bukkit.getScheduler().runTask(plugin, () -> triggerActions(toTrigger));
                }
                // Send notifications async (safe — Adventure API is thread-safe)
                sendNotifications(detected);

                // Run lag source analysis if enabled
                if (plugin.getConfig().getDouble("system.lag-source-analyzer.auto-analyze-tps", 15.0) >= currentTPS) { // Logic
                                                                                                                       // change:
                                                                                                                       // check
                                                                                                                       // against
                                                                                                                       // TPS
                    // Wait, original was `lag-analysis.report-on-alert`.
                    // My new config has `system.lag-source-analyzer.auto-analyze-tps: 15.0`.
                    // So if currentTPS <= 15.0 (or whatever configured), trigger.
                    triggerLagAnalysis();
                }
            }
        } else {
            consecutiveLowCount = 0;
            checkRecovery();
        }
    }

    private int getSeverityIndex(ThresholdConfig threshold) {
        return thresholds.indexOf(threshold);
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
            if (sources.isEmpty())
                return;

            List<String> report = lagSourceAnalyzer.formatCompactReport(sources);
            if (report.isEmpty())
                return;

            // Send lag source report to admins (async-safe with Adventure)
            Bukkit.getScheduler().runTask(plugin, () -> {
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
        }).exceptionally(e -> {
            plugin.getLogger().warning("Lag analysis failed: " + e.getMessage());
            return null;
        });
    }

    /**
     * Send notifications — runs on ASYNC thread.
     */
    private void sendNotifications(ThresholdConfig threshold) {
        int cooldown = plugin.getConfig().getInt("notifications.cooldown", 10);
        long now = System.currentTimeMillis();
        if (now - lastNotifyTime < cooldown * 1000L)
            return;
        lastNotifyTime = now;

        String message = threshold.getMessage()
                .replace("{tps}", String.format("%.1f", currentTPS));
        String fullMessage = plugin.getPrefix() + message;
        // Dispatch player interaction to main thread
        Bukkit.getScheduler().runTask(plugin, () -> {
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
        int checkInterval = config.getInt("monitor.check-interval", 5);
        int neededChecks = delaySeconds / checkInterval;

        if (currentTPS >= recoveryThreshold) {
            consecutiveGoodCount++;
            if (consecutiveGoodCount >= neededChecks) {
                // Dispatch recovery to MAIN THREAD
                Bukkit.getScheduler().runTask(plugin, () -> {
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
        return averageOf(tps5s);
    }

    public double getTPS10s() {
        return averageOf(tps10s);
    }

    public double getTPS1m() {
        return averageOf(tps1m);
    }

    public double getTPS5m() {
        return averageOf(tps5m);
    }

    public double getTPS15m() {
        return averageOf(tps15m);
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
}
