package com.lesslag.monitor;

import com.lesslag.LessLag;
import org.bukkit.Bukkit;
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
        // check
        // heavily)
        // Optimization: checking blocks around entity must be sync.
        // To avoid lag, we can slice this using WorkloadDistributor if needed,
        // but for now we'll do a simple per-world loop with a limit or just direct.

        // Actually, let's use the distributor to avoid spikes.
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getType() != EntityType.VILLAGER)
                    continue;

                Villager villager = (Villager) entity;

                // Skip if currently active (recently traded)
                if (activeVillagers.containsKey(villager.getUniqueId()))
                    continue;

                // Use distributor to spread the "AI disabling" checks
                plugin.getWorkloadDistributor().addWorkload(() -> {
                    if (!villager.isValid())
                        return;

                    boolean shouldOptimize = !optimizeTrappedOnly || isTrapped(villager);

                    if (shouldOptimize && plugin.isMobAwareSafe(villager)) {
                        plugin.setMobAwareSafe(villager, false);
                    } else if (!shouldOptimize && !plugin.isMobAwareSafe(villager)) {
                        // If no longer trapped (e.g. player broke the cell), re-enable
                        plugin.setMobAwareSafe(villager, true);
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
        Location loc = v.getLocation();
        Block feet = loc.getBlock();

        // Check if movement is restricted
        // A simple heuristic: check if there are 3+ solid blocks around at feet level?
        // Or if they are in a 1x1 hole (surrounded by 4 or corners).

        // Strict 1x1 check: North, South, East, West must be solid/blocking.
        // This might be too strict for some designs (e.g. slab/fence).
        // VillagerLobotimizer often relies on pathfinding result, but that's heavy.

        // Let's use a "movement restricted" check.
        // If they haven't moved in X seconds? No, that requires tracking.

        // Let's check for "Claustrophobic":
        // 1. Standing on something solid?
        // 2. Head or feet surrounded by collision?

        // Optimized approach: Check if they are inside a block collision box that
        // prevents movement.
        // For trading halls, they are usually in 1x1.

        int confiningBlocks = 0;
        Block[] surroundings = {
                feet.getRelative(BlockFace.NORTH),
                feet.getRelative(BlockFace.SOUTH),
                feet.getRelative(BlockFace.EAST),
                feet.getRelative(BlockFace.WEST)
        };

        for (Block b : surroundings) {
            if (b.getType().isSolid() || b.getType().name().contains("GLASS") || b.getType().name().contains("FENCE")
                    || b.getType().name().contains("WALL")) {
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
        }

        // 2. Mark as active
        long expiry = System.currentTimeMillis() + (restoreDuration * 1000L);
        activeVillagers.put(villager.getUniqueId(), expiry);
    }

    private void cleanupActiveVillagers() {
        long now = System.currentTimeMillis();
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
