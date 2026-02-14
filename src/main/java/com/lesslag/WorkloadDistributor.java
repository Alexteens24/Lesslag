package com.lesslag;

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

    private final Deque<Runnable> highPriorityQueue = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final Deque<Runnable> usageQueue = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final java.util.concurrent.atomic.AtomicInteger usageQueueSize = new java.util.concurrent.atomic.AtomicInteger(
            0);

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
        LOW // Background (Scanners, particles) - Drops if full
    }

    /**
     * Add a workload with LOW priority (default).
     */
    public boolean addWorkload(Runnable workload) {
        return addWorkload(workload, WorkloadPriority.LOW);
    }

    /**
     * Add a workload with specified priority.
     * Thread-safe & Non-blocking (Lock-free).
     */
    public boolean addWorkload(Runnable workload, WorkloadPriority priority) {
        if (workload == null)
            return false;

        if (priority == WorkloadPriority.HIGH) {
            highPriorityQueue.addLast(workload);
        } else {
            // Optimistic check. If we are slightly over limit due to race condition, it is
            // fine.
            // Strict counting is less important than throughput.
            if (usageQueueSize.get() >= MAX_USAGE_QUEUE_SIZE) {
                // Drop oldest to make space?
                // In a lock-free concurrent deque, polling specific elements (head) while
                // adding to tail is safe but
                // creating a "Slot" is tricky without locking.
                // Strategy: Just poll one off the head to "make room" then add.
                Runnable dropped = usageQueue.pollFirst();
                if (dropped != null) {
                    usageQueueSize.decrementAndGet();
                }
            }
            usageQueue.addLast(workload);
            usageQueueSize.incrementAndGet();
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
            if (LessLag.getInstance() != null && LessLag.getInstance().getTpsMonitor() != null) {
                double mspt = LessLag.getInstance().getTpsMonitor().getCurrentMSPT();
                if (mspt > 45.0) {
                    budget = 500_000L; // 0.5ms
                }
            }

            long stopTime = System.nanoTime() + budget;

            while (System.nanoTime() < stopTime) {
                Runnable work = highPriorityQueue.pollFirst();

                if (work == null) {
                    work = usageQueue.pollFirst();
                    if (work != null) {
                        usageQueueSize.decrementAndGet();
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

            // Check if queues are empty to stop the timer
            if (highPriorityQueue.isEmpty() && usageQueue.isEmpty()) {
                if (task != null) {
                    task.cancel();
                    task = null;
                }
                running.set(false);

                // Double-check (Race condition: item added after check but before set(false))
                if (!highPriorityQueue.isEmpty() || !usageQueue.isEmpty()) {
                    ensureRunning();
                }
            }
        }, 1L, 1L);
    }

    /**
     * Get current queue size for monitoring.
     * Note: size() on ConcurrentLinkedDeque is O(n), so we use the counter for
     * usage queue
     * and accept O(n) for high priority (which should be small).
     */
    public int getQueueSize() {
        return highPriorityQueue.size() + usageQueueSize.get();
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

        highPriorityQueue.clear();
        usageQueue.clear();
        usageQueueSize.set(0);
    }
}
