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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TPS / MSPT monitor with full Paper and Folia compatibility.
 *
 * <h3>Architecture</h3>
 * 
 * <pre>
 *  Paper path
 *    ┌─ Global tick task (1t, sync) ─── measures real tick-rate, writes msptBuffer (volatile)
 *    └─ Async monitor task           ── reads volatile currentTPS, triggers actions/notifications
 *
 *  Folia path
 *    ┌─ Async repeating task          ── polls server.getTPS()[1] (1-min Paper TPS), same monitor
 *    └─ Async monitor task            ── identical to Paper async path
 * </pre>
 *
 * <h3>Thread safety</h3>
 * <ul>
 * <li>{@code currentTPS}, {@code currentMSPT} etc. are {@code volatile} for
 * cross-thread visibility.</li>
 * <li>Mutable alert-state counters ({@code consecutiveLowCount},
 * {@code consecutiveGoodCount})
 * are {@link AtomicInteger}s — the monitor task may overlap when check-interval
 * &lt; task duration.</li>
 * <li>{@code lastNotifyTime}, {@code lastDiscordAlertTime},
 * {@code lastAnalysisTime} are
 * {@link AtomicLong}s to allow race-free CAS updates.</li>
 * <li>{@code msptBuffer} is written <em>only</em> from the tick task (global
 * thread) and read
 * from both the tick task and async monitor. The write index and count are
 * {@code volatile}
 * so the async reader sees a consistent snapshot (no lock; minor transient
 * inconsistency
 * acceptable for aggregate MSPT stats).</li>
 * <li>All Bukkit API calls that are not thread-safe (player sound, etc.) are
 * dispatched through
 * {@link SchedulerAdapter#runAtEntity} on Folia or
 * {@link SchedulerAdapter#runGlobal} on Paper.</li>
 * </ul>
 */
public class TPSMonitor {

    // ── O(1) circular buffer — pre-computes average on write so reads are O(1) ──
    private static final class RingBuffer {
        private final double[] data;
        private int writeIndex = 0;
        private int count = 0;
        private volatile double cachedAverage = 20.0;

        RingBuffer(int capacity) {
            this.data = new double[capacity];
            java.util.Arrays.fill(data, 20.0);
        }

        /** Called only from the tick/measurement thread. */
        void add(double value) {
            data[writeIndex] = value;
            writeIndex = (writeIndex + 1) % data.length;
            if (count < data.length)
                count++;
            double sum = 0;
            for (int i = 0; i < count; i++)
                sum += data[i];
            cachedAverage = sum / count; // volatile write — visible to async readers
        }

        /** Safe to call from any thread — reads a single volatile double. */
        double average() {
            return cachedAverage;
        }
    }

    // ── Dependencies ──────────────────────────────────────────────────────────
    private final LessLag plugin;
    private final ActionExecutor actionExecutor;
    private final LagSourceAnalyzer lagSourceAnalyzer;
    private final PredictiveOptimizer predictiveOptimizer;

    private SchedulerAdapter.TaskHandle tickTask;
    private SchedulerAdapter.TaskHandle monitorTask;

    // ── TPS ───────────────────────────────────────────────────────────────────
    /** Smoothed TPS — written by tick/measurement thread, read by any. */
    private volatile double currentTPS = 20.0;

    // 20-sample history for the smoothed `currentTPS` value (tick thread only)
    private final double[] tpsHistory = new double[20];
    private int historyIndex = 0;

    // Multi-window O(1) ring buffers
    private final RingBuffer tps5s = new RingBuffer(5);
    private final RingBuffer tps10s = new RingBuffer(10);
    private final RingBuffer tps1m = new RingBuffer(60);
    private final RingBuffer tps5m = new RingBuffer(300);
    private final RingBuffer tps15m = new RingBuffer(900);

    // ── MSPT ─────────────────────────────────────────────────────────────────
    private volatile double currentMSPT = 50.0;
    private volatile double minMSPT = 50.0;
    private volatile double maxMSPT = 50.0;

    private static final int MSPT_BUFFER_SIZE = 100;
    private final double[] msptBuffer = new double[MSPT_BUFFER_SIZE];
    /** Volatile so async readers see the latest commit from the tick thread. */
    private volatile int msptWriteIndex = 0;
    private volatile int msptCount = 0;

    // ── Alert state — atomic for async monitor safety ────────────────────────
    private final AtomicInteger consecutiveLowCount = new AtomicInteger(0);
    private final AtomicInteger consecutiveGoodCount = new AtomicInteger(0);
    private volatile boolean settingsModified = false;
    private final AtomicBoolean isAnalyzing = new AtomicBoolean(false);

    // CAS-friendly timestamps (nanoseconds)
    private final AtomicLong lastNotifyTime = new AtomicLong(0);
    private final AtomicLong lastDiscordAlertTime = new AtomicLong(0);
    private final AtomicLong lastAnalysisTime = new AtomicLong(0);

    // ── Thresholds ────────────────────────────────────────────────────────────
    private volatile List<ThresholdConfig> thresholds;
    private final AtomicReference<ThresholdConfig> activeThreshold = new AtomicReference<>(null);

    // ── Constructor ───────────────────────────────────────────────────────────

    public TPSMonitor(LessLag plugin, ActionExecutor actionExecutor,
            LagSourceAnalyzer lagSourceAnalyzer, PredictiveOptimizer predictiveOptimizer) {
        this.plugin = plugin;
        this.actionExecutor = actionExecutor;
        this.lagSourceAnalyzer = lagSourceAnalyzer;
        this.predictiveOptimizer = predictiveOptimizer;
        java.util.Arrays.fill(tpsHistory, 20.0);
        loadThresholds();
    }

    public void loadThresholds() {
        this.thresholds = ThresholdConfig.loadFromConfig(plugin.getConfig(), plugin.getLogger());
        plugin.getLogger().info("Loaded " + thresholds.size() + " threshold(s): " + thresholds);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void start() {
        if (!plugin.getConfig().getBoolean("system.tps-monitor.enabled", true))
            return;

        if (SchedulerAdapter.isFolia()) {
            startFolia();
        } else {
            startPaper();
        }

        int checkIntervalTicks = plugin.getConfig().getInt("system.tps-monitor.check-interval", 100);
        monitorTask = SchedulerAdapter.runAsyncRepeating(plugin, this::checkTPS, 100L, checkIntervalTicks);

        plugin.getLogger().info("TPS Monitor started ("
                + (SchedulerAdapter.isFolia() ? "Folia" : "Paper")
                + " mode, check-interval: " + checkIntervalTicks + " ticks)");
    }

    /**
     * Paper-only tick task: counts real server ticks at 1t interval to derive
     * true TPS and per-tick MSPT. Must NOT be used on Folia because the global
     * scheduler there drives only the global region — per-region tick rates are
     * independent and cannot be measured this way.
     */
    private void startPaper() {
        final long[] lastMeasureNano = { System.nanoTime() };
        final long[] lastTickNano = { System.nanoTime() };
        final int[] tickCount = { 0 };

        tickTask = SchedulerAdapter.runGlobalRepeating(plugin, () -> {
            long now = System.nanoTime();

            // Measure per-tick wall time for MSPT
            double tickMs = (now - lastTickNano[0]) / 1_000_000.0;
            lastTickNano[0] = now;
            int wi = msptWriteIndex;
            msptBuffer[wi] = tickMs;
            msptWriteIndex = (wi + 1) % MSPT_BUFFER_SIZE;
            if (msptCount < MSPT_BUFFER_SIZE)
                msptCount++;

            // Piggyback on this single 1-tick task to avoid extra scheduler overhead
            if (plugin.getTickMonitor() != null)
                plugin.getTickMonitor().tick(tickMs);
            if (plugin.getBottleneckAnalyzer() != null)
                plugin.getBottleneckAnalyzer().tickPing();

            tickCount[0]++;
            long elapsed = now - lastMeasureNano[0];
            if (elapsed >= 1_000_000_000L) {
                double measuredTPS = Math.min(20.0, tickCount[0] / (elapsed / 1_000_000_000.0));
                commitTPS(measuredTPS);
                tickCount[0] = 0;
                lastMeasureNano[0] = now;
            }
        }, 1L, 1L);
    }

    /**
     * Folia-only measurement: polls Paper's TPS array every second (async).
     * Folia exposes {@code Server#getTPS()} returning a double[] where
     * index 0 = 1-min avg, index 1 = 5-min avg, index 2 = 15-min avg.
     * We use index 0 (1-min) as a representative value and smooth it with
     * our own tpsHistory ring. MSPT on Folia is read from
     * {@code Server#getAverageTickTime()} (milliseconds per tick, global region).
     */
    private void startFolia() {
        tickTask = SchedulerAdapter.runAsyncRepeating(plugin, () -> {
            double[] serverTps = Bukkit.getServer().getTPS();
            double measured = serverTps.length > 0 ? Math.min(20.0, serverTps[0]) : 20.0;
            commitTPS(measured);

            // MSPT — Paper / Folia expose getAverageTickTime() (global region only on
            // Folia)
            double mspt = Bukkit.getServer().getAverageTickTime();
            currentMSPT = mspt;
            minMSPT = Math.min(minMSPT, mspt);
            maxMSPT = Math.max(maxMSPT, mspt);

            if (predictiveOptimizer != null)
                predictiveOptimizer.feed(mspt);
        }, 20L, 20L);
    }

    /** Commit a freshly measured TPS value — updates all ring buffers. */
    private void commitTPS(double measuredTPS) {
        tpsHistory[historyIndex] = measuredTPS;
        historyIndex = (historyIndex + 1) % tpsHistory.length;

        double sum = 0;
        for (double t : tpsHistory)
            sum += t;
        currentTPS = sum / tpsHistory.length; // volatile write

        tps5s.add(measuredTPS);
        tps10s.add(measuredTPS);
        tps1m.add(measuredTPS);
        tps5m.add(measuredTPS);
        tps15m.add(measuredTPS);

        if (!SchedulerAdapter.isFolia()) {
            calculateMSPT();
        }
        if (predictiveOptimizer != null && !SchedulerAdapter.isFolia()) {
            predictiveOptimizer.feed(currentMSPT);
        }
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

    // ── MSPT helpers ─────────────────────────────────────────────────────────

    /** Called from the tick thread only on Paper. */
    private void calculateMSPT() {
        int n = msptCount;
        if (n == 0)
            return;
        double min = Double.MAX_VALUE, max = 0, sum = 0;
        for (int i = 0; i < n; i++) {
            double ms = msptBuffer[i];
            if (ms < min)
                min = ms;
            if (ms > max)
                max = ms;
            sum += ms;
        }
        minMSPT = min;
        maxMSPT = max;
        currentMSPT = sum / n;
    }

    /**
     * Compute MSPT percentiles on-demand from the circular buffer.
     * Only called on admin command — zero overhead during normal operation.
     * 
     * @return double[3] = { p50, p95, p99 }
     */
    public double[] getMSPTPercentiles() {
        int n = msptCount;
        if (n == 0)
            return new double[] { 0, 0, 0 };
        double[] sorted = new double[n];
        System.arraycopy(msptBuffer, 0, sorted, 0, n);
        java.util.Arrays.sort(sorted);
        return new double[] {
                sorted[Math.min((int) (n * 0.50), n - 1)],
                sorted[Math.min((int) (n * 0.95), n - 1)],
                sorted[Math.min((int) (n * 0.99), n - 1)]
        };
    }

    // ── Monitor logic — all called on ASYNC thread ────────────────────────────

    /**
     * Main check loop — runs on an async thread every {@code check-interval} ticks.
     * Reads only volatile fields. All Bukkit API or block/entity access is
     * dispatched to the correct thread via {@link SchedulerAdapter}.
     */
    private void checkTPS() {
        int triggerCount = plugin.getConfig().getInt("automation.trigger-count", 3);

        // Walk thresholds (sorted by TPS desc) — find the most severe match
        ThresholdConfig detected = null;
        double snap = currentTPS; // single volatile read
        for (ThresholdConfig t : thresholds) {
            if (snap <= t.getTps())
                detected = t;
        }

        if (detected != null) {
            consecutiveLowCount.incrementAndGet();
            consecutiveGoodCount.set(0);

            if (consecutiveLowCount.get() >= triggerCount) {
                ThresholdConfig prev = activeThreshold.getAndSet(detected);
                if (prev == null || !prev.equals(detected)) {
                    // Escalation / de-escalation — dispatch to main thread
                    final ThresholdConfig toTrigger = detected;
                    SchedulerAdapter.runGlobal(plugin, () -> triggerActions(toTrigger));
                }
                sendNotifications(detected, snap);
                maybeRunLagAnalysis(snap);
                checkDiscordAlert(detected, snap);
            }
        } else {
            consecutiveLowCount.set(0);
            checkRecovery(snap);
        }
    }

    /** Runs on MAIN / global thread. */
    private void triggerActions(ThresholdConfig threshold) {
        plugin.getLogger().warning("TPS Alert [" + threshold.getName().toUpperCase() + "] TPS: "
                + String.format("%.1f", currentTPS) + " — " + threshold.getActions().size()
                + " action(s), " + threshold.getCommands().size() + " command(s)");
        settingsModified = true;
        actionExecutor.executeActions(threshold.getActions());
        if (!threshold.getCommands().isEmpty()) {
            actionExecutor.executeCommands(threshold.getCommands(), currentTPS);
        }
    }

    /**
     * Send per-player notifications.
     * <p>
     * On Paper: dispatched to global (main) thread — safe to call any Bukkit
     * API.<br>
     * On Folia: each player's sound is dispatched through <em>that player's</em>
     * entity scheduler so it runs on the region that owns the player, avoiding
     * cross-region thread violations.
     */
    private void sendNotifications(ThresholdConfig threshold, double snap) {
        int cooldownSec = plugin.getConfig().getInt("notifications.cooldown", 10);
        long now = System.nanoTime();
        long prev = lastNotifyTime.get();
        if (now - prev < (long) cooldownSec * 1_000_000_000L)
            return;
        if (!lastNotifyTime.compareAndSet(prev, now))
            return; // lost CAS race — another async call won

        String message = threshold.getMessage().replace("{tps}", String.format("%.1f", snap));
        String fullMessage = plugin.getPrefix() + message;

        String broadcastMsg = buildBroadcast(threshold, snap);

        if (SchedulerAdapter.isFolia()) {
            // Folia: dispatch per-player to their owning region thread
            for (Player player : Bukkit.getOnlinePlayers()) {
                final boolean isAdmin = player.hasPermission("lesslag.notify");
                SchedulerAdapter.runAtEntity(plugin, player, () -> {
                    if (!player.isOnline())
                        return;
                    if (isAdmin) {
                        if (threshold.isNotifyActionbar())
                            LessLag.sendActionBar(player, fullMessage);
                        if (threshold.isNotifyChat())
                            LessLag.sendMessage(player, fullMessage);
                        if (threshold.isNotifySound())
                            playSound(player, threshold);
                    } else if (threshold.isBroadcast() && broadcastMsg != null) {
                        LessLag.sendMessage(player, broadcastMsg);
                    }
                });
            }
        } else {
            // Paper: single dispatch to global (main) thread covers all players
            SchedulerAdapter.runGlobal(plugin, () -> {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.hasPermission("lesslag.notify")) {
                        if (threshold.isNotifyActionbar())
                            LessLag.sendActionBar(player, fullMessage);
                        if (threshold.isNotifyChat())
                            LessLag.sendMessage(player, fullMessage);
                        if (threshold.isNotifySound())
                            playSound(player, threshold);
                    } else if (threshold.isBroadcast() && broadcastMsg != null) {
                        LessLag.sendMessage(player, broadcastMsg);
                    }
                }
            });
        }
    }

    private String buildBroadcast(ThresholdConfig threshold, double snap) {
        if (!threshold.isBroadcast())
            return null;
        String msg = threshold.getBroadcastMessage();
        if (msg == null) {
            msg = plugin.getConfig().getString("messages.broadcast-emergency",
                    "<red><bold>[!] <white>Server is experiencing lag, auto-fix in progress...");
        }
        return plugin.getPrefix() + msg.replace("{tps}", String.format("%.1f", snap));
    }

    /**
     * Plays a sound on {@code player} using Paper's self-targeted overload
     * ({@link Player#playSound(Player, Sound, float, float)}) which avoids
     * allocating a {@link org.bukkit.Location} and is safe when called on the
     * player's owning region thread.
     */
    private void playSound(Player player, ThresholdConfig threshold) {
        Sound sound;
        try {
            sound = Sound.valueOf(threshold.getSoundType().toUpperCase());
        } catch (IllegalArgumentException e) {
            sound = Sound.BLOCK_NOTE_BLOCK_PLING;
        }
        // Player-targeted overload: no Location allocation, Folia-correct thread
        // context
        player.playSound(player, sound, threshold.getSoundVolume(), threshold.getSoundPitch());
    }

    private void maybeRunLagAnalysis(double snap) {
        if (!plugin.getConfig().getBoolean("system.lag-source-analyzer.enabled", true))
            return;
        if (plugin.getConfig().getDouble("system.lag-source-analyzer.auto-analyze-tps", 15.0) < snap)
            return;

        long now = System.nanoTime();
        long prev = lastAnalysisTime.get();
        if (now - prev < 60_000_000_000L)
            return;
        if (!lastAnalysisTime.compareAndSet(prev, now))
            return;
        if (!isAnalyzing.compareAndSet(false, true))
            return;

        triggerLagAnalysis();
    }

    private void triggerLagAnalysis() {
        lagSourceAnalyzer.analyzeAsync().thenAccept(sources -> {
            try {
                if (sources.isEmpty())
                    return;
                List<String> report = lagSourceAnalyzer.formatCompactReport(sources);
                if (report.isEmpty())
                    return;

                String header = plugin.getPrefix() + "<gray>Possible lag causes:";
                // NotificationHelper dispatches to the correct thread internally
                SchedulerAdapter.runGlobal(plugin, () -> {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (player.hasPermission("lesslag.notify")) {
                            LessLag.sendMessage(player, header);
                            for (String line : report)
                                LessLag.sendMessage(player, line);
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

    private void checkRecovery(double snap) {
        if (!settingsModified)
            return;
        FileConfiguration config = plugin.getConfig();
        if (!config.getBoolean("recovery.enabled", true))
            return;

        double recoveryThreshold = config.getDouble("recovery.tps-threshold", 18.0);
        int delaySeconds = config.getInt("recovery.delay-seconds", 30);
        int checkIntervalTicks = config.getInt("system.tps-monitor.check-interval", 100);
        int neededChecks = Math.max(1, (int) ((long) delaySeconds * 20L / checkIntervalTicks));

        if (snap >= recoveryThreshold) {
            if (consecutiveGoodCount.incrementAndGet() >= neededChecks) {
                consecutiveGoodCount.set(0);
                SchedulerAdapter.runGlobal(plugin, () -> {
                    plugin.getLogger().info("TPS stabilized ("
                            + String.format("%.1f", currentTPS) + "). Restoring defaults...");
                    actionExecutor.restoreDefaults();
                    settingsModified = false;
                    activeThreshold.set(null);

                    String recoveryMsg = config.getString("messages.recovery",
                            "<green> TPS stabilized: {tps}")
                            .replace("{tps}", String.format("%.1f", currentTPS));
                    boolean playSound = config.getBoolean("notifications.sound", true);

                    if (SchedulerAdapter.isFolia()) {
                        for (Player player : Bukkit.getOnlinePlayers()) {
                            if (!player.hasPermission("lesslag.notify"))
                                continue;
                            final Player p = player;
                            SchedulerAdapter.runAtEntity(plugin, p, () -> {
                                if (!p.isOnline())
                                    return;
                                LessLag.sendMessage(p, plugin.getPrefix() + recoveryMsg);
                                if (playSound)
                                    p.playSound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
                            });
                        }
                    } else {
                        for (Player player : Bukkit.getOnlinePlayers()) {
                            if (!player.hasPermission("lesslag.notify"))
                                continue;
                            LessLag.sendMessage(player, plugin.getPrefix() + recoveryMsg);
                            if (playSound)
                                player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
                        }
                    }
                });
            }
        } else {
            consecutiveGoodCount.set(0);
        }
    }

    private void checkDiscordAlert(ThresholdConfig threshold, double snap) {
        if (!plugin.getConfig().getBoolean("premium.enabled", false))
            return;
        double alertThreshold = plugin.getConfig().getDouble("premium.discord.alert-tps-threshold", 18.0);
        if (snap > alertThreshold)
            return;

        long now = System.nanoTime();
        long prev = lastDiscordAlertTime.get();
        if (now - prev < 60_000_000_000L)
            return;
        if (!lastDiscordAlertTime.compareAndSet(prev, now))
            return;

        String msg = "**TPS Alert**: Server TPS dropped to **" + String.format("%.1f", snap) + "**!"
                + "\\nThreshold: " + threshold.getName();
        if (plugin.getPremiumManager() != null)
            plugin.getPremiumManager().sendAlert(msg);
    }

    // ── Getters (all volatile-safe) ───────────────────────────────────────────

    public double getCurrentTPS() {
        return currentTPS;
    }

    public ThresholdConfig getActiveThreshold() {
        return activeThreshold.get();
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
}
