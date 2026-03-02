package com.lesslag;

import com.lesslag.util.SchedulerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.io.File;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

public class PluginIntegrationSmokeTest {

    @Test
    public void testLifecycleSmokeRunsWithRepeatingTaskAndDisableCleanupPath() throws Exception {
        LessLag plugin = allocateWithoutConstructor();

        Plugin schedulerPlugin = Mockito.mock(Plugin.class);
        when(schedulerPlugin.getLogger()).thenReturn(Logger.getLogger("PluginIntegrationSmokeTest"));

        Object bukkitSchedulerProxy = createBukkitSchedulerProxy();

        try (MockedStatic<Bukkit> bukkitMock = Mockito.mockStatic(Bukkit.class)) {
            bukkitMock.when(Bukkit::getServer).thenReturn(null);
            bukkitMock.when(Bukkit::getScheduler).thenReturn(bukkitSchedulerProxy);

            SchedulerAdapter.setFoliaDetectionOverrideForTests(false);
            SchedulerAdapter adapter = new SchedulerAdapter(schedulerPlugin);

            AtomicInteger repeatingRuns = new AtomicInteger();
            assertDoesNotThrow(() -> adapter.runRepeating(repeatingRuns::incrementAndGet, 1L, 1L));
            assertEquals(1, repeatingRuns.get());

            assertDoesNotThrow(plugin::onLoad);
            assertDoesNotThrow(plugin::onEnable);
            assertDoesNotThrow(plugin::onDisable);
        }
    }

    @Test
    public void testPluginYmlIsValidAndFoliaEnabled() {
        // Load from classpath (target/classes/plugin.yml) since test CWD may vary
        java.io.InputStream stream = getClass().getClassLoader().getResourceAsStream("plugin.yml");
        assertNotNull(stream, "plugin.yml should be on the classpath");

        YamlConfiguration config = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(stream));
        assertTrue(config.getBoolean("folia-supported"));
    }

    private static LessLag allocateWithoutConstructor() throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field field = unsafeClass.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        return (LessLag) unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, LessLag.class);
    }

    private static Object createBukkitSchedulerProxy() throws Exception {
        Class<?> schedulerClass = Class.forName("org.bukkit.scheduler." + "BukkitScheduler");
        return java.lang.reflect.Proxy.newProxyInstance(
                schedulerClass.getClassLoader(),
                new Class<?>[] { schedulerClass },
                (proxy, method, args) -> {
                    if (method.getName().startsWith("run") && args != null) {
                        for (Object arg : args) {
                            if (arg instanceof Runnable) {
                                ((Runnable) arg).run();
                                break;
                            }
                        }
                    }
                    return null;
                });
    }
}
