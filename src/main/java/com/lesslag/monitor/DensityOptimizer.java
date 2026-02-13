package com.lesslag.monitor;

import com.lesslag.LessLag;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class DensityOptimizer {

    private final LessLag plugin;
    private boolean enabled;
    private int checkInterval;
    private Map<EntityType, Integer> limits;
    private boolean bypassTamed;
    private boolean bypassNamed;
    private boolean bypassLeashed;
    private BukkitTask task;

    public DensityOptimizer(LessLag plugin) {
        this.plugin = plugin;
        this.limits = new HashMap<>();
        reloadConfig();
    }

    public void reloadConfig() {
        this.enabled = plugin.getConfig().getBoolean("modules.density-optimizer.enabled", true);
        this.checkInterval = plugin.getConfig().getInt("modules.density-optimizer.check-interval", 40);
        this.bypassTamed = plugin.getConfig().getBoolean("modules.density-optimizer.bypass-tamed", true);
        this.bypassNamed = plugin.getConfig().getBoolean("modules.density-optimizer.bypass-named", true);
        this.bypassLeashed = plugin.getConfig().getBoolean("modules.density-optimizer.bypass-leashed", true);

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

        task = Bukkit.getScheduler().runTaskTimer(plugin, this::scan, checkInterval, checkInterval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        restore();
    }

    private void scan() {
        if (!enabled)
            return;

        // Use WorkloadDistributor to avoid lag spikes
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                plugin.getWorkloadDistributor().addWorkload(() -> processChunk(chunk));
            }
        }
    }

    private void processChunk(Chunk chunk) {
        if (!chunk.isLoaded())
            return;

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
    }
}
