package com.lesslag;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

/**
 * Distributes heavy workloads across multiple ticks to prevent server freeze.
 * Thread-safe — any thread can call addWorkload().
 */
public class WorkloadDistributor {

    private final Queue<Runnable> workloadQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger queueSize = new AtomicInteger(0);
    private volatile BukkitTask task;
    private Logger logger;

    private long maxNanosPerTick = 2_000_000; // Default 2ms
    private static final int MAX_QUEUE_SIZE = 5000;

    public WorkloadDistributor() {
        // Delay config loading until onEnable
    }

    public void setLogger(Logger logger) {
        this.logger = logger;
    }

    private Logger getLogger() {
        if (logger == null) {
            try {
                if (LessLag.getInstance() != null) {
                    logger = LessLag.getInstance().getLogger();
                } else {
                    logger = Logger.getGlobal();
                }
            } catch (Exception e) {
                logger = Logger.getGlobal();
            }
        }
        return logger;
    }

    public void reloadConfig() {
        int maxMillis = LessLag.getInstance().getConfig().getInt("workload-limit-ms", 2);
        this.maxNanosPerTick = maxMillis * 1_000_000L;
    }

    /**
     * Add a workload to process on the main thread, spread across ticks.
     * Thread-safe — can be called from any thread.
     *
     * @return true if added, false if queue is full
     */
    public boolean addWorkload(Runnable workload) {
        if (workload == null)
            return false;

        // Optimistic increment to reserve a slot
        int currentSize = queueSize.incrementAndGet();
        if (currentSize > MAX_QUEUE_SIZE) {
            queueSize.decrementAndGet();
            // Log only on the transition to full to avoid spam
            if (currentSize == MAX_QUEUE_SIZE + 1) {
                getLogger().warning(
                        "[WorkloadDistributor] Queue overflow! Dropping workload (max: " + MAX_QUEUE_SIZE + ")");
            }
            return false;
        }

        boolean success = false;
        try {
            workloadQueue.add(workload);
            success = true;
        } finally {
            if (!success) {
                queueSize.decrementAndGet();
            }
        }

        ensureRunning();
        return true;
    }

    private void ensureRunning() {
        if (running.compareAndSet(false, true)) {
            // Schedule on main thread — must dispatch from any thread safely
            scheduleStartTimer(this::startTimer);
        }
    }

    protected void scheduleStartTimer(Runnable run) {
        Bukkit.getScheduler().runTask(LessLag.getInstance(), run);
    }

    protected BukkitTask scheduleTimerTask(Runnable run, long delay, long period) {
        return Bukkit.getScheduler().runTaskTimer(LessLag.getInstance(), run, delay, period);
    }

    private void startTimer() {
        if (task != null && !task.isCancelled())
            return;

        task = scheduleTimerTask(() -> {
            long stopTime = System.nanoTime() + maxNanosPerTick;

            while (!workloadQueue.isEmpty() && System.nanoTime() < stopTime) {
                Runnable work = workloadQueue.poll();
                if (work != null) {
                    try {
                        long start = System.nanoTime();
                        work.run();
                        long duration = System.nanoTime() - start;
                        if (duration > 50_000_000L) { // Warn if single task > 50ms
                            getLogger().warning(
                                    "[WorkloadDistributor] Slow task detected: " + (duration / 1_000_000.0) + "ms");
                        }
                    } catch (Exception e) {
                        getLogger().warning(
                                "[WorkloadDistributor] Workload threw exception: " + e.getMessage());
                    }
                    queueSize.decrementAndGet();
                }
            }

            if (workloadQueue.isEmpty()) {
                if (task != null) {
                    task.cancel();
                    task = null;
                }
                running.set(false);

                // Double-check: items may have been added between isEmpty() and running.set()
                if (!workloadQueue.isEmpty()) {
                    ensureRunning();
                }
            }
        }, 1L, 1L);
    }

    /**
     * Get current queue size for monitoring.
     */
    public int getQueueSize() {
        return queueSize.get();
    }

    /**
     * Check if the distributor is actively processing.
     */
    public boolean isProcessing() {
        return running.get();
    }

    /**
     * Stop processing and clear the queue.
     */
    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        running.set(false);
        workloadQueue.clear();
        queueSize.set(0);
    }
}
