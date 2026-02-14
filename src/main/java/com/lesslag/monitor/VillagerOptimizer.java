package com.lesslag.monitor;

import com.lesslag.LessLag;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Villager Lobotomizer — Optimizes villagers by disabling AI when they are
 * "trapped" (e.g. trading halls).
 *
 * <p>
 * Features:
 * <ul>
 * <li>Automatically detects 1x1 trading cells</li>
 * <li>Disables AI for trapped villagers (massive performance gain)</li>
 * <li>Temporarily re-enables AI on interaction (trading/restocking)</li>
 * </ul>
 */
public class VillagerOptimizer implements Listener {

    private final LessLag plugin;
    private BukkitTask scanTask;

    // Config
    private boolean enabled;
    private int checkInterval;
    private int restoreDuration;
    private boolean optimizeTrappedOnly;

    // State
    // UUIDs of villagers who have AI temporarily enabled [UUID -> Expiration Time
    // (ms)]
    private final Map<UUID, Long> activeVillagers = new ConcurrentHashMap<>();

    public VillagerOptimizer(LessLag plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    private void loadConfig() {
        enabled = plugin.getConfig().getBoolean("modules.villager-optimizer.enabled", true);
        checkInterval = plugin.getConfig().getInt("modules.villager-optimizer.check-interval", 600);
        restoreDuration = plugin.getConfig().getInt("modules.villager-optimizer.ai-restore-duration", 30);
        optimizeTrappedOnly = plugin.getConfig().getBoolean("modules.villager-optimizer.optimize-trapped", true);
    }

    public void start() {
        if (!enabled)
            return;

        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        // Periodic scan task (ASYNC for finding candidates, SYNC for modifying)
        scanTask = new BukkitRunnable() {
            @Override
            public void run() {
                runOptimizationScan();
            }
        }.runTaskTimer(plugin, 100L, checkInterval);

        // Cleanup task for temporary AI (runs faster, e.g. every 5s)
        new BukkitRunnable() {
            @Override
            public void run() {
                cleanupActiveVillagers();
            }
        }.runTaskTimer(plugin, 100L, 100L);

        plugin.getLogger().info("Villager Optimizer started (Interval: " + checkInterval + " ticks)");
    }

    public void stop() {
        if (scanTask != null) {
            scanTask.cancel();
            scanTask = null;
        }
        // HandlerList.unregisterAll(this); // Optional, but good practice if reloading
    }

    // ══════════════════════════════════════════════════
    // Optimization Logic
    // ══════════════════════════════════════════════════

    private void runOptimizationScan() {
        // Collect candidates (Sync because we need Bukkit API involved in isTrapped
        // check heavily)
        // Optimization: Iterate loaded chunks to avoid massive entity list copy.
        // Batch workloads per chunk to reduce WorkloadDistributor queue pressure.

        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                // Quick check if chunk has villagers before scheduling?
                // getEntities() on chunk is array copy too, but much smaller.
                // We'll schedule the chunk processing as a single unit.

                plugin.getWorkloadDistributor().addWorkload(() -> {
                    if (!chunk.isLoaded())
                        return;

                    for (Entity entity : chunk.getEntities()) {
                        if (entity.getType() != EntityType.VILLAGER)
                            continue;

                        Villager villager = (Villager) entity;

                        // Skip if currently active (recently traded)
                        if (activeVillagers.containsKey(villager.getUniqueId()))
                            continue;

                        if (!villager.isValid())
                            continue;

                        // Check throttling: If already optimized, skip check if checked recently
                        long now = System.nanoTime();
                        if (villager.hasMetadata("LessLag.VillagerOptimized")
                                && villager.hasMetadata("LessLag.LastTrappedCheck")) {
                            long lastCheck = villager.getMetadata("LessLag.LastTrappedCheck").get(0).asLong();
                            if (now - lastCheck < 120_000_000_000L) { // 2 minutes
                                continue;
                            }
                        }

                        boolean shouldOptimize = !optimizeTrappedOnly || isTrapped(villager);

                        if (shouldOptimize) {
                            if (plugin.isMobAwareSafe(villager)) {
                                plugin.setMobAwareSafe(villager, false);
                                villager.setMetadata("LessLag.VillagerOptimized",
                                        new org.bukkit.metadata.FixedMetadataValue(plugin, true));
                            }
                            // Update last check timestamp
                            villager.setMetadata("LessLag.LastTrappedCheck",
                                    new org.bukkit.metadata.FixedMetadataValue(plugin, now));
                        } else if (!plugin.isMobAwareSafe(villager)) {
                            // If no longer trapped (e.g. player broke the cell), re-enable
                            plugin.setMobAwareSafe(villager, true);
                            villager.removeMetadata("LessLag.VillagerOptimized", plugin);
                            villager.removeMetadata("LessLag.LastTrappedCheck", plugin);
                        }
                    }
                });
            }
        }
    }

    /**
     * Checks if a villager is effectively trapped in a small area.
     * Criteria: Surrounded by solid blocks or unable to pathfind significantly.
     * Simple check: 1x1 hole.
     */
    private boolean isTrapped(Villager v) {
        // Vehicle Check (e.g. Minecart, Boat) - Often used in farms
        if (v.getVehicle() != null) {
            return true;
        }

        Location loc = v.getLocation();
        Block feet = loc.getBlock();

        // Check 1x1 confinement
        int confiningBlocks = 0;
        Block[] surroundings = {
                feet.getRelative(BlockFace.NORTH),
                feet.getRelative(BlockFace.SOUTH),
                feet.getRelative(BlockFace.EAST),
                feet.getRelative(BlockFace.WEST)
        };

        for (Block b : surroundings) {
            String name = b.getType().name();
            // Check for solid OR obstructing blocks (like glass, fences, trapdoors)
            // Trapdoors are strictly blocking if closed, but hard to check state simply
            // without casting.
            // However, in farms, they are almost always used to block pathing.
            // We assume if it's a "barrier-like" block, it contributes to trapping.
            if (b.getType().isSolid() ||
                    name.contains("GLASS") ||
                    name.contains("FENCE") ||
                    name.contains("WALL") ||
                    name.contains("TRAPDOOR") ||
                    name.contains("IRON_BARS") ||
                    name.contains("DOOR") ||
                    name.contains("GATE")) {
                confiningBlocks++;
            }
        }

        return confiningBlocks >= 3;
    }

    // ══════════════════════════════════════════════════
    // Interaction Handler (The "Anti-Lobotomy" on click)
    // ══════════════════════════════════════════════════

    @EventHandler
    public void onVillagerInteract(PlayerInteractEntityEvent event) {
        if (!enabled)
            return;
        if (event.getRightClicked().getType() != EntityType.VILLAGER)
            return;

        Villager villager = (Villager) event.getRightClicked();

        // Re-enable AI temporarily
        activateVillager(villager);
    }

    public void activateVillager(Villager villager) {
        // 1. Enable AI
        if (!plugin.isMobAwareSafe(villager)) {
            plugin.setMobAwareSafe(villager, true);
            villager.removeMetadata("LessLag.VillagerOptimized", plugin);
        }

        // 2. Mark as active
        long expiry = System.nanoTime() + (restoreDuration * 1_000_000_000L);
        activeVillagers.put(villager.getUniqueId(), expiry);
    }

    private void cleanupActiveVillagers() {
        long now = System.nanoTime();
        Iterator<Map.Entry<UUID, Long>> it = activeVillagers.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            if (now > entry.getValue()) {
                // Expired
                UUID uuid = entry.getKey();
                it.remove();

                // Queuing the re-check/disable
                plugin.getWorkloadDistributor().addWorkload(() -> {
                    Entity entity = Bukkit.getEntity(uuid);
                    if (entity instanceof Villager && entity.isValid()) {
                        Villager v = (Villager) entity;
                        // Determine if we should optimize again
                        boolean shouldOptimize = !optimizeTrappedOnly || isTrapped(v);
                        if (shouldOptimize) {
                            plugin.setMobAwareSafe(v, false);
                            v.setMetadata("LessLag.VillagerOptimized",
                                    new org.bukkit.metadata.FixedMetadataValue(plugin, true));
                        }
                    }
                });
            }
        }
    }

    // ══════════════════════════════════════════════════
    // Getters
    // ══════════════════════════════════════════════════

    public int getOptimizedCount() {
        int count = 0;
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntitiesByClass(Villager.class)) {
                if (!plugin.isMobAwareSafe((Villager) e)) {
                    count++;
                }
            }
        }
        return count;
    }

    public int getActiveRestoredCount() {
        return activeVillagers.size();
    }
}
