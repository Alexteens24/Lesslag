package com.lesslag.util;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

public class SchedulerAdapterDetectionTest {

    @AfterEach
    public void tearDown() {
        SchedulerAdapter.setFoliaDetectionOverrideForTests(null);
        SchedulerAdapter.clearAdapterCacheForTests();
    }

    @Test
    public void testDetectsFoliaAbsentWhenNoMarkerAndNoGlobalSchedulerGetter() {
        Plugin plugin = Mockito.mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("SchedulerAdapterDetectionTest"));
        SchedulerAdapter.setFoliaDetectionOverrideForTests(false);

        SchedulerAdapter adapter = new SchedulerAdapter(plugin);
        System.out.println("isFoliaDetected(absent)=" + adapter.isFoliaDetected());
        assertFalse(adapter.isFoliaDetected());
    }

    @Test
    public void testDetectsFoliaPresentWhenGlobalSchedulerGetterExists() {
        Plugin plugin = Mockito.mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("SchedulerAdapterDetectionTest"));

        SchedulerAdapter.setFoliaDetectionOverrideForTests(true);

        SchedulerAdapter adapter = new SchedulerAdapter(plugin);
        System.out.println("isFoliaDetected(present)=" + adapter.isFoliaDetected());
        assertTrue(adapter.isFoliaDetected());
    }
}
