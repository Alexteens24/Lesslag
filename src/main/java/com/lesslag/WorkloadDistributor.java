package com.lesslag;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

/**
 * Distributes heavy workloads across multiple ticks to prevent server freeze.
 * Thread-safe — any thread can call addWorkload().
 * Implements a Ring Buffer (LIFO dropping) to prevent death spirals.
 */
public class WorkloadDistributor {

    private final Deque<Runnable> workloadQueue = new ArrayDeque<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile BukkitTask task;
    private Logger logger;

    private long maxNanosPerTick = 2_000_000; // Default 2ms
    private static final int MAX_QUEUE_SIZE = 2000;

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
        if (LessLag.getInstance() != null) {
            int maxMillis = LessLag.getInstance().getConfig().getInt("workload-limit-ms", 2);
            this.maxNanosPerTick = maxMillis * 1_000_000L;
        }
    }

    /**
     * Add a workload to process on the main thread, spread across ticks.
     * Thread-safe — can be called from any thread.
     * Uses a Ring Buffer strategy: if full, drops the oldest task.
     *
     * @return always true (as we overwrite oldest)
     */
    public boolean addWorkload(Runnable workload) {
        if (workload == null)
            return false;

        synchronized (workloadQueue) {
            if (workloadQueue.size() >= MAX_QUEUE_SIZE) {
                workloadQueue.pollFirst(); // Drop oldest to make space
            }
            workloadQueue.addLast(workload); // Add newest
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
            long budget = maxNanosPerTick;

            // Emergency Throttle: If server is struggling (MSPT > 45ms), reduce budget to
            // 0.5ms
            // This prevents the distributor from compounding lag.
            if (LessLag.getInstance() != null && LessLag.getInstance().getTpsMonitor() != null) {
                double mspt = LessLag.getInstance().getTpsMonitor().getCurrentMSPT();
                if (mspt > 45.0) {
                    budget = 500_000L; // 0.5ms
                }
            }

            long stopTime = System.nanoTime() + budget;

            while (System.nanoTime() < stopTime) {
                Runnable work;
                synchronized (workloadQueue) {
                    work = workloadQueue.pollFirst();
                }

                if (work == null) break;

                try {
                    long start = System.nanoTime();
                    work.run();
                    long duration = System.nanoTime() - start;
                    if (duration > 50_000_000L) { // Warn if single task > 50ms
                        getLogger().warning(
                                "[WorkloadDistributor] Slow task detected: " + (duration / 1_000_000.0) + "ms");
                    }
                } catch (Throwable e) {
                    getLogger().warning(
                            "[WorkloadDistributor] Workload threw exception: " + e.getMessage());
                }
            }

            boolean isEmpty;
            synchronized (workloadQueue) {
                isEmpty = workloadQueue.isEmpty();
            }

            if (isEmpty) {
                if (task != null) {
                    task.cancel();
                    task = null;
                }
                running.set(false);

                // Double-check: items may have been added between isEmpty() and running.set()
                boolean recheckNotEmpty;
                synchronized (workloadQueue) {
                    recheckNotEmpty = !workloadQueue.isEmpty();
                }

                if (recheckNotEmpty) {
                    ensureRunning();
                }
            }
        }, 1L, 1L);
    }

    /**
     * Get current queue size for monitoring.
     */
    public int getQueueSize() {
        synchronized (workloadQueue) {
            return workloadQueue.size();
        }
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
        synchronized (workloadQueue) {
            workloadQueue.clear();
        }
    }
}
