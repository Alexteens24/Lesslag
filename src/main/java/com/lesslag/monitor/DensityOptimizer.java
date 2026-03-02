package com.lesslag.monitor;

import com.lesslag.LessLag;
import com.lesslag.util.SchedulerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.*;

import java.util.*;

public class DensityOptimizer {

    private final LessLag plugin;
    private boolean enabled;
    private int checkInterval;
    private Map<EntityType, Integer> limits;
    private boolean bypassTamed;
    private boolean bypassNamed;
    private boolean bypassLeashed;
    private SchedulerAdapter.TaskHandle task;

    // ── Incremental scan state ──
    // Instead of scanning all chunks in one tick, we spread the work across multiple ticks.
    private int scanCursor = 0;            // Current position in the chunk list
    private Chunk[] pendingChunks = null;   // Snapshot of loaded chunks for current scan pass
    private int pendingWorldIndex = 0;     // Current world index for multi-world iteration

    public DensityOptimizer(LessLag plugin) {
        this.plugin = plugin;
        this.limits = new HashMap<>();
        reloadConfig();
    }

    private int scalingLimit = -1;

    public void setScalingLimit(int limit) {
        this.scalingLimit = limit;
    }

    public void reloadConfig() {
        this.enabled = plugin.getConfig().getBoolean("modules.density-optimizer.enabled", true);
        this.checkInterval = plugin.getConfig().getInt("modules.density-optimizer.check-interval", 40);
        this.bypassTamed = plugin.getConfig().getBoolean("modules.density-optimizer.bypass-tamed", true);
        this.bypassNamed = plugin.getConfig().getBoolean("modules.density-optimizer.bypass-named", true);
        this.bypassLeashed = plugin.getConfig().getBoolean("modules.density-optimizer.bypass-leashed", true);
        this.scalingLimit = -1; // Reset on reload

        limits.clear();
        if (plugin.getConfig().getConfigurationSection("modules.density-optimizer.limits") != null) {
            for (String key : plugin.getConfig().getConfigurationSection("modules.density-optimizer.limits")
                    .getKeys(false)) {
                try {
                    EntityType type = EntityType.valueOf(key.toUpperCase());
                    int limit = plugin.getConfig().getInt("modules.density-optimizer.limits." + key);
                    limits.put(type, limit);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Invalid entity type in density-optimizer: " + key);
                }
            }
        }
    }

    public void start() {
        if (task != null)
            task.cancel();
        if (!enabled)
            return;

        // Incremental mode: run every tick but only process a slice of chunks.
        // This spreads work evenly instead of spiking every checkInterval ticks.
        // The full scan completes in roughly (totalLoadedChunks / chunksPerTick) ticks.
        // After a full pass, idle for checkInterval ticks before starting the next.
        task = SchedulerAdapter.runGlobalRepeating(plugin, this::scanIncremental, checkInterval, 1L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        restore();
    }

    private int idleCountdown = 0;

    private void scanIncremental() {
        if (!enabled || limits.isEmpty())
            return;

        // Idle between full passes
        if (idleCountdown > 0) {
            idleCountdown--;
            return;
        }

        // Start new pass if needed
        if (pendingChunks == null || scanCursor >= pendingChunks.length) {
            // Move to next world or start fresh
            List<World> worlds = Bukkit.getWorlds();
            if (pendingChunks != null && pendingWorldIndex + 1 < worlds.size()) {
                pendingWorldIndex++;
            } else {
                // Full cycle complete — idle for checkInterval ticks
                if (pendingChunks != null) {
                    pendingChunks = null;
                    idleCountdown = checkInterval;
                    return;
                }
                pendingWorldIndex = 0;
            }

            if (worlds.isEmpty()) return;
            if (pendingWorldIndex >= worlds.size()) {
                pendingChunks = null;
                idleCountdown = checkInterval;
                return;
            }
            pendingChunks = worlds.get(pendingWorldIndex).getLoadedChunks();
            scanCursor = 0;
        }

        // Process a slice of chunks this tick
        // On Folia each dispatch goes through reflection + region task queuing,
        // so we process fewer chunks per tick to reduce dispatch overhead.
        boolean folia = SchedulerAdapter.isFolia();
        int perTick = folia ? 8 : 30;
        int end = Math.min(scanCursor + perTick, pendingChunks.length);
        for (int i = scanCursor; i < end; i++) {
            Chunk chunk = pendingChunks[i];
            if (chunk.isLoaded()) {
                if (folia) {
                    // On Folia, entity access must happen on the chunk's owning region thread
                    final Chunk c = chunk;
                    SchedulerAdapter.runAtChunk(plugin, c.getWorld(), c.getX(), c.getZ(), () -> processChunk(c));
                } else {
                    processChunk(chunk);
                }
            }
        }
        scanCursor = end;
    }

    private void processChunk(Chunk chunk) {
        if (!chunk.isLoaded())
            return;

        // Local map — on Folia this runs on different region threads concurrently
        Map<EntityType, List<Mob>> mobsByType = new HashMap<>();

        // 1. Snapshot valid mobs
        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof Mob))
                continue;
            Mob mob = (Mob) entity;

            if (limits.containsKey(mob.getType())) {
                if (shouldBypass(mob))
                    continue;
                mobsByType.computeIfAbsent(mob.getType(), k -> new ArrayList<>()).add(mob);
            }
        }

        // 2. Process limits
        for (Map.Entry<EntityType, List<Mob>> entry : mobsByType.entrySet()) {
            EntityType type = entry.getKey();
            List<Mob> mobs = entry.getValue();
            int limit = limits.get(type);

            // Apply scaling limit if stricter
            if (scalingLimit > 0 && scalingLimit < limit) {
                limit = scalingLimit;
            }

            if (mobs.size() > limit) {
                // Too many mobs! Disable AI for the excess (bottom of list first?)
                // Actually, let's random or just take list order (usually order of
                // spawn/loading).
                // To be safe, we disable AI for the *excess* ones.
                // Keeping the *first* 'limit' mobs active is usually better user experience
                // (older mobs).

                for (int i = 0; i < mobs.size(); i++) {
                    Mob mob = mobs.get(i);
                    boolean shouldBeActive = (i < limit);

                    if (plugin.isMobAwareSafe(mob) != shouldBeActive) {
                        plugin.setMobAwareSafe(mob, shouldBeActive);
                        mob.setCollidable(shouldBeActive); // Also disable collision for performance!

                        if (!shouldBeActive) {
                            mob.setMetadata("LessLag.DensitySuppressed",
                                    new org.bukkit.metadata.FixedMetadataValue(plugin, true));
                        } else {
                            mob.removeMetadata("LessLag.DensitySuppressed", plugin);
                        }
                    }
                }
            } else {
                // Under limit: Ensure everything is active (recovery)
                // This is important if player killed some mobs, the remaining ones should wake
                // up.
                for (Mob mob : mobs) {
                    if (!plugin.isMobAwareSafe(mob)) {
                        plugin.setMobAwareSafe(mob, true);
                        mob.setCollidable(true);
                        mob.removeMetadata("LessLag.DensitySuppressed", plugin);
                    }
                }
            }
        }
    }

    private boolean shouldBypass(Mob mob) {
        // Unified compatibility check
        if (plugin.getCompatManager().isProtectedEntity(mob))
            return true;

        if (bypassNamed && LessLag.hasCustomName(mob))
            return true;
        if (bypassTamed && mob instanceof Tameable && ((Tameable) mob).isTamed())
            return true;
        if (bypassLeashed && mob.isLeashed())
            return true;
        return false;
    }

    private void restore() {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                if (SchedulerAdapter.isFolia()) {
                    // On Folia, dispatch to each chunk's region thread
                    final Chunk c = chunk;
                    SchedulerAdapter.runAtChunk(plugin, c.getWorld(), c.getX(), c.getZ(), () -> restoreChunk(c));
                } else {
                    restoreChunk(chunk);
                }
            }
        }
    }

    private void restoreChunk(Chunk chunk) {
        if (!chunk.isLoaded()) return;
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Mob) {
                Mob mob = (Mob) entity;
                if (!plugin.isMobAwareSafe(mob)) {
                    plugin.setMobAwareSafe(mob, true);
                    mob.setCollidable(true);
                    mob.removeMetadata("LessLag.DensitySuppressed", plugin);
                }
            }
        }
    }
}
