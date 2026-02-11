package com.lesslag.monitor;

import com.lesslag.LessLag;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Smart Chunk Limiter — Surgically removes excess entities only in
 * overloaded chunks, instead of wiping the entire server.
 *
 * Architecture: ASYNC timer → SYNC snapshot → ASYNC analysis → SYNC removal
 * 1. Timer fires ASYNC
 * 2. Brief SYNC snapshot: collect entity data per chunk
 * 3. ASYNC: identify hot chunks, sort by priority
 * 4. SYNC: dispatch entity removal via WorkloadDistributor (batched)
 */
public class ChunkLimiter {

    private final LessLag plugin;
    private BukkitTask scanTask;

    // Config (cached)
    private int maxPerChunk;
    private int scanInterval;

    // Stats (atomic for cross-thread safety)
    private final AtomicInteger lastHotChunks = new AtomicInteger(0);
    private final AtomicInteger lastEntitiesRemoved = new AtomicInteger(0);
    private volatile long lastScanTime = 0;

    public ChunkLimiter(LessLag plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    private void loadConfig() {
        maxPerChunk = plugin.getConfig().getInt("chunk-limiter.max-entities-per-chunk", 50);
        scanInterval = plugin.getConfig().getInt("chunk-limiter.scan-interval", 30);
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("chunk-limiter.enabled", true))
            return;

        // ASYNC periodic trigger
        scanTask = new BukkitRunnable() {
            @Override
            public void run() {
                beginAsyncScan();
            }
        }.runTaskTimerAsynchronously(plugin, 200L, scanInterval * 20L);

        plugin.getLogger().info("Smart Chunk Limiter started ASYNC (interval: " + scanInterval + "s)");
    }

    public void stop() {
        if (scanTask != null) {
            scanTask.cancel();
            scanTask = null;
        }
    }

    // ══════════════════════════════════════════════════
    // Phase 1: ASYNC → SYNC snapshot
    // ══════════════════════════════════════════════════

    private void beginAsyncScan() {
        Set<String> whitelist = loadWhitelist();

        // Brief SYNC snapshot
        Bukkit.getScheduler().runTask(plugin, () -> {
            List<ChunkSnapshot> snapshots = collectSnapshot(whitelist);
            if (snapshots.isEmpty())
                return;

            // Back to ASYNC for analysis
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                analyzeAndDispatch(snapshots, whitelist);
            });
        });
    }

    // ══════════════════════════════════════════════════
    // Phase 2: SYNC — Quick snapshot
    // ══════════════════════════════════════════════════

    private List<ChunkSnapshot> collectSnapshot(Set<String> whitelist) {
        List<ChunkSnapshot> snapshots = new ArrayList<>();

        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                Entity[] entities = chunk.getEntities();
                if (entities.length <= maxPerChunk)
                    continue;

                // This chunk is hot — snapshot its entities
                List<EntitySnapshot> entitySnaps = new ArrayList<>();
                for (Entity entity : entities) {
                    if (entity instanceof Player)
                        continue;
                    if (isProtected(entity, whitelist))
                        continue;

                    EntityCategory cat;
                    if (entity instanceof Item)
                        cat = EntityCategory.ITEM;
                    else if (entity instanceof ExperienceOrb)
                        cat = EntityCategory.XP_ORB;
                    else if (entity instanceof Monster)
                        cat = EntityCategory.HOSTILE;
                    else if (entity instanceof LivingEntity)
                        cat = EntityCategory.PASSIVE;
                    else
                        continue;

                    entitySnaps.add(new EntitySnapshot(entity.getUniqueId(), cat));
                }

                snapshots.add(new ChunkSnapshot(
                        world.getName(), chunk.getX(), chunk.getZ(),
                        entities.length, entitySnaps));
            }
        }

        return snapshots;
    }

    // ══════════════════════════════════════════════════
    // Phase 3: ASYNC — Analyze hot chunks
    // ══════════════════════════════════════════════════

    private void analyzeAndDispatch(List<ChunkSnapshot> snapshots, Set<String> whitelist) {
        lastHotChunks.set(0);
        lastEntitiesRemoved.set(0);

        List<UUID> toRemove = new ArrayList<>();

        for (ChunkSnapshot chunk : snapshots) {
            int excess = chunk.totalEntities - maxPerChunk;
            if (excess <= 0)
                continue;

            lastHotChunks.incrementAndGet();

            // Sort by removal priority (ITEM > XP > HOSTILE > PASSIVE)
            List<EntitySnapshot> removable = new ArrayList<>(chunk.entities);
            removable.sort(Comparator.comparingInt(e -> e.category.ordinal()));

            int toRemoveCount = Math.min(excess, removable.size());
            for (int i = 0; i < toRemoveCount; i++) {
                toRemove.add(removable.get(i).uuid);
            }

            plugin.getLogger().fine("[ChunkLimiter] Chunk (" + chunk.chunkX + ", " + chunk.chunkZ
                    + ") in " + chunk.worldName + ": scheduling " + toRemoveCount + " removals");
        }

        if (toRemove.isEmpty()) {
            lastScanTime = System.currentTimeMillis();
            return;
        }

        // Dispatch removals to main thread via WorkloadDistributor
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (UUID uuid : toRemove) {
                plugin.getWorkloadDistributor().addWorkload(() -> {
                    Entity entity = Bukkit.getEntity(uuid);
                    if (entity != null && entity.isValid() && !(entity instanceof Player)) {
                        entity.remove();
                        lastEntitiesRemoved.incrementAndGet();
                    }
                });
            }

            // Final stats task
            plugin.getWorkloadDistributor().addWorkload(() -> {
                lastScanTime = System.currentTimeMillis();
                int removed = lastEntitiesRemoved.get();
                if (removed > 0) {
                    plugin.getLogger().info("[ChunkLimiter] Cleaned " + removed
                            + " entities from " + lastHotChunks.get() + " overloaded chunk(s)");
                }
            });
        });
    }

    /**
     * One-shot manual scan triggered by command or threshold action.
     */
    public void manualClean() {
        beginAsyncScan();
    }

    // Kept on sync path for direct calls (e.g. from ActionExecutor)
    public void scanAndClean() {
        beginAsyncScan();
    }

    // ══════════════════════════════════════════════════
    // Protection checks
    // ══════════════════════════════════════════════════

    private boolean isProtected(Entity entity, Set<String> whitelist) {
        if (whitelist.contains(entity.getType().name()))
            return true;
        if (LessLag.hasCustomName(entity))
            return true;
        if (entity instanceof Tameable && ((Tameable) entity).isTamed())
            return true;
        if (entity instanceof LivingEntity && ((LivingEntity) entity).isLeashed())
            return true;
        if (!entity.getPassengers().isEmpty() || entity.getVehicle() != null)
            return true;
        if (entity instanceof ArmorStand)
            return true;
        return false;
    }

    private Set<String> loadWhitelist() {
        Set<String> set = new HashSet<>();
        for (String s : plugin.getConfig().getStringList("entity-whitelist"))
            set.add(s.toUpperCase());
        return set;
    }

    // ══════════════════════════════════════════════════
    // Data Classes
    // ══════════════════════════════════════════════════

    private enum EntityCategory {
        ITEM, XP_ORB, HOSTILE, PASSIVE
    }

    private static class EntitySnapshot {
        final UUID uuid;
        final EntityCategory category;

        EntitySnapshot(UUID uuid, EntityCategory category) {
            this.uuid = uuid;
            this.category = category;
        }
    }

    private static class ChunkSnapshot {
        final String worldName;
        final int chunkX, chunkZ;
        final int totalEntities;
        final List<EntitySnapshot> entities;

        ChunkSnapshot(String worldName, int chunkX, int chunkZ, int totalEntities,
                List<EntitySnapshot> entities) {
            this.worldName = worldName;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.totalEntities = totalEntities;
            this.entities = entities;
        }
    }

    // ══════════════════════════════════════════════════
    // Getters
    // ══════════════════════════════════════════════════

    public int getLastHotChunks() {
        return lastHotChunks.get();
    }

    public int getLastEntitiesRemoved() {
        return lastEntitiesRemoved.get();
    }

    public long getLastScanTime() {
        return lastScanTime;
    }
}
