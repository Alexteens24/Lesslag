package com.lesslag.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Runtime scheduler bridge using native Paper/Folia API (1.20+). */
public class SchedulerAdapter {

    private final Plugin plugin;

    public SchedulerAdapter(Plugin plugin) {
        this.plugin = plugin;
    }

    public static void init() {
        // No-op in modern Paper API
    }

    // LessLag 1.5+ officially targets Paper/Folia Native API natively.
    // Legacy Bukkit/Spigot schedulers and reflections are completely removed.
    public static boolean isFolia() {
        return true;
    }

    public void runGlobal(Runnable runnable) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, runnable);
    }

    public void runAsync(Runnable runnable) {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> runnable.run());
    }

    public void runDelayed(Runnable runnable, long delayTicks) {
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> runnable.run(), Math.max(1L, delayTicks));
    }

    public void runRepeating(Runnable runnable, long delayTicks, long periodTicks) {
        runRepeatingInternal(runnable, delayTicks, periodTicks);
    }

    public void runAtLocation(Location loc, Runnable runnable) {
        if (loc == null) return;
        Bukkit.getRegionScheduler().execute(plugin, loc, runnable);
    }

    public void runAtEntity(Entity entity, Runnable runnable) {
        if (entity == null) return;
        entity.getScheduler().execute(plugin, runnable, null, 0L);
    }

    public CompletableFuture<Boolean> teleportEntity(Entity entity, Location loc) {
        if (entity == null || loc == null) {
            return CompletableFuture.completedFuture(false);
        }
        return entity.teleportAsync(loc);
    }

    public void runAtChunk(World world, int chunkX, int chunkZ, Runnable runnable) {
        if (world == null) return;
        Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, runnable);
    }

    public static void runGlobal(Plugin plugin, Runnable runnable) {
        new SchedulerAdapter(plugin).runGlobal(runnable);
    }

    public static void runGlobalDelayed(Plugin plugin, Runnable runnable, long delayTicks) {
        new SchedulerAdapter(plugin).runDelayed(runnable, delayTicks);
    }

    public static TaskHandle runGlobalRepeating(Plugin plugin, Runnable runnable, long delayTicks, long periodTicks) {
        return new SchedulerAdapter(plugin).runRepeatingInternal(runnable, delayTicks, periodTicks);
    }

    public static void runAsync(Plugin plugin, Runnable runnable) {
        new SchedulerAdapter(plugin).runAsync(runnable);
    }

    public static void runAsyncDelayed(Plugin plugin, Runnable runnable, long delayTicks) {
        Bukkit.getAsyncScheduler().runDelayed(plugin, task -> runnable.run(), delayTicks * 50L, TimeUnit.MILLISECONDS);
    }

    public static TaskHandle runAsyncRepeating(Plugin plugin, Runnable runnable, long delayTicks, long periodTicks) {
        ScheduledTask task = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, t -> runnable.run(), delayTicks * 50L, periodTicks * 50L, TimeUnit.MILLISECONDS);
        return new TaskHandle(task);
    }

    public static void runAtLocation(Plugin plugin, Location location, Runnable runnable) {
        new SchedulerAdapter(plugin).runAtLocation(location, runnable);
    }

    public static void runAtEntity(Plugin plugin, Entity entity, Runnable runnable) {
        new SchedulerAdapter(plugin).runAtEntity(entity, runnable);
    }

    public static void teleportEntity(Plugin plugin, Entity entity, Location location) {
        new SchedulerAdapter(plugin).teleportEntity(entity, location);
    }

    public static void runAtChunk(Plugin plugin, World world, int chunkX, int chunkZ, Runnable runnable) {
        new SchedulerAdapter(plugin).runAtChunk(world, chunkX, chunkZ, runnable);
    }

    public static void cancelTask(Object taskHandle) {
        if (taskHandle instanceof ScheduledTask) {
            ((ScheduledTask) taskHandle).cancel();
        } else if (taskHandle instanceof TaskHandle) {
            ((TaskHandle) taskHandle).cancel();
        }
    }

    private TaskHandle runRepeatingInternal(Runnable runnable, long delayTicks, long periodTicks) {
        ScheduledTask task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> runnable.run(), Math.max(1L, delayTicks), Math.max(1L, periodTicks));
        return new TaskHandle(task);
    }

    public static final class TaskHandle {
        private final ScheduledTask rawHandle;

        public TaskHandle(ScheduledTask rawHandle) {
            this.rawHandle = rawHandle;
        }

        public void cancel() {
            if (rawHandle != null) {
                rawHandle.cancel();
            }
        }

        public boolean isCancelled() {
            return rawHandle == null || rawHandle.isCancelled();
        }
    }
}
