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
    private SchedulerAdapter.TaskHandle pingTask;
    private volatile boolean running = false;

    // Config values
    private long thresholdMs;
    private long sampleIntervalMs;

    // State tracking
    private volatile long lastTickTimeNano;
    private long mainThreadId = -1;

    // Sampling context
    private volatile boolean isSpiking = false;
    private final Map<String, Integer> currentSpikeSamples = new ConcurrentHashMap<>();
    private final AtomicInteger totalSamplesInCurrentSpike = new AtomicInteger(0);

    public BottleneckAnalyzer(LessLag plugin) {
        this.plugin = plugin;
        this.threadBean = ManagementFactory.getThreadMXBean();
        loadConfig();
    }

    private void loadConfig() {
        // Defaults to 100ms threshold, 5ms sampling interval
        this.thresholdMs = plugin.getConfig().getLong("system.bottleneck-analyzer.threshold-ms", 100L);
        this.sampleIntervalMs = plugin.getConfig().getLong("system.bottleneck-analyzer.sample-interval-ms", 5L);
    }

    /**
     * Start the watchdog thread
     */
    public void start() {
        if (!plugin.getConfig().getBoolean("system.bottleneck-analyzer.enabled", true)) {
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
        running = true;

        watchdogThread = new Thread(this::runWatchdog, "LessLag-Watchdog");
        watchdogThread.setDaemon(true);
        watchdogThread.setPriority(Thread.MAX_PRIORITY); // Needs to run when everything else lags
        watchdogThread.start();

        plugin.getLogger().info(
                "BottleneckAnalyzer started (Threshold: " + thresholdMs + "ms, Sampling: " + sampleIntervalMs + "ms)");

        // Register sync task to ping the watchdog every tick
        pingTask = SchedulerAdapter.runGlobalRepeating(plugin, this::pingWatchdog, 1L, 1L);
    }

    public void stop() {
        running = false;
        if (watchdogThread != null) {
            watchdogThread.interrupt();
            watchdogThread = null;
        }
        if (pingTask != null) {
            pingTask.cancel();
            pingTask = null;
        }
    }

    /**
     * Called on the MAIN THREAD every tick to reset the watchdog timer.
     * Also processes any spikes that just finished.
     */
    private void pingWatchdog() {
        lastTickTimeNano = System.nanoTime();

        // If we were spiking but now the tick completed, process the samples
        if (isSpiking) {
            isSpiking = false;
            int totalSamples = totalSamplesInCurrentSpike.getAndSet(0);
            if (totalSamples > 0) {
                processAndReportSpike(new HashMap<>(currentSpikeSamples), totalSamples);
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
     * Ignores common Bukkit/Minecraft server loop methods.
     */
    private String extractMeaningfulMethod(StackTraceElement[] stack) {
        for (StackTraceElement element : stack) {
            String className = element.getClassName();

            // Skip JVM internals
            if (className.startsWith("java.") || className.startsWith("javax.") || className.startsWith("sun.")) {
                continue;
            }

            // Skip standard NMS/Paper tick loops unless it's the only thing there
            if (className.startsWith("net.minecraft.server") &&
                    (element.getMethodName().equals("tick") || element.getMethodName().equals("doTick"))) {
                continue;
            }

            // Format: com.plugin.Class.method
            return className + "." + element.getMethodName();
        }

        // Fallback to top if everything was skipped
        if (stack.length > 0) {
            return stack[0].getClassName() + "." + stack[0].getMethodName();
        }
        return "Unknown";
    }

    /**
     * Analyze aggregated samples and report to admins
     */
    private void processAndReportSpike(Map<String, Integer> samples, int totalSamples) {
        if (totalSamples == 0 || samples.isEmpty())
            return;

        // Find the method taking the most time
        Map.Entry<String, Integer> worstMethod = Collections.max(
                samples.entrySet(),
                Map.Entry.comparingByValue());

        double percentage = (worstMethod.getValue() * 100.0) / totalSamples;
        // The lastTickTimeNano was already updated by the pingWatchdog before this
        // method was called,
        // so we calculate the approximate duration based on the number of samples
        // taken.
        // We add thresholdMs because sampling only starts AFTER the threshold is
        // reached.
        long durationMs = thresholdMs + (totalSamples * sampleIntervalMs);

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
}
