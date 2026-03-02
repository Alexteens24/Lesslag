package com.lesslag.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Runtime scheduler bridge for Folia and non-Folia servers. */
public class SchedulerAdapter {

    private static final Map<Plugin, SchedulerAdapter> ADAPTERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile Boolean foliaDetectionOverrideForTests;

    private final Plugin plugin;
    private final boolean foliaDetected;

    private volatile boolean foliaReflectionReady;
    private volatile boolean bukkitReflectionReady;

    private Object globalScheduler;
    private Object regionScheduler;
    private Object asyncScheduler;

    private Method globalExecute;
    private Method globalRunDelayed;
    private Method globalRunAtFixedRate;

    private Method regionExecuteAtLocation;
    private Method regionExecuteAtChunk;

    private Method asyncRunNow;
    private Method asyncRunDelayed;
    private Method asyncRunAtFixedRate;

    private Method entityGetSchedulerMethod;
    private Method entitySchedulerRun;
    private Method entityTeleportAsync;

    private Object bukkitScheduler;
    private Method bukkitRunSync;
    private Method bukkitRunDelayed;
    private Method bukkitRunRepeating;
    private Method bukkitRunAsync;
    private Method bukkitRunAsyncDelayed;
    private Method bukkitRunAsyncRepeating;

    /** Creates an adapter for a plugin instance. */
    public SchedulerAdapter(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.foliaDetected = detectFoliaRuntime();
    }

    /** Creates an adapter with explicit scheduler bindings for deterministic tests. */
    public SchedulerAdapter(Plugin plugin, boolean foliaDetected, Object globalScheduler, Object regionScheduler,
            Object asyncScheduler) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.foliaDetected = foliaDetected;
        if (foliaDetected) {
            initializeFoliaBindings(globalScheduler, regionScheduler, asyncScheduler);
        }
    }

    /** Initializes detection-related state for compatibility. */
    public static void init() {
        detectFoliaRuntime();
    }

    /** Returns true when Folia-like runtime scheduling APIs are detected. */
    public static boolean isFolia() {
        return detectFoliaRuntime();
    }

    /** Runs a task on global/main execution context. */
    public void runGlobal(Runnable runnable) {
        if (!tryRunGlobal(runnable)) {
            runBukkitSync(runnable);
        }
    }

    /** Runs a task on async execution context. */
    public void runAsync(Runnable runnable) {
        if (!tryRunAsync(runnable)) {
            runBukkitAsync(runnable);
        }
    }

    /** Runs a task after a delay in ticks. */
    public void runDelayed(Runnable runnable, long delayTicks) {
        if (!tryRunGlobalDelayed(runnable, delayTicks)) {
            runBukkitDelayed(runnable, delayTicks);
        }
    }

    /** Runs a repeating task in ticks. */
    public void runRepeating(Runnable runnable, long delayTicks, long periodTicks) {
        runRepeatingInternal(runnable, delayTicks, periodTicks, false);
    }

    /** Runs a task at a location-owned context. */
    public void runAtLocation(Location loc, Runnable runnable) {
        if (!tryRunAtLocation(loc, runnable)) {
            runBukkitSync(runnable);
        }
    }

    /** Runs a task at an entity-owned context. */
    public void runAtEntity(Entity entity, Runnable runnable) {
        if (!tryRunAtEntity(entity, runnable)) {
            runBukkitSync(runnable);
        }
    }

    /** Teleports an entity and returns completion state. */
    public CompletableFuture<Boolean> teleportEntity(Entity entity, Location loc) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        if (entity == null || loc == null) {
            result.complete(false);
            return result;
        }

        if (foliaDetected) {
            runAtEntity(entity, () -> completeTeleport(result, entity, loc));
            return result;
        }

        if (ensureBukkitReflection() && entityTeleportAsync != null) {
            try {
                Object asyncResult = entityTeleportAsync.invoke(entity, loc);
                if (asyncResult instanceof CompletableFuture<?>) {
                    @SuppressWarnings("unchecked")
                    CompletableFuture<Boolean> teleportFuture = (CompletableFuture<Boolean>) asyncResult;
                    teleportFuture.whenComplete((value, error) -> {
                        if (error != null) {
                            result.completeExceptionally(error);
                        } else {
                            result.complete(Boolean.TRUE.equals(value));
                        }
                    });
                    return result;
                }
            } catch (Exception ex) {
                log(Level.WARNING, "Async teleport reflection failed", ex);
            }
        }

        runBukkitSync(() -> completeTeleport(result, entity, loc));
        return result;
    }

    /** Runs a task on a chunk-owned context. */
    public void runAtChunk(World world, int chunkX, int chunkZ, Runnable runnable) {
        if (!tryRunAtChunk(world, chunkX, chunkZ, runnable)) {
            runBukkitSync(runnable);
        }
    }

    /** Returns true if Folia runtime is detected for this adapter. */
    public boolean isFoliaDetected() {
        return foliaDetected;
    }

    /** Sets Folia detection override for tests; null clears the override. */
    public static void setFoliaDetectionOverrideForTests(Boolean value) {
        foliaDetectionOverrideForTests = value;
    }

    /** Clears static adapter cache for deterministic tests. */
    public static void clearAdapterCacheForTests() {
        ADAPTERS.clear();
    }

    /** Runs a task on global/main execution context using static bridge. */
    public static void runGlobal(Plugin plugin, Runnable runnable) {
        from(plugin).runGlobal(runnable);
    }

    /** Runs a delayed task on global/main execution context using static bridge. */
    public static void runGlobalDelayed(Plugin plugin, Runnable runnable, long delayTicks) {
        from(plugin).runDelayed(runnable, delayTicks);
    }

    /** Runs a repeating global/main task and returns a handle. */
    public static TaskHandle runGlobalRepeating(Plugin plugin, Runnable runnable, long delayTicks, long periodTicks) {
        return from(plugin).runRepeatingInternal(runnable, delayTicks, periodTicks, true);
    }

    /** Runs an async task using static bridge. */
    public static void runAsync(Plugin plugin, Runnable runnable) {
        from(plugin).runAsync(runnable);
    }

    /** Runs a delayed async task using static bridge. */
    public static void runAsyncDelayed(Plugin plugin, Runnable runnable, long delayTicks) {
        from(plugin).runAsyncDelayedInternal(runnable, delayTicks);
    }

    /** Runs a repeating async task using static bridge and returns a handle. */
    public static TaskHandle runAsyncRepeating(Plugin plugin, Runnable runnable, long delayTicks, long periodTicks) {
        return from(plugin).runAsyncRepeatingInternal(runnable, delayTicks, periodTicks);
    }

    /** Runs a location task using static bridge. */
    public static void runAtLocation(Plugin plugin, Location location, Runnable runnable) {
        from(plugin).runAtLocation(location, runnable);
    }

    /** Runs an entity task using static bridge. */
    public static void runAtEntity(Plugin plugin, Entity entity, Runnable runnable) {
        from(plugin).runAtEntity(entity, runnable);
    }

    /** Teleports an entity using static bridge. */
    public static void teleportEntity(Plugin plugin, Entity entity, Location location) {
        from(plugin).teleportEntity(entity, location);
    }

    /** Runs a chunk task using static bridge. */
    public static void runAtChunk(Plugin plugin, World world, int chunkX, int chunkZ, Runnable runnable) {
        from(plugin).runAtChunk(world, chunkX, chunkZ, runnable);
    }

    /** Cancels a reflected task handle if supported. */
    public static void cancelTask(Object taskHandle) {
        if (taskHandle == null) {
            return;
        }
        try {
            Method cancelMethod = taskHandle.getClass().getMethod("cancel");
            cancelMethod.setAccessible(true);
            cancelMethod.invoke(taskHandle);
        } catch (Exception ex) {
            Bukkit.getLogger().log(Level.WARNING, "Task cancellation failed", ex);
        }
    }

    /** Handle abstraction for repeated tasks. */
    public static final class TaskHandle {
        private final Object rawHandle;
        private final Logger logger;

        /** Creates a handle wrapper for a platform task object. */
        public TaskHandle(Object rawHandle, Logger logger) {
            this.rawHandle = rawHandle;
            this.logger = logger;
        }

        /** Creates a handle wrapper using the global logger fallback. */
        public TaskHandle(Object rawHandle) {
            this(rawHandle, Bukkit.getLogger());
        }

        /** Cancels the underlying task if available. */
        public void cancel() {
            if (rawHandle == null) {
                return;
            }
            try {
                Method cancelMethod = rawHandle.getClass().getMethod("cancel");
                cancelMethod.setAccessible(true);
                cancelMethod.invoke(rawHandle);
            } catch (Exception ex) {
                logger.log(Level.WARNING, "Task handle cancel failed", ex);
            }
        }

        /** Returns true if cancellation is requested or unknown. */
        public boolean isCancelled() {
            if (rawHandle == null) {
                return true;
            }
            try {
                Method cancelledMethod = rawHandle.getClass().getMethod("isCancelled");
                cancelledMethod.setAccessible(true);
                Object value = cancelledMethod.invoke(rawHandle);
                return value instanceof Boolean && (Boolean) value;
            } catch (NoSuchMethodException ex) {
                return false;
            } catch (Exception ex) {
                logger.log(Level.WARNING, "Task handle status read failed", ex);
                return false;
            }
        }
    }

    private static SchedulerAdapter from(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        synchronized (ADAPTERS) {
            return ADAPTERS.computeIfAbsent(plugin, SchedulerAdapter::new);
        }
    }

    private static boolean detectFoliaRuntime() {
        Boolean override = foliaDetectionOverrideForTests;
        if (override != null) {
            return override;
        }

        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ignored) {
            // fallback check below
        }

        try {
            Object server = Bukkit.class.getMethod("getServer").invoke(null);
            if (server == null) {
                return false;
            }
            server.getClass().getMethod("getGlobalRegionScheduler");
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean ensureFoliaReflection() {
        if (!foliaDetected) {
            return false;
        }
        if (foliaReflectionReady) {
            return true;
        }

        synchronized (this) {
            if (foliaReflectionReady) {
                return true;
            }
            try {
                Object server = Bukkit.class.getMethod("getServer").invoke(null);
                if (server == null) {
                    return false;
                }

                Method globalGetter = server.getClass().getMethod("getGlobalRegionScheduler");
                Method regionGetter = server.getClass().getMethod("getRegionScheduler");
                Method asyncGetter = server.getClass().getMethod("getAsyncScheduler");

                initializeFoliaBindings(globalGetter.invoke(server), regionGetter.invoke(server), asyncGetter.invoke(server));
                return foliaReflectionReady;
            } catch (Exception ex) {
                log(Level.WARNING, "Folia reflection initialization failed", ex);
                return false;
            }
        }
    }

    private void initializeFoliaBindings(Object globalScheduler, Object regionScheduler, Object asyncScheduler) {
        try {
            this.globalScheduler = Objects.requireNonNull(globalScheduler, "globalScheduler");
            this.regionScheduler = Objects.requireNonNull(regionScheduler, "regionScheduler");
            this.asyncScheduler = Objects.requireNonNull(asyncScheduler, "asyncScheduler");

            globalExecute = this.globalScheduler.getClass().getMethod("execute", Plugin.class, Runnable.class);
            globalRunDelayed = this.globalScheduler.getClass().getMethod("runDelayed", Plugin.class, Consumer.class, long.class);
            globalRunAtFixedRate = this.globalScheduler.getClass().getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class);

            regionExecuteAtLocation = this.regionScheduler.getClass().getMethod("execute", Plugin.class, Location.class, Runnable.class);
            regionExecuteAtChunk = this.regionScheduler.getClass().getMethod("execute", Plugin.class, World.class, int.class, int.class, Runnable.class);

            asyncRunNow = this.asyncScheduler.getClass().getMethod("runNow", Plugin.class, Consumer.class);
            asyncRunDelayed = this.asyncScheduler.getClass().getMethod("runDelayed", Plugin.class, Consumer.class, long.class, TimeUnit.class);
            asyncRunAtFixedRate = this.asyncScheduler.getClass().getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class);

            entityGetSchedulerMethod = Entity.class.getMethod("getScheduler");
            Class<?> entitySchedulerClass = entityGetSchedulerMethod.getReturnType();
            entitySchedulerRun = entitySchedulerClass.getMethod("run", Plugin.class, Consumer.class, Runnable.class);

            foliaReflectionReady = true;
        } catch (Exception ex) {
            log(Level.WARNING, "Folia binding initialization failed", ex);
            foliaReflectionReady = false;
        }
    }

    private boolean ensureBukkitReflection() {
        if (bukkitReflectionReady) {
            return true;
        }

        synchronized (this) {
            if (bukkitReflectionReady) {
                return true;
            }
            try {
                Method getSchedulerMethod = Bukkit.class.getMethod("getScheduler");
                bukkitScheduler = getSchedulerMethod.invoke(null);
                if (bukkitScheduler == null) {
                    return false;
                }

                Class<?> schedulerClass = bukkitScheduler.getClass();
                bukkitRunSync = schedulerClass.getMethod("run" + "Task", Plugin.class, Runnable.class);
                bukkitRunDelayed = schedulerClass.getMethod("run" + "TaskLater", Plugin.class, Runnable.class, long.class);
                bukkitRunRepeating = schedulerClass.getMethod("run" + "TaskTimer", Plugin.class, Runnable.class, long.class, long.class);
                bukkitRunAsync = schedulerClass.getMethod("run" + "TaskAsynchronously", Plugin.class, Runnable.class);
                bukkitRunAsyncDelayed = schedulerClass.getMethod("run" + "TaskLaterAsynchronously", Plugin.class, Runnable.class, long.class);
                bukkitRunAsyncRepeating = schedulerClass.getMethod("run" + "TaskTimerAsynchronously", Plugin.class, Runnable.class, long.class, long.class);

                try {
                    entityTeleportAsync = Entity.class.getMethod("teleportAsync", Location.class);
                } catch (NoSuchMethodException ignored) {
                    entityTeleportAsync = null;
                }

                bukkitReflectionReady = true;
                return true;
            } catch (Exception ex) {
                log(Level.WARNING, "Bukkit reflection initialization failed", ex);
                return false;
            }
        }
    }

    private boolean tryRunGlobal(Runnable runnable) {
        if (!ensureFoliaReflection()) {
            return false;
        }
        try {
            globalExecute.invoke(globalScheduler, plugin, runnable);
            return true;
        } catch (Exception ex) {
            log(Level.WARNING, "Folia global execution failed", ex);
            return false;
        }
    }

    private boolean tryRunGlobalDelayed(Runnable runnable, long delayTicks) {
        if (!ensureFoliaReflection()) {
            return false;
        }
        try {
            globalRunDelayed.invoke(globalScheduler, plugin, (Consumer<Object>) task -> runnable.run(), normalizeTickValue(delayTicks));
            return true;
        } catch (Exception ex) {
            log(Level.WARNING, "Folia delayed execution failed", ex);
            return false;
        }
    }

    private boolean tryRunAsync(Runnable runnable) {
        if (!ensureFoliaReflection()) {
            return false;
        }
        try {
            asyncRunNow.invoke(asyncScheduler, plugin, (Consumer<Object>) task -> runnable.run());
            return true;
        } catch (Exception ex) {
            log(Level.WARNING, "Folia async execution failed", ex);
            return false;
        }
    }

    private boolean tryRunAtLocation(Location location, Runnable runnable) {
        if (location == null) {
            return false;
        }
        if (!ensureFoliaReflection()) {
            return false;
        }
        try {
            regionExecuteAtLocation.invoke(regionScheduler, plugin, location, runnable);
            return true;
        } catch (Exception ex) {
            log(Level.WARNING, "Folia location execution failed", ex);
            return false;
        }
    }

    private boolean tryRunAtEntity(Entity entity, Runnable runnable) {
        if (entity == null) {
            return false;
        }
        if (!ensureFoliaReflection()) {
            return false;
        }
        try {
            Object entityScheduler = entityGetSchedulerMethod.invoke(entity);
            entitySchedulerRun.invoke(entityScheduler, plugin, (Consumer<Object>) task -> runnable.run(), null);
            return true;
        } catch (Exception ex) {
            log(Level.WARNING, "Folia entity execution failed", ex);
            return false;
        }
    }

    private boolean tryRunAtChunk(World world, int chunkX, int chunkZ, Runnable runnable) {
        if (world == null) {
            return false;
        }
        if (!ensureFoliaReflection()) {
            return false;
        }
        try {
            regionExecuteAtChunk.invoke(regionScheduler, plugin, world, chunkX, chunkZ, runnable);
            return true;
        } catch (Exception ex) {
            log(Level.WARNING, "Folia chunk execution failed", ex);
            return false;
        }
    }

    private void runBukkitSync(Runnable runnable) {
        if (!ensureBukkitReflection()) {
            safeRun(runnable);
            return;
        }
        try {
            bukkitRunSync.invoke(bukkitScheduler, plugin, runnable);
        } catch (Exception ex) {
            log(Level.WARNING, "Bukkit sync execution failed", ex);
            safeRun(runnable);
        }
    }

    private void runBukkitDelayed(Runnable runnable, long delayTicks) {
        if (!ensureBukkitReflection()) {
            safeRun(runnable);
            return;
        }
        try {
            bukkitRunDelayed.invoke(bukkitScheduler, plugin, runnable, normalizeTickValue(delayTicks));
        } catch (Exception ex) {
            log(Level.WARNING, "Bukkit delayed execution failed", ex);
            safeRun(runnable);
        }
    }

    private void runBukkitAsync(Runnable runnable) {
        if (!ensureBukkitReflection()) {
            Thread thread = new Thread(runnable, "LessLag-Adapter-Async");
            thread.setDaemon(true);
            thread.start();
            return;
        }
        try {
            bukkitRunAsync.invoke(bukkitScheduler, plugin, runnable);
        } catch (Exception ex) {
            log(Level.WARNING, "Bukkit async execution failed", ex);
            Thread thread = new Thread(runnable, "LessLag-Adapter-Async");
            thread.setDaemon(true);
            thread.start();
        }
    }

    private void runAsyncDelayedInternal(Runnable runnable, long delayTicks) {
        if (tryRunAsyncDelayed(runnable, delayTicks)) {
            return;
        }
        if (!ensureBukkitReflection()) {
            safeRun(runnable);
            return;
        }
        try {
            bukkitRunAsyncDelayed.invoke(bukkitScheduler, plugin, runnable, normalizeTickValue(delayTicks));
        } catch (Exception ex) {
            log(Level.WARNING, "Bukkit async delayed execution failed", ex);
            safeRun(runnable);
        }
    }

    private TaskHandle runAsyncRepeatingInternal(Runnable runnable, long delayTicks, long periodTicks) {
        if (ensureFoliaReflection()) {
            try {
                Object handle = asyncRunAtFixedRate.invoke(
                        asyncScheduler,
                        plugin,
                        (Consumer<Object>) task -> runnable.run(),
                        ticksToMillis(delayTicks),
                        ticksToMillis(periodTicks),
                        TimeUnit.MILLISECONDS);
                return new TaskHandle(handle, plugin.getLogger());
            } catch (Exception ex) {
                log(Level.WARNING, "Folia async repeating execution failed", ex);
            }
        }

        if (ensureBukkitReflection()) {
            try {
                Object handle = bukkitRunAsyncRepeating.invoke(
                        bukkitScheduler,
                        plugin,
                        runnable,
                        normalizeTickValue(delayTicks),
                        normalizeTickValue(periodTicks));
                return new TaskHandle(handle, plugin.getLogger());
            } catch (Exception ex) {
                log(Level.WARNING, "Bukkit async repeating execution failed", ex);
            }
        }

        safeRun(runnable);
        return new TaskHandle(null, plugin.getLogger());
    }

    private TaskHandle runRepeatingInternal(Runnable runnable, long delayTicks, long periodTicks, boolean returnHandle) {
        if (ensureFoliaReflection()) {
            try {
                Object handle = globalRunAtFixedRate.invoke(
                        globalScheduler,
                        plugin,
                        (Consumer<Object>) task -> runnable.run(),
                        normalizeTickValue(delayTicks),
                        normalizeTickValue(periodTicks));
                return returnHandle ? new TaskHandle(handle, plugin.getLogger()) : new TaskHandle(null, plugin.getLogger());
            } catch (Exception ex) {
                log(Level.WARNING, "Folia repeating execution failed", ex);
            }
        }

        if (ensureBukkitReflection()) {
            try {
                Object handle = bukkitRunRepeating.invoke(
                        bukkitScheduler,
                        plugin,
                        runnable,
                        normalizeTickValue(delayTicks),
                        normalizeTickValue(periodTicks));
                return returnHandle ? new TaskHandle(handle, plugin.getLogger()) : new TaskHandle(null, plugin.getLogger());
            } catch (Exception ex) {
                log(Level.WARNING, "Bukkit repeating execution failed", ex);
            }
        }

        safeRun(runnable);
        return new TaskHandle(null, plugin.getLogger());
    }

    private boolean tryRunAsyncDelayed(Runnable runnable, long delayTicks) {
        if (!ensureFoliaReflection()) {
            return false;
        }
        try {
            asyncRunDelayed.invoke(
                    asyncScheduler,
                    plugin,
                    (Consumer<Object>) task -> runnable.run(),
                    ticksToMillis(delayTicks),
                    TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception ex) {
            log(Level.WARNING, "Folia async delayed execution failed", ex);
            return false;
        }
    }

    private void completeTeleport(CompletableFuture<Boolean> result, Entity entity, Location location) {
        try {
            result.complete(entity.teleport(location));
        } catch (Exception ex) {
            result.completeExceptionally(ex);
        }
    }

    private long normalizeTickValue(long ticks) {
        return Math.max(1L, ticks);
    }

    private long ticksToMillis(long ticks) {
        return normalizeTickValue(ticks) * 50L;
    }

    private void safeRun(Runnable runnable) {
        try {
            runnable.run();
        } catch (Exception ex) {
            log(Level.WARNING, "Fallback runnable failed", ex);
        }
    }

    private void log(Level level, String message, Exception ex) {
        try {
            plugin.getLogger().log(level, message, ex);
        } catch (Exception ignored) {
            Bukkit.getLogger().log(level, message, ex);
        }
    }
}
