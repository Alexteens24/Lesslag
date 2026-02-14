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
        // Add 2000 items (new limit)
        for (int i = 0; i < 2000; i++) {
            assertTrue(distributor.addWorkload(() -> {
            }), "Should add workload " + i);
        }
        assertEquals(2000, distributor.getQueueSize());

        // Add 2001st item - should succeed (Ring Buffer overwrites)
        assertTrue(distributor.addWorkload(() -> {
        }), "Should accept 2001st workload (overwriting oldest)");
        assertEquals(2000, distributor.getQueueSize());
    }

    @Test
    void testShutdownClearsQueue() {
        distributor.addWorkload(() -> {
        });
        assertEquals(1, distributor.getQueueSize());

        distributor.shutdown();
        assertEquals(0, distributor.getQueueSize());
        assertFalse(distributor.isProcessing());
    }

    @Test
    void testAtomicQueueOverflow() throws InterruptedException {
        // Simulate concurrent adds
        int threads = 10;
        int addsPerThread = 600; // Total 6000 adds, limit is 2000

        Thread[] worker = new Thread[threads];
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            worker[i] = new Thread(() -> {
                for (int j = 0; j < addsPerThread; j++) {
                    if (distributor.addWorkload(() -> {
                    })) {
                        successCount.incrementAndGet();
                    }
                }
            });
            worker[i].start();
        }

        for (int i = 0; i < threads; i++) {
            worker[i].join();
        }

        // Queue size should be capped at 2000
        assertEquals(2000, distributor.getQueueSize());
        // All adds are "successful" (accepted), even if they dropped an old one
        assertEquals(6000, successCount.get());
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
