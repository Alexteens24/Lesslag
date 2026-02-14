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

    private final Deque<Runnable> highPriorityQueue = new ArrayDeque<>();
    private final Deque<Runnable> usageQueue = new ArrayDeque<>(); // Low priority, ring buffer
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile BukkitTask task;
    private Logger logger;

    private long maxNanosPerTick = 2_000_000; // Default 2ms
    private static final int MAX_USAGE_QUEUE_SIZE = 2000;

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

    public enum WorkloadPriority {
        HIGH, // Critical (User commands, restores) - Never dropped
        LOW // Background (Scanners, particles) - Ring buffer (drops oldest)
    }

    /**
     * Add a workload with LOW priority (default).
     */
    public boolean addWorkload(Runnable workload) {
        return addWorkload(workload, WorkloadPriority.LOW);
    }

    /**
     * Add a workload with specified priority.
     * Thread-safe.
     */
    public boolean addWorkload(Runnable workload, WorkloadPriority priority) {
        if (workload == null)
            return false;

        synchronized (this) {
            if (priority == WorkloadPriority.HIGH) {
                highPriorityQueue.addLast(workload);
            } else {
                if (usageQueue.size() >= MAX_USAGE_QUEUE_SIZE) {
                    usageQueue.pollFirst(); // Drop oldest to make space
                }
                usageQueue.addLast(workload);
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
                Runnable work = null;
                synchronized (this) {
                    // Drain High Priority first
                    work = highPriorityQueue.pollFirst();
                    if (work == null) {
                        work = usageQueue.pollFirst();
                    }
                }

                if (work == null)
                    break;

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
            synchronized (this) {
                isEmpty = highPriorityQueue.isEmpty() && usageQueue.isEmpty();
            }

            if (isEmpty) {
                if (task != null) {
                    task.cancel();
                    task = null;
                }
                running.set(false);

                // Double-check: items may have been added between isEmpty() and running.set()
                boolean recheckNotEmpty;
                synchronized (this) {
                    recheckNotEmpty = !highPriorityQueue.isEmpty() || !usageQueue.isEmpty();
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
        synchronized (this) {
            return highPriorityQueue.size() + usageQueue.size();
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
        synchronized (this) {
            highPriorityQueue.clear();
            usageQueue.clear();
        }
    }
}
