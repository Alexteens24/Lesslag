package com.lesslag.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

public class SchedulerAdapterConcurrencyTest {

    @AfterEach
    public void tearDown() {
        SchedulerAdapter.setFoliaDetectionOverrideForTests(null);
        SchedulerAdapter.clearAdapterCacheForTests();
    }

    @Test
    public void testRegionAndBlockSchedulingStressRunsWithoutAsyncAccessErrors() throws Exception {
        Plugin plugin = Mockito.mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("SchedulerAdapterConcurrencyTest"));

        FoliaServerHarness harness = new FoliaServerHarness();
        SchedulerAdapter adapter = new SchedulerAdapter(plugin, true, harness.globalScheduler, harness.regionScheduler,
                harness.asyncScheduler);
        assertTrue(adapter.isFoliaDetected());

        int workerThreads = 50;
        int tasks = 5000;
        ExecutorService launcher = Executors.newFixedThreadPool(workerThreads);
        CountDownLatch done = new CountDownLatch(tasks);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        World world = Mockito.mock(World.class);

        for (int i = 0; i < tasks; i++) {
            launcher.submit(() -> {
                try {
                    Location loc = new Location(world, 1.0, 64.0, 1.0);
                    adapter.runAtLocation(loc, done::countDown);
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
        }

        launcher.shutdown();
        assertTrue(launcher.awaitTermination(10, TimeUnit.SECONDS));
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertTrue(failures.isEmpty(), "Expected no failures but got: " + failures);
        assertEquals(tasks, harness.regionScheduler.locationRuns.get());
    }

    @Test
    public void testEntityTeleportStressCompletesWithSuccessFutures() throws Exception {
        Plugin plugin = Mockito.mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("SchedulerAdapterConcurrencyTest"));

        FoliaServerHarness harness = new FoliaServerHarness();
        SchedulerAdapter adapter = new SchedulerAdapter(plugin, true, harness.globalScheduler, harness.regionScheduler,
                harness.asyncScheduler);
        assertTrue(adapter.isFoliaDetected());

        int teleports = 2000;
        List<CompletableFuture<Boolean>> futures = new ArrayList<>(teleports);
        World world = Mockito.mock(World.class);
        Object entitySchedulerProxy = createEntitySchedulerProxy(harness.entityScheduler.runs);

        for (int i = 0; i < teleports; i++) {
            Entity entity = Mockito.mock(Entity.class);
            Mockito.doAnswer(invocation -> entitySchedulerProxy).when(entity).getScheduler();
            when(entity.teleport(any(Location.class))).thenReturn(true);
            futures.add(adapter.teleportEntity(entity, new Location(world, i, 64.0, i)));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS);
        assertTrue(futures.stream().allMatch(CompletableFuture::join));
        assertEquals(teleports, harness.entityScheduler.runs.get());
    }

    @Test
    public void testFallsBackToBukkitPathWhenFoliaNotDetected() throws Exception {
        Plugin plugin = Mockito.mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("SchedulerAdapterConcurrencyTest"));

        AtomicInteger bukkitInvocations = new AtomicInteger();

        try (MockedStatic<Bukkit> bukkitMock = Mockito.mockStatic(Bukkit.class)) {
            SchedulerAdapter.setFoliaDetectionOverrideForTests(false);
            bukkitMock.when(Bukkit::getServer).thenReturn(null);
            bukkitMock.when(Bukkit::getScheduler).thenReturn(createBukkitSchedulerProxy(bukkitInvocations));

            SchedulerAdapter adapter = new SchedulerAdapter(plugin);
            assertTrue(!adapter.isFoliaDetected());

            AtomicInteger runs = new AtomicInteger();
            adapter.runGlobal(runs::incrementAndGet);

            assertEquals(1, runs.get());
            assertTrue(bukkitInvocations.get() >= 1);
        }
    }

    static final class FoliaServerHarness {
        final GlobalSchedulerStub globalScheduler = new GlobalSchedulerStub();
        final RegionSchedulerStub regionScheduler = new RegionSchedulerStub();
        final AsyncSchedulerStub asyncScheduler = new AsyncSchedulerStub();
        final EntitySchedulerStub entityScheduler = new EntitySchedulerStub();
    }

    static final class GlobalSchedulerStub {
        final AtomicInteger globalRuns = new AtomicInteger();

        public ScheduledTaskStub execute(Plugin plugin, Runnable runnable) {
            globalRuns.incrementAndGet();
            runnable.run();
            return new ScheduledTaskStub();
        }

        public ScheduledTaskStub runDelayed(Plugin plugin, Consumer<Object> consumer, long delayTicks) {
            globalRuns.incrementAndGet();
            consumer.accept(new Object());
            return new ScheduledTaskStub();
        }

        public ScheduledTaskStub runAtFixedRate(Plugin plugin, Consumer<Object> consumer, long delayTicks, long periodTicks) {
            globalRuns.incrementAndGet();
            consumer.accept(new Object());
            return new ScheduledTaskStub();
        }
    }

    static final class RegionSchedulerStub {
        final AtomicInteger locationRuns = new AtomicInteger();

        public ScheduledTaskStub execute(Plugin plugin, Location loc, Runnable runnable) {
            locationRuns.incrementAndGet();
            runnable.run();
            return new ScheduledTaskStub();
        }

        public ScheduledTaskStub execute(Plugin plugin, World world, int chunkX, int chunkZ, Runnable runnable) {
            locationRuns.incrementAndGet();
            runnable.run();
            return new ScheduledTaskStub();
        }
    }

    static final class AsyncSchedulerStub {
        public ScheduledTaskStub runNow(Plugin plugin, Consumer<Object> consumer) {
            consumer.accept(new Object());
            return new ScheduledTaskStub();
        }

        public ScheduledTaskStub runDelayed(Plugin plugin, Consumer<Object> consumer, long delay, TimeUnit unit) {
            consumer.accept(new Object());
            return new ScheduledTaskStub();
        }

        public ScheduledTaskStub runAtFixedRate(Plugin plugin, Consumer<Object> consumer, long delay, long period, TimeUnit unit) {
            consumer.accept(new Object());
            return new ScheduledTaskStub();
        }
    }

    static final class EntitySchedulerStub {
        final AtomicInteger runs = new AtomicInteger();
    }

    static final class ScheduledTaskStub {
        private boolean cancelled;

        public void cancel() {
            cancelled = true;
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }

    private static Object createBukkitSchedulerProxy(AtomicInteger invocationCount) throws Exception {
        Class<?> schedulerClass = Class.forName("org.bukkit.scheduler." + "BukkitScheduler");
        return java.lang.reflect.Proxy.newProxyInstance(
                schedulerClass.getClassLoader(),
                new Class<?>[] { schedulerClass },
                (proxy, method, args) -> {
                    if (method.getName().startsWith("run") && args != null) {
                        invocationCount.incrementAndGet();
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

    private static Object createEntitySchedulerProxy(AtomicInteger invocationCount) throws Exception {
        Class<?> entitySchedulerClass = Class.forName(
                "io.papermc.paper.threadedregions.scheduler." + "EntityScheduler");
        return java.lang.reflect.Proxy.newProxyInstance(
                entitySchedulerClass.getClassLoader(),
                new Class<?>[] { entitySchedulerClass },
                (proxy, method, args) -> {
                    if ("run".equals(method.getName()) && args != null && args.length > 1 && args[1] instanceof Consumer) {
                        invocationCount.incrementAndGet();
                        @SuppressWarnings("unchecked")
                        Consumer<Object> consumer = (Consumer<Object>) args[1];
                        consumer.accept(new Object());
                    }
                    return null;
                });
    }
}
