package com.lesslag.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Scheduler abstraction layer providing a single API surface for both
 * Folia (regionised schedulers) and traditional Bukkit/Paper/Spigot.
 *
 * <p>All Folia APIs are accessed exclusively through reflection — no
 * compile-time dependency on Folia is required.</p>
 *
 * <p>On Folia:
 * <ul>
 *   <li>Global tasks run on the global region scheduler</li>
 *   <li>Async tasks run on the async scheduler</li>
 *   <li>Entity tasks run on the entity's owning region</li>
 *   <li>Location/chunk tasks run on the region that owns that location/chunk</li>
 * </ul>
 *
 * <p>On Bukkit/Paper/Spigot all tasks route through {@code BukkitScheduler}.</p>
 */
public final class SchedulerAdapter {

    // ═══════════════════════════════════════════
    //  Runtime Detection
    // ═══════════════════════════════════════════

    private static final boolean FOLIA;

    static {
        boolean folia;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (ClassNotFoundException e) {
            folia = false;
        }
        FOLIA = folia;
    }

    // ═══════════════════════════════════════════
    //  Cached Reflection Handles (initialised lazily)
    // ═══════════════════════════════════════════

    private static volatile boolean initialized = false;

    // Scheduler singleton instances
    private static Object globalScheduler;
    private static Object regionScheduler;
    private static Object asyncScheduler;

    // GlobalRegionScheduler
    private static Method grsExecute;          // execute(Plugin, Runnable)
    private static Method grsRunDelayed;       // runDelayed(Plugin, Consumer, long)
    private static Method grsRunAtFixedRate;   // runAtFixedRate(Plugin, Consumer, long, long)

    // RegionScheduler
    private static Method rsExecuteLoc;        // execute(Plugin, Location, Runnable)
    private static Method rsExecuteChunk;      // execute(Plugin, World, int, int, Runnable)

    // AsyncScheduler
    private static Method asRunNow;            // runNow(Plugin, Consumer)
    private static Method asRunDelayed;        // runDelayed(Plugin, Consumer, long, TimeUnit)
    private static Method asRunAtFixedRate;    // runAtFixedRate(Plugin, Consumer, long, long, TimeUnit)

    // EntityScheduler
    private static Method entityGetScheduler;  // Entity.getScheduler()
    private static Method esRun;               // run(Plugin, Consumer, Runnable)

    // ScheduledTask cancel/isCancelled
    private static Method stCancel;
    private static Method stIsCancelled;

    // Paper async teleport (if available)
    private static Method entityTeleportAsync;

    private SchedulerAdapter() {}

    /**
     * Whether the server is running Folia.
     */
    public static boolean isFolia() {
        return FOLIA;
    }

    /**
     * Initialise Folia reflection handles.
     * Called automatically on first use; may also be called explicitly during
     * plugin enable for eager initialisation.
     */
    public static void init() {
        if (!FOLIA || initialized) return;
        synchronized (SchedulerAdapter.class) {
            if (initialized) return;
            try {
                Object server = Bukkit.getServer();

                // ── Obtain scheduler singletons ──
                globalScheduler = server.getClass().getMethod("getGlobalRegionScheduler").invoke(server);
                regionScheduler = server.getClass().getMethod("getRegionScheduler").invoke(server);
                asyncScheduler  = server.getClass().getMethod("getAsyncScheduler").invoke(server);

                // ── GlobalRegionScheduler methods (use interface class for stability) ──
                Class<?> grsCls = Class.forName(
                        "io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
                grsExecute        = grsCls.getMethod("execute", Plugin.class, Runnable.class);
                grsRunDelayed     = grsCls.getMethod("runDelayed",
                        Plugin.class, Consumer.class, long.class);
                grsRunAtFixedRate = grsCls.getMethod("runAtFixedRate",
                        Plugin.class, Consumer.class, long.class, long.class);

                // ── RegionScheduler methods ──
                Class<?> rsCls = Class.forName(
                        "io.papermc.paper.threadedregions.scheduler.RegionScheduler");
                rsExecuteLoc   = rsCls.getMethod("execute",
                        Plugin.class, Location.class, Runnable.class);
                rsExecuteChunk = rsCls.getMethod("execute",
                        Plugin.class, World.class, int.class, int.class, Runnable.class);

                // ── AsyncScheduler methods ──
                Class<?> asCls = Class.forName(
                        "io.papermc.paper.threadedregions.scheduler.AsyncScheduler");
                asRunNow         = asCls.getMethod("runNow",
                        Plugin.class, Consumer.class);
                asRunDelayed     = asCls.getMethod("runDelayed",
                        Plugin.class, Consumer.class, long.class, TimeUnit.class);
                asRunAtFixedRate = asCls.getMethod("runAtFixedRate",
                        Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class);

                // ── EntityScheduler ──
                entityGetScheduler = Entity.class.getMethod("getScheduler");
                Class<?> esCls = Class.forName(
                        "io.papermc.paper.threadedregions.scheduler.EntityScheduler");
                esRun = esCls.getMethod("run",
                        Plugin.class, Consumer.class, Runnable.class);

                // ── ScheduledTask ──
                Class<?> stCls = Class.forName(
                        "io.papermc.paper.threadedregions.scheduler.ScheduledTask");
                stCancel      = stCls.getMethod("cancel");
                stIsCancelled = stCls.getMethod("isCancelled");

                initialized = true;
            } catch (Exception e) {
                Bukkit.getLogger().log(Level.SEVERE,
                        "[LessLag] Failed to initialise Folia scheduler reflection", e);
            }
        }

        // Paper async teleport (available on both Paper and Folia, not Spigot)
        try {
            entityTeleportAsync = Entity.class.getMethod("teleportAsync", Location.class);
        } catch (NoSuchMethodException ignored) {
            // Not available (Spigot)
        }
    }

    private static void ensureInit() {
        if (FOLIA && !initialized) init();
    }

    // ═══════════════════════════════════════════
    //  TaskHandle — wraps BukkitTask or ScheduledTask
    // ═══════════════════════════════════════════

    /**
     * Universal handle for scheduled tasks that can be cancelled.
     * Works with both {@code BukkitTask} and Folia's {@code ScheduledTask}.
     */
    public static final class TaskHandle {
        private final Object handle;

        public TaskHandle(Object handle) {
            this.handle = handle;
        }

        /**
         * Cancel the scheduled task.
         */
        public void cancel() {
            if (handle == null) return;
            try {
                if (FOLIA && stCancel != null) {
                    stCancel.invoke(handle);
                } else if (handle instanceof BukkitTask) {
                    ((BukkitTask) handle).cancel();
                }
            } catch (Exception ignored) {}
        }

        /**
         * Check if the task has been cancelled.
         */
        public boolean isCancelled() {
            if (handle == null) return true;
            try {
                if (FOLIA && stIsCancelled != null) {
                    return (boolean) stIsCancelled.invoke(handle);
                } else if (handle instanceof BukkitTask) {
                    return ((BukkitTask) handle).isCancelled();
                }
            } catch (Exception ignored) {}
            return true;
        }
    }

    // ═══════════════════════════════════════════
    //  Global (main-thread / global-region) scheduling
    // ═══════════════════════════════════════════

    /**
     * Run a task on the global region thread (Folia) or main thread (Bukkit).
     * <p>Replaces: {@code Bukkit.getScheduler().runTask(plugin, task)}</p>
     */
    public static void runGlobal(Plugin plugin, Runnable task) {
        ensureInit();
        if (FOLIA && grsExecute != null) {
            try {
                grsExecute.invoke(globalScheduler, plugin, task);
                return;
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "[SchedulerAdapter] Folia global execute failed", e);
            }
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }

    /**
     * Run a task on the global region thread after a delay (in ticks).
     * <p>Replaces: {@code Bukkit.getScheduler().runTaskLater(plugin, task, delay)}</p>
     */
    public static void runGlobalDelayed(Plugin plugin, Runnable task, long delayTicks) {
        ensureInit();
        if (FOLIA && grsRunDelayed != null) {
            try {
                grsRunDelayed.invoke(globalScheduler, plugin,
                        (Consumer<Object>) t -> task.run(),
                        Math.max(1, delayTicks));
                return;
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "[SchedulerAdapter] Folia global delayed failed", e);
            }
        }
        Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
    }

    /**
     * Run a repeating task on the global region thread (ticks).
     * <p>Replaces: {@code Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period)}</p>
     *
     * @return a {@link TaskHandle} that can be used to cancel the task
     */
    public static TaskHandle runGlobalRepeating(Plugin plugin, Runnable task,
                                                 long delayTicks, long periodTicks) {
        ensureInit();
        if (FOLIA && grsRunAtFixedRate != null) {
            try {
                Object st = grsRunAtFixedRate.invoke(globalScheduler, plugin,
                        (Consumer<Object>) t -> task.run(),
                        Math.max(1, delayTicks),
                        Math.max(1, periodTicks));
                return new TaskHandle(st);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "[SchedulerAdapter] Folia global repeating failed", e);
            }
        }
        BukkitTask bt = Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
        return new TaskHandle(bt);
    }

    // ═══════════════════════════════════════════
    //  Async scheduling
    // ═══════════════════════════════════════════

    /**
     * Run a task asynchronously.
     * <p>Replaces: {@code Bukkit.getScheduler().runTaskAsynchronously(plugin, task)}</p>
     */
    public static void runAsync(Plugin plugin, Runnable task) {
        ensureInit();
        if (FOLIA && asRunNow != null) {
            try {
                asRunNow.invoke(asyncScheduler, plugin,
                        (Consumer<Object>) t -> task.run());
                return;
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "[SchedulerAdapter] Folia async runNow failed", e);
            }
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    /**
     * Run a task asynchronously after a delay (in ticks, converted to ms for Folia).
     * <p>Replaces: {@code Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delay)}</p>
     */
    public static void runAsyncDelayed(Plugin plugin, Runnable task, long delayTicks) {
        ensureInit();
        if (FOLIA && asRunDelayed != null) {
            try {
                asRunDelayed.invoke(asyncScheduler, plugin,
                        (Consumer<Object>) t -> task.run(),
                        Math.max(1, delayTicks * 50L),
                        TimeUnit.MILLISECONDS);
                return;
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "[SchedulerAdapter] Folia async delayed failed", e);
            }
        }
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delayTicks);
    }

    /**
     * Run a repeating async task (tick-based, converted to ms for Folia).
     * <p>Replaces: {@code Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delay, period)}</p>
     *
     * @return a {@link TaskHandle} that can be used to cancel the task
     */
    public static TaskHandle runAsyncRepeating(Plugin plugin, Runnable task,
                                                long delayTicks, long periodTicks) {
        ensureInit();
        if (FOLIA && asRunAtFixedRate != null) {
            try {
                Object st = asRunAtFixedRate.invoke(asyncScheduler, plugin,
                        (Consumer<Object>) t -> task.run(),
                        Math.max(1, delayTicks * 50L),
                        Math.max(1, periodTicks * 50L),
                        TimeUnit.MILLISECONDS);
                return new TaskHandle(st);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "[SchedulerAdapter] Folia async repeating failed", e);
            }
        }
        BukkitTask bt = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, task, delayTicks, periodTicks);
        return new TaskHandle(bt);
    }

    // ═══════════════════════════════════════════
    //  Region-specific scheduling (Folia)
    // ═══════════════════════════════════════════

    /**
     * Run a task on the region thread that owns the given location.
     * On Bukkit/Paper: runs on the main thread.
     */
    public static void runAtLocation(Plugin plugin, Location location, Runnable task) {
        ensureInit();
        if (FOLIA && rsExecuteLoc != null) {
            try {
                rsExecuteLoc.invoke(regionScheduler, plugin, location, task);
                return;
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "[SchedulerAdapter] Folia region execute (location) failed", e);
            }
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }

    /**
     * Run a task on the region thread that owns the given chunk.
     * On Bukkit/Paper: runs on the main thread.
     */
    public static void runAtChunk(Plugin plugin, World world, int chunkX, int chunkZ,
                                   Runnable task) {
        ensureInit();
        if (FOLIA && rsExecuteChunk != null) {
            try {
                rsExecuteChunk.invoke(regionScheduler, plugin, world, chunkX, chunkZ, task);
                return;
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "[SchedulerAdapter] Folia region execute (chunk) failed", e);
            }
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }

    /**
     * Run a task on the entity's owning region thread.
     * On Bukkit/Paper: runs on the main thread.
     * If the entity has been removed (retired), the task is silently skipped.
     */
    public static void runAtEntity(Plugin plugin, Entity entity, Runnable task) {
        ensureInit();
        if (FOLIA && entityGetScheduler != null && esRun != null) {
            try {
                Object es = entityGetScheduler.invoke(entity);
                esRun.invoke(es, plugin,
                        (Consumer<Object>) t -> task.run(),
                        (Runnable) null);
                return;
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "[SchedulerAdapter] Folia entity run failed", e);
            }
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }

    // ═══════════════════════════════════════════
    //  Teleportation
    // ═══════════════════════════════════════════

    /**
     * Teleport an entity safely across all server implementations.
     * <ul>
     *   <li>Folia: dispatches to entity scheduler → {@code entity.teleport(location)}</li>
     *   <li>Paper: uses {@code teleportAsync(Location)} if available</li>
     *   <li>Spigot: uses synchronous {@code teleport(Location)}</li>
     * </ul>
     */
    public static void teleportEntity(Plugin plugin, Entity entity, Location location) {
        ensureInit();
        if (FOLIA) {
            runAtEntity(plugin, entity, () -> entity.teleport(location));
            return;
        }
        // Paper: try teleportAsync, fallback to sync
        if (entityTeleportAsync != null) {
            try {
                entityTeleportAsync.invoke(entity, location);
                return;
            } catch (Exception ignored) {}
        }
        entity.teleport(location);
    }

    // ═══════════════════════════════════════════
    //  Utility
    // ═══════════════════════════════════════════

    /**
     * Cancel a raw task handle (works for both {@code BukkitTask} and
     * Folia's {@code ScheduledTask}).
     */
    public static void cancelTask(Object taskHandle) {
        if (taskHandle == null) return;
        try {
            if (FOLIA && stCancel != null) {
                stCancel.invoke(taskHandle);
            } else if (taskHandle instanceof BukkitTask) {
                ((BukkitTask) taskHandle).cancel();
            }
        } catch (Exception ignored) {}
    }
}
