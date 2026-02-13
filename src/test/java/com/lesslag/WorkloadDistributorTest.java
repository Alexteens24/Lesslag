package com.lesslag;

import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkloadDistributorTest {

    private TestWorkloadDistributor distributor;
    private Logger mockLogger;

    @BeforeEach
    void setUp() {
        mockLogger = mock(Logger.class);
        distributor = new TestWorkloadDistributor();
        distributor.setLogger(mockLogger);
    }

    @Test
    void testAddWorkloadLimit() {
        // Add 5000 items
        for (int i = 0; i < 5000; i++) {
            assertTrue(distributor.addWorkload(() -> {}), "Should add workload " + i);
        }
        assertEquals(5000, distributor.getQueueSize());

        // Add 5001st item - should fail
        assertFalse(distributor.addWorkload(() -> {}), "Should reject 5001st workload");
        assertEquals(5000, distributor.getQueueSize());

        // Verify warning logged
        verify(mockLogger).warning(contains("Queue overflow"));
    }

    @Test
    void testShutdownClearsQueue() {
        distributor.addWorkload(() -> {});
        assertEquals(1, distributor.getQueueSize());

        distributor.shutdown();
        assertEquals(0, distributor.getQueueSize());
        assertFalse(distributor.isProcessing());
    }

    @Test
    void testAtomicQueueOverflow() throws InterruptedException {
        // Simulate concurrent adds
        int threads = 10;
        int addsPerThread = 600; // Total 6000 adds, limit is 5000

        Thread[] worker = new Thread[threads];
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            worker[i] = new Thread(() -> {
                for (int j = 0; j < addsPerThread; j++) {
                    if (distributor.addWorkload(() -> {})) {
                        successCount.incrementAndGet();
                    }
                }
            });
            worker[i].start();
        }

        for (int i = 0; i < threads; i++) {
            worker[i].join();
        }

        assertEquals(5000, distributor.getQueueSize());
        assertEquals(5000, successCount.get());
    }

    // Subclass to override Bukkit calls
    static class TestWorkloadDistributor extends WorkloadDistributor {
        @Override
        protected void scheduleStartTimer(Runnable run) {
            // Do nothing
        }

        @Override
        protected BukkitTask scheduleTimerTask(Runnable run, long delay, long period) {
            return mock(BukkitTask.class);
        }
    }
}
