package com.lesslag;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkloadDistributorTest {

    private LessLag lessLag;
    private Server server;
    private BukkitScheduler scheduler;
    private Logger logger;
    private WorkloadDistributor distributor;
    private FileConfiguration config;

    @BeforeEach
    void setUp() throws Exception {
        // Mock Server and Scheduler
        server = mock(Server.class);
        scheduler = mock(BukkitScheduler.class);
        when(server.getScheduler()).thenReturn(scheduler);

        // Force set Bukkit server via reflection to avoid "Server is already set" error
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, server);

        // Mock Plugin
        lessLag = mock(LessLag.class);
        logger = mock(Logger.class);
        when(lessLag.getLogger()).thenReturn(logger);

        config = mock(FileConfiguration.class);
        when(lessLag.getConfig()).thenReturn(config);
        when(config.getInt("workload-limit-ms", 2)).thenReturn(2);

        // Inject LessLag instance
        Field instanceField = LessLag.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, lessLag);

        distributor = new WorkloadDistributor();
        // Manually trigger reloadConfig since we are testing it
        // Note: In real plugin, onEnable calls reloadConfig.
        // But distributor calls LessLag.getInstance() in reloadConfig, so we must ensure instance is set.
        distributor.reloadConfig();
    }

    @AfterEach
    void tearDown() throws Exception {
        Field instanceField = LessLag.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);

        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, null);
    }

    @Test
    void testAddWorkloadSchedulesTask() {
        Runnable task = () -> {};

        // When
        boolean added = distributor.addWorkload(task);

        // Then
        assertTrue(added);
        assertEquals(1, distributor.getQueueSize());
        // Verify runTask was called to schedule startTimer
        verify(scheduler).runTask(eq(lessLag), any(Runnable.class));
    }

    @Test
    void testProcessWorkload() {
        // Capture the startTimer runnable
        ArgumentCaptor<Runnable> startTimerCaptor = ArgumentCaptor.forClass(Runnable.class);

        // Mock runTask to capture the runnable
        doAnswer(invocation -> {
            Runnable r = invocation.getArgument(1);
            r.run(); // Immediately run startTimer logic (simplified)
            return mock(BukkitTask.class);
        }).when(scheduler).runTask(eq(lessLag), any(Runnable.class));

        // Mock runTaskTimer to capture the processing loop
        ArgumentCaptor<Runnable> processLoopCaptor = ArgumentCaptor.forClass(Runnable.class);
        when(scheduler.runTaskTimer(eq(lessLag), processLoopCaptor.capture(), anyLong(), anyLong()))
                .thenReturn(mock(BukkitTask.class));

        // Add a workload
        AtomicBoolean ran = new AtomicBoolean(false);
        distributor.addWorkload(() -> ran.set(true));

        // Verify ensureRunning -> startTimer -> runTaskTimer chain
        // Since we mocked runTask to run immediately, startTimer runs immediately, calling runTaskTimer
        verify(scheduler).runTaskTimer(eq(lessLag), any(Runnable.class), eq(1L), eq(1L));

        // Get the actual processing loop runnable
        Runnable processLoop = processLoopCaptor.getValue();

        // Run the processing loop
        processLoop.run();

        // Assertions
        assertTrue(ran.get());
        assertEquals(0, distributor.getQueueSize());
    }

    // Helper class for atomic boolean since we can't use java.util.concurrent.atomic.AtomicBoolean easily in lambda without final
    static class AtomicBoolean {
        boolean value;
        AtomicBoolean(boolean value) { this.value = value; }
        void set(boolean v) { value = v; }
        boolean get() { return value; }
    }
}
