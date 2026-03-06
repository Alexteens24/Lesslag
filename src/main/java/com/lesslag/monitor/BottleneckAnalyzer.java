package com.lesslag.monitor;

import com.lesslag.LessLag;
import com.lesslag.util.NotificationHelper;
import com.lesslag.util.SchedulerAdapter;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An async watchdog that constantly monitors the main server thread.
 * If the main thread hangs for too long, it aggressively samples its stack
 * trace
 * to identify the exact cause of the bottleneck/lag spike.
 */
public class BottleneckAnalyzer {

    private final LessLag plugin;
    private final ThreadMXBean threadBean;

    private Thread watchdogThread;
    private volatile boolean running = false;

    // Config values
    private long thresholdMs;
    private long sampleIntervalMs;
    private int minSamples;
    private long reportCooldownMs;

    // State tracking
    private volatile long lastTickTimeNano;
    private long mainThreadId = -1;

    // Sampling context
    private volatile boolean isSpiking = false;
    private final Map<String, Integer> currentSpikeSamples = new ConcurrentHashMap<>();
    private final AtomicInteger totalSamplesInCurrentSpike = new AtomicInteger(0);
    private volatile long lastReportTimeMs = 0;
    private long startTimeMs = -1; // used for startup-ignore window

    // Extra config values
    private double minConcentration; // top method must be >= this fraction of total samples
    private long startupIgnoreMs; // suppress alerts in first N ms after start (JIT warmup)

    // Runtime stats (volatile for cross-thread command reads)
    private volatile int totalSpikes = 0;
    private volatile long worstSpikeDurationMs = 0;
    private volatile String worstSpikeCulprit = "";
    private volatile String lastSpikeCulprit = "";
    private volatile long lastSpikeTimeMs = 0;

    public BottleneckAnalyzer(LessLag plugin) {
        this.plugin = plugin;
        this.threadBean = ManagementFactory.getThreadMXBean();
        loadConfig();
    }

    private void loadConfig() {
        this.thresholdMs = plugin.getConfig().getLong("system.bottleneck-analyzer.threshold-ms", 150L);
        this.sampleIntervalMs = plugin.getConfig().getLong("system.bottleneck-analyzer.sample-interval-ms", 5L);
        this.minSamples = plugin.getConfig().getInt("system.bottleneck-analyzer.min-samples", 10);
        this.reportCooldownMs = plugin.getConfig().getLong("system.bottleneck-analyzer.report-cooldown-ms", 30_000L);
        // New: minimum fraction of samples the top method must hold (0.0–1.0).
        // GC / JIT pauses scatter samples broadly; a real bottleneck concentrates them.
        this.minConcentration = plugin.getConfig().getDouble("system.bottleneck-analyzer.min-concentration", 0.40);
        // Suppress alerts during JVM JIT warmup window after server starts.
        this.startupIgnoreMs = plugin.getConfig().getLong("system.bottleneck-analyzer.startup-ignore-ms", 60_000L);
    }

    /**
     * Start the watchdog thread
     */
    public void start() {
        if (!plugin.getConfig().getBoolean("system.bottleneck-analyzer.enabled", true)) {
            return;
        }

        // Regionized runtimes (Folia/Luminol/etc.) do not have a single authoritative
        // main thread for tick ownership. The watchdog model here is built around one
        // main thread, so it can produce false positives on Folia.
        if (SchedulerAdapter.isFolia()) {
            running = false;
            plugin.getLogger().info(
                    "BottleneckAnalyzer disabled on Folia/regionized runtime (single-thread watchdog is not reliable).");
            return;
        }

        // Find main thread ID by looking for the thread named "Server thread" (standard
        // Spigot/Paper)
        ThreadInfo[] threads = threadBean.dumpAllThreads(false, false);
        for (ThreadInfo ti : threads) {
            // Paper might name it "Server thread", older versions could differ slightly,
            // but usually it's this.
            if ("Server thread".equals(ti.getThreadName())) {
                mainThreadId = ti.getThreadId();
                break;
            }
        }

        if (mainThreadId == -1) {
            plugin.getLogger().warning(
                    "BottleneckAnalyzer: Could not identify main 'Server thread'. Using fallback heuristic...");
            // Fallback: the thread that started the plugin is usually the main thread
            mainThreadId = Thread.currentThread().threadId();
        }

        lastTickTimeNano = System.nanoTime();
        startTimeMs = System.currentTimeMillis();
        running = true;

        watchdogThread = new Thread(this::runWatchdog, "LessLag-Watchdog");
        watchdogThread.setDaemon(true);
        watchdogThread.setPriority(Thread.MAX_PRIORITY); // Needs to run when everything else lags
        watchdogThread.start();

        plugin.getLogger().info(
                "BottleneckAnalyzer started (Threshold: " + thresholdMs + "ms, Sampling: " + sampleIntervalMs + "ms)");

        // No own per-tick task — TPSMonitor calls tickPing() from its single tick
        // lambda
    }

    public void stop() {
        running = false;
        if (watchdogThread != null) {
            watchdogThread.interrupt();
            watchdogThread = null;
        }
    }

    /**
     * Called from TPSMonitor's per-tick lambda to reset the watchdog timer.
     * Also processes any spikes that just finished.
     * Eliminates a separate runGlobalRepeating(1L, 1L) task.
     */
    public void tickPing() {
        if (!running)
            return;
        long now = System.nanoTime();
        long elapsedMs = (now - lastTickTimeNano) / 1_000_000L;
        lastTickTimeNano = now;

        // If we were spiking but now the tick completed, process the samples
        if (isSpiking) {
            isSpiking = false;
            int totalSamples = totalSamplesInCurrentSpike.getAndSet(0);
            if (totalSamples > 0) {
                processAndReportSpike(new HashMap<>(currentSpikeSamples), totalSamples,
                        Math.max(elapsedMs, thresholdMs));
                currentSpikeSamples.clear();
            }
        }
    }

    /**
     * The async loop that checks the main thread's status
     */
    private void runWatchdog() {
        while (running) {
            try {
                long now = System.nanoTime();
                long elapsedMs = (now - lastTickTimeNano) / 1_000_000L;

                if (elapsedMs >= thresholdMs) {
                    // MAIN THREAD IS HANGING! Start sampling.
                    isSpiking = true;
                    takeSample();

                    // Sleep for the short sample interval while it's still hanging
                    Thread.sleep(sampleIntervalMs);
                } else {
                    // Not hanging yet, wait until the threshold could be reached
                    long timeUntilThreshold = thresholdMs - elapsedMs;
                    Thread.sleep(Math.max(1, Math.min(timeUntilThreshold, 50)));
                }

            } catch (InterruptedException e) {
                if (!running)
                    break;
            } catch (Exception e) {
                plugin.getLogger().warning("BottleneckAnalyzer encountered an error: " + e.getMessage());
            }
        }
    }

    /**
     * Snapshots the main thread's stack trace and records the deepest non-native
     * method.
     */
    private void takeSample() {
        if (mainThreadId == -1)
            return;

        ThreadInfo info = threadBean.getThreadInfo(mainThreadId, 50); // get top 50 frames
        if (info == null)
            return;

        StackTraceElement[] stack = info.getStackTrace();
        if (stack == null || stack.length == 0)
            return;

        String key = extractMeaningfulMethod(stack);
        if (key != null) {
            currentSpikeSamples.put(key, currentSpikeSamples.getOrDefault(key, 0) + 1);
            totalSamplesInCurrentSpike.incrementAndGet();
        }
    }

    /**
     * Walks down the stack trace to find the most likely culprit method.
     * First pass: Prioritize methods belonging to plugins (non-core / non-library).
     * Second pass: If purely a server/vanilla issue, return the deepest actionable
     * method.
     */
    private String extractMeaningfulMethod(StackTraceElement[] stack) {
        // First pass: find a clear third-party / plugin method that is not a core
        // library/server package.
        for (StackTraceElement element : stack) {
            String className = element.getClassName();
            if (!isCoreServerOrLibrary(className)) {
                return className + "." + element.getMethodName();
            }
        }

        // Second pass: No obvious plugin found. Find the deepest method that isn't
        // completely generic/benign.
        for (StackTraceElement element : stack) {
            String className = element.getClassName();
            String methodName = element.getMethodName();

            // Skip standard JVM/Libraries
            if (className.startsWith("java.") || className.startsWith("javax.") ||
                    className.startsWith("sun.") || className.startsWith("jdk.") ||
                    className.startsWith("io.netty.") || className.startsWith("com.mojang.") ||
                    className.startsWith("it.unimi.") || className.startsWith("com.google.") ||
                    className.startsWith("org.apache.") || className.startsWith("org.yaml.") ||
                    className.startsWith("org.slf4j.") || className.startsWith("jline.") ||
                    className.startsWith("org.jline.") || className.startsWith("net.minecrell.") ||
                    className.startsWith("org.sqlite.") || className.startsWith("com.mysql.") ||
                    className.startsWith("com.zaxxer.hikari.")) {
                continue;
            }

            // Skip standard server wait/poll/tick/loop infrastructure that don't give
            // insight
            if (className.startsWith("net.minecraft.") || className.startsWith("org.bukkit.") ||
                    className.startsWith("com.destroystokyo.paper.") || className.startsWith("io.papermc.")) {

                // If it's the main server loop, it's not helpful
                if (className.contains("MinecraftServer") || className.contains("ServerLevel")
                        || className.contains("DedicatedServer")) {
                    if (methodName.equals("tick") || methodName.equals("doTick") || methodName.equals("tickServer")
                            || methodName.equals("tickChildren")
                            || methodName.equals("runServer") || methodName.equals("run")
                            || methodName.equals("executeModerately")
                            || methodName.equals("pollUntilIdle")) {
                        continue;
                    }
                }

                // Skip basic network/pipeline generic tasks
                if (className.contains("PlayerConnection") || className.contains("NetworkManager")) {
                    if (methodName.equals("tick") || methodName.equals("sendPacket")
                            || methodName.equals("handlePacket") || methodName.equals("flush")) {
                        continue;
                    }
                }

                // Skip generic waits/locks
                if (methodName.equals("await") || methodName.equals("lock")
                        || methodName.equals("park") || methodName.equals("sleep")
                        || methodName.toLowerCase().contains("wait")) {
                    continue;
                }
            }

            // Skip chunk loading / saving / standard NMS operations that aren't usually
            // actionable
            if ((className.contains("ChunkMap") || className.contains("ChunkStorage")
                    || className.contains("ChunkSerializer") || className.contains("RegionFile")
                    || className.contains("PaperChunk") || className.contains("PlayerChunk"))
                    && (methodName.equals("saveChunk") || methodName.equals("loadChunk")
                            || methodName.equals("getChunkAt") || methodName.equals("load")
                            || methodName.equals("save") || methodName.equals("processQueue"))) {
                continue;
            }

            return className + "." + methodName;
        }

        // Extreme fallback (if everything was filtered, return the top of the stack)
        if (stack.length > 0) {
            return stack[0].getClassName() + "." + stack[0].getMethodName();
        }

        return null;
    }

    private boolean isCoreServerOrLibrary(String className) {
        return className.startsWith("net.minecraft.")
                || className.startsWith("org.spigotmc.")
                || className.startsWith("org.bukkit.")
                || className.startsWith("com.destroystokyo.paper.")
                || className.startsWith("io.papermc.")
                || className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("sun.")
                || className.startsWith("jdk.")
                || className.startsWith("com.mojang.")
                || className.startsWith("io.netty.")
                || className.startsWith("it.unimi.")
                || className.startsWith("com.google.")
                || className.startsWith("org.apache.")
                || className.startsWith("org.yaml.")
                || className.startsWith("org.slf4j.")
                || className.startsWith("jline.")
                || className.startsWith("org.jline.")
                || className.startsWith("net.minecrell.")
                || className.startsWith("com.mysql.")
                || className.startsWith("org.sqlite.")
                || className.startsWith("com.zaxxer.hikari.");
    }

    /**
     * Analyze aggregated samples and report to admins.
     *
     * <p>
     * Three gates must all pass before a report fires:
     * <ol>
     * <li>{@code minSamples} — enough data collected</li>
     * <li>{@code minConcentration} — the top method dominates samples
     * (GC/JIT scatter is rejected here)</li>
     * <li>{@code startupIgnoreMs} — server has passed JIT warmup window</li>
     * <li>{@code reportCooldownMs} — not spamming</li>
     * </ol>
     */
    private void processAndReportSpike(Map<String, Integer> samples, int totalSamples, long measuredDurationMs) {
        if (totalSamples == 0 || samples.isEmpty())
            return;
        if (totalSamples < minSamples)
            return;

        // Gate 1: startup warmup window — JIT causes spikes in the first ~60s
        if (startTimeMs > 0 && (System.currentTimeMillis() - startTimeMs) < startupIgnoreMs)
            return;

        // Gate 2: concentration check — if samples are scattered (GC / OS scheduling),
        // the top method won't dominate. Only report clear single-culprit spikes.
        Map.Entry<String, Integer> worstMethod = Collections.max(
                samples.entrySet(), Map.Entry.comparingByValue());
        double concentration = (worstMethod.getValue() * 1.0) / totalSamples;
        if (concentration < minConcentration)
            return; // scattered → skip

        // Gate 3: cooldown
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastReportTimeMs < reportCooldownMs)
            return;
        lastReportTimeMs = nowMs;

        double percentage = (worstMethod.getValue() * 100.0) / totalSamples;
        long durationMs = measuredDurationMs;

        // Track stats
        totalSpikes++;
        lastSpikeCulprit = worstMethod.getKey();
        lastSpikeTimeMs = System.currentTimeMillis();
        if (durationMs > worstSpikeDurationMs) {
            worstSpikeDurationMs = durationMs;
            worstSpikeCulprit = worstMethod.getKey();
        }

        // Format the name nicely
        String methodName = worstMethod.getKey();
        if (methodName.length() > 40) {
            // Trim to max 40 chars
            methodName = "..." + methodName.substring(methodName.length() - 37);
        }

        String message = plugin.getConfig().getString("messages.bottleneck-detected",
                "&c&lLAG SPIKE! &7({duration}ms) Caused by: &e{method} &7({percent}%)")
                .replace("{duration}", String.valueOf(durationMs))
                .replace("{method}", methodName)
                .replace("{percent}", String.format("%.1f", percentage));

        // Use NotificationHelper to broadcast to admins
        NotificationHelper.notifyAdminsAsync(message);

        // Console logging
        plugin.getLogger().warning(String.format("Lag Spike Detected: %dms | Worst Method: %s (%.1f%% of samples)",
                durationMs, worstMethod.getKey(), percentage));

        // Optional: Discord webhook if premium enabled
        if (plugin.getConfig().getBoolean("premium.enabled", false)) {
            // Wait 1 second before discord alert to prevent spamming the Discord API during
            // massive lag
            long finalDuration = durationMs;
            String finalMethod = worstMethod.getKey();
            SchedulerAdapter.runAsyncDelayed(plugin, () -> {
                if (plugin.getPremiumManager() != null) {
                    plugin.getPremiumManager().sendAlert(
                            "**Lag Spike Detected**: `" + finalDuration + "ms`\n" +
                                    "**Primary Culprit**: `" + finalMethod + "` (" + String.format("%.1f%%", percentage)
                                    + ")");
                }
            }, 20L);
        }
    }

    // ── Getters (volatile-safe for command reads) ──

    public int getTotalSpikes() {
        return totalSpikes;
    }

    public long getWorstSpikeDurationMs() {
        return worstSpikeDurationMs;
    }

    public String getWorstSpikeCulprit() {
        return worstSpikeCulprit;
    }

    public String getLastSpikeCulprit() {
        return lastSpikeCulprit;
    }

    public long getLastSpikeTimeMs() {
        return lastSpikeTimeMs;
    }

    public boolean isRunning() {
        return running;
    }
}
