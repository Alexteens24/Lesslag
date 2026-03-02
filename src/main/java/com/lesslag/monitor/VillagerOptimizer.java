package com.lesslag.monitor;

import com.lesslag.LessLag;
import com.lesslag.util.SchedulerAdapter;

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
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;

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
    private SchedulerAdapter.TaskHandle scanTask;
    private SchedulerAdapter.TaskHandle cleanupTask;

    // Config
    private boolean enabled;
    private int checkInterval;
    private int restoreDuration;
    private boolean optimizeTrappedOnly;

    // State
    // UUIDs of villagers who have AI temporarily enabled [UUID -> ActiveVillagerInfo]
    private final Map<UUID, ActiveVillagerInfo> activeVillagers = new ConcurrentHashMap<>();

    // ── Incremental scan state ──
    private Chunk[] scanChunks = null;
    private int scanCursor = 0;
    private int scanWorldIndex = 0;
    private int scanIdleCountdown = 0;
    private static final int VILLAGER_CHUNKS_PER_TICK = 20;

    private static class ActiveVillagerInfo {
        final long expiry;
        final UUID worldUID;
        final int chunkX, chunkZ;

        ActiveVillagerInfo(long expiry, UUID worldUID, int chunkX, int chunkZ) {
            this.expiry = expiry;
            this.worldUID = worldUID;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
    }

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

        // Incremental scan: run every tick, process a slice of chunks each tick.
        // After a full pass, idle for checkInterval ticks before starting the next.
        scanTask = SchedulerAdapter.runGlobalRepeating(plugin, this::scanIncremental, 100L, 1L);

        // Cleanup task for temporary AI (runs faster, e.g. every 5s)
        cleanupTask = SchedulerAdapter.runGlobalRepeating(plugin, () -> {
            cleanupActiveVillagers();
        }, 100L, 100L);

        plugin.getLogger().info("Villager Optimizer started (Interval: " + checkInterval + " ticks)");
    }

    public void stop() {
        if (scanTask != null) {
            scanTask.cancel();
            scanTask = null;
        }
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        HandlerList.unregisterAll(this);
    }

    // ══════════════════════════════════════════════════
    // Optimization Logic
    // ══════════════════════════════════════════════════

    private void scanIncremental() {
        // Idle between full passes
        if (scanIdleCountdown > 0) {
            scanIdleCountdown--;
            return;
        }

        // Start new pass if needed
        if (scanChunks == null || scanCursor >= scanChunks.length) {
            List<World> worlds = Bukkit.getWorlds();
            if (scanChunks != null && scanWorldIndex + 1 < worlds.size()) {
                scanWorldIndex++;
            } else {
                if (scanChunks != null) {
                    scanChunks = null;
                    scanIdleCountdown = checkInterval;
                    return;
                }
                scanWorldIndex = 0;
            }
            if (worlds.isEmpty()) return;
            if (scanWorldIndex >= worlds.size()) {
                scanChunks = null;
                scanIdleCountdown = checkInterval;
                return;
            }
            scanChunks = worlds.get(scanWorldIndex).getLoadedChunks();
            scanCursor = 0;
        }

        // Process a slice of chunks this tick
        boolean folia = SchedulerAdapter.isFolia();
        int end = Math.min(scanCursor + VILLAGER_CHUNKS_PER_TICK, scanChunks.length);
        for (int i = scanCursor; i < end; i++) {
            Chunk chunk = scanChunks[i];
            if (!chunk.isLoaded()) continue;
            if (folia) {
                // On Folia, entity access must happen on the chunk's owning region thread
                final Chunk c = chunk;
                SchedulerAdapter.runAtChunk(plugin, c.getWorld(), c.getX(), c.getZ(), () -> processVillagerChunk(c));
            } else {
                processVillagerChunk(chunk);
            }
        }
        scanCursor = end;
    }

    private void processVillagerChunk(Chunk chunk) {
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

            // Compatibility check: don't optimize NPCs or merchants
            if (plugin.getCompatManager().isProtectedEntity(villager)) {
                continue;
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

        // 2. Mark as active with location data for region-safe dispatch
        long expiry = System.nanoTime() + (restoreDuration * 1_000_000_000L);
        Location loc = villager.getLocation();
        activeVillagers.put(villager.getUniqueId(), new ActiveVillagerInfo(
                expiry, loc.getWorld().getUID(),
                loc.getBlockX() >> 4, loc.getBlockZ() >> 4));
    }

    private void cleanupActiveVillagers() {
        long now = System.nanoTime();
        Iterator<Map.Entry<UUID, ActiveVillagerInfo>> it = activeVillagers.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<UUID, ActiveVillagerInfo> entry = it.next();
            if (now > entry.getValue().expiry) {
                // Expired
                UUID uuid = entry.getKey();
                ActiveVillagerInfo info = entry.getValue();
                it.remove();

                // Queuing the re-check/disable
                if (SchedulerAdapter.isFolia()) {
                    // On Folia: dispatch to the entity's owning region thread
                    World world = Bukkit.getWorld(info.worldUID);
                    if (world == null) continue;
                    SchedulerAdapter.runAtChunk(plugin, world, info.chunkX, info.chunkZ, () -> {
                        Entity entity = Bukkit.getEntity(uuid);
                        if (entity instanceof Villager && entity.isValid()) {
                            Villager v = (Villager) entity;
                            boolean shouldOptimize = !optimizeTrappedOnly || isTrapped(v);
                            if (shouldOptimize) {
                                plugin.setMobAwareSafe(v, false);
                                v.setMetadata("LessLag.VillagerOptimized",
                                        new org.bukkit.metadata.FixedMetadataValue(plugin, true));
                            }
                        }
                    });
                } else {
                    plugin.getWorkloadDistributor().addWorkload(() -> {
                        Entity entity = Bukkit.getEntity(uuid);
                        if (entity instanceof Villager && entity.isValid()) {
                            Villager v = (Villager) entity;
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
