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
    private Set<String> cachedWhitelist = Collections.emptySet();

    // Stats (atomic for cross-thread safety)
    private final AtomicInteger lastHotChunks = new AtomicInteger(0);
    private final AtomicInteger lastEntitiesRemoved = new AtomicInteger(0);
    private volatile long lastScanTime = 0;

    public ChunkLimiter(LessLag plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    private void loadConfig() {
        maxPerChunk = plugin.getConfig().getInt("modules.entities.chunk-limiter.max-entities-per-chunk", 50);
        scanInterval = plugin.getConfig().getInt("modules.entities.chunk-limiter.scan-interval", 30);

        cachedWhitelist = new HashSet<>();
        for (String s : plugin.getConfig().getStringList("modules.entities.chunk-limiter.whitelist"))
            cachedWhitelist.add(s.toUpperCase());
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("modules.entities.chunk-limiter.enabled", true))
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
    // Phase 1: Distributed Scan (Main Thread)
    // ══════════════════════════════════════════════════

    private void beginAsyncScan() {
        Set<String> whitelist = cachedWhitelist;
        lastHotChunks.set(0);
        lastEntitiesRemoved.set(0);

        Bukkit.getScheduler().runTask(plugin, () -> {
            for (World world : Bukkit.getWorlds()) {
                Chunk[] loadedChunks = world.getLoadedChunks();
                if (loadedChunks.length == 0)
                    continue;

                // Process chunks in batches of 10 to avoid lag spikes
                int batchSize = 10;
                for (int i = 0; i < loadedChunks.length; i += batchSize) {
                    final int start = i;
                    final int end = Math.min(i + batchSize, loadedChunks.length);
                    final Chunk[] chunks = loadedChunks;

                    plugin.getWorkloadDistributor().addWorkload(() -> {
                        processChunkBatch(chunks, start, end, whitelist);
                    });
                }
            }

            // Final reporting task
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

    private void processChunkBatch(Chunk[] chunks, int start, int end, Set<String> whitelist) {
        for (int i = start; i < end; i++) {
            Chunk chunk = chunks[i];
            if (!chunk.isLoaded())
                continue;

            Entity[] entities = chunk.getEntities();
            if (entities.length <= maxPerChunk)
                continue;

            // Hot chunk logic
            processHotChunk(chunk, entities, whitelist);
        }
    }

    private void processHotChunk(Chunk chunk, Entity[] entities, Set<String> whitelist) {
        lastHotChunks.incrementAndGet();
        int excess = entities.length - maxPerChunk;

        List<Entity> removable = new ArrayList<>();
        for (Entity entity : entities) {
            if (entity instanceof Player || isProtected(entity, whitelist))
                continue;

            // Check if removable type
            if (entity instanceof Item || entity instanceof ExperienceOrb || entity instanceof Monster
                    || entity instanceof LivingEntity) {
                removable.add(entity);
            }
        }

        // Sort directly (ITEM > XP > HOSTILE > PASSIVE)
        removable.sort((e1, e2) -> Integer.compare(getCategoryOrdinal(e1), getCategoryOrdinal(e2)));

        int toRemoveCount = Math.min(excess, removable.size());
        for (int i = 0; i < toRemoveCount; i++) {
            removable.get(i).remove();
            lastEntitiesRemoved.incrementAndGet();
        }

        if (toRemoveCount > 0) {
            plugin.getLogger().fine("[ChunkLimiter] Chunk (" + chunk.getX() + ", " + chunk.getZ()
                    + ") in " + chunk.getWorld().getName() + ": removed " + toRemoveCount + " entities");
        }
    }

    private int getCategoryOrdinal(Entity entity) {
        if (entity instanceof Item)
            return 0;
        if (entity instanceof ExperienceOrb)
            return 1;
        if (entity instanceof Monster)
            return 2;
        if (entity instanceof LivingEntity)
            return 3; // Passive
        return 4;
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
