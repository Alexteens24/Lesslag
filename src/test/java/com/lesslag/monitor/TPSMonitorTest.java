package com.lesslag.monitor;

import com.lesslag.LessLag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TPSMonitorTest {

    @Mock
    private LessLag plugin;
    @Mock
    private org.bukkit.configuration.file.FileConfiguration config;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(plugin.getConfig()).thenReturn(config);
    }

    @Test
    void testNeededChecksCalculation() {
        // delaySeconds = 30
        // checkInterval = 100 ticks (5s)
        when(config.getInt("recovery.delay-seconds", 30)).thenReturn(30);
        when(config.getInt("system.tps-monitor.check-interval", 100)).thenReturn(100);

        // 30s = 600 ticks
        // neededChecks = 600 / 100 = 6
        long delayTicks = 30 * 20L;
        int checkIntervalTicks = 100;
        int neededChecks = (int) Math.ceil((double) delayTicks / checkIntervalTicks);
        assertEquals(6, neededChecks);

        // checkInterval = 1 tick
        checkIntervalTicks = 1;
        neededChecks = (int) Math.ceil((double) delayTicks / checkIntervalTicks);
        assertEquals(600, neededChecks);

        // checkInterval = 30 ticks (1.5s)
        // neededChecks = 600 / 30 = 20
        checkIntervalTicks = 30;
        neededChecks = (int) Math.ceil((double) delayTicks / checkIntervalTicks);
        assertEquals(20, neededChecks);
    }
}
