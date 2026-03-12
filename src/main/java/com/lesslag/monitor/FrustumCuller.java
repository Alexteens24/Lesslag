package com.lesslag.monitor;

import com.lesslag.LessLag;
import com.lesslag.util.SchedulerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Entity AI Frustum Culling — Disables AI for mobs outside players' FOV.
 *
 * Architecture: ASYNC timer → SYNC snapshot → ASYNC analysis → SYNC dispatch
 * 1. Periodic check fires ASYNC
 * 2. Brief SYNC snapshot: collect mob data + player view data
 * 3. ASYNC: calculate visibility per mob (heavy trig/distance math)
 * 4. SYNC: dispatch AI enable/disable via WorkloadDistributor (batched)
 */
public class FrustumCuller {

    private final LessLag plugin;
    private SchedulerAdapter.TaskHandle task;

    // Config (cached)
    private double maxRadius;
    private double fovDegrees;
    private double behindRadius;
    private int intervalTicks;
    private Set<String> protectedTypes = Collections.emptySet();

    // PDC keys — must mirror the keys used in DensityOptimizer, VillagerOptimizer, MobFarmOptimizer
    private final NamespacedKey DENSITY_SUPPRESSED_KEY;
    private final NamespacedKey VILLAGER_OPTIMIZED_KEY;
    private final NamespacedKey FARM_DUMB_KEY;

    // Stats
    private final AtomicInteger lastCulled = new AtomicInteger(0);
    private final AtomicInteger lastRestored = new AtomicInteger(0);
    private final AtomicInteger lastProcessed = new AtomicInteger(0);

    public FrustumCuller(LessLag plugin) {
        this.plugin = plugin;
        // Mirror the PDC keys used by the optimizer/suppressor classes
        DENSITY_SUPPRESSED_KEY = new NamespacedKey(plugin, "density_suppressed");
        VILLAGER_OPTIMIZED_KEY = new NamespacedKey(plugin, "villager_optimized");
        FARM_DUMB_KEY          = new NamespacedKey(plugin, "farm_dumb");
        loadConfig();
    }

    private void loadConfig() {
        maxRadius = plugin.getConfig().getDouble("modules.mob-ai.active-radius", 48);
        fovDegrees = plugin.getConfig().getDouble("modules.mob-ai.fov-degrees", 110);
        behindRadius = plugin.getConfig().getDouble("modules.mob-ai.behind-safe-radius", 12);
        intervalTicks = plugin.getConfig().getInt("modules.mob-ai.update-interval", 20);

        protectedTypes = new HashSet<>();
        for (String s : plugin.getConfig().getStringList("modules.mob-ai.protected"))
            protectedTypes.add(s.toUpperCase());
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("modules.mob-ai.enabled", true))
            return;

        // ASYNC periodic trigger
        task = SchedulerAdapter.runAsyncRepeating(plugin, () -> {
            beginAsyncCull();
        }, 100L, intervalTicks);

        plugin.getLogger().info("Frustum Culler started ASYNC (interval: " + intervalTicks
                + " ticks, FOV: " + fovDegrees + "°)");
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    // ══════════════════════════════════════════════════
    // Phase 1: ASYNC → dispatch to SYNC for snapshot
    // ══════════════════════════════════════════════════

    private void beginAsyncCull() {
        if (!plugin.isEnabled()) {
            return;
        }

        double fovCosine = Math.cos(Math.toRadians(fovDegrees / 2.0));
        double maxRadiusSq = maxRadius * maxRadius;
        double behindRadiusSq = behindRadius * behindRadius;

        java.util.function.Consumer<SnapshotResult> onComplete = snapshot -> {
            if (!plugin.isEnabled())
                return;
            if (snapshot.mobs.isEmpty())
                return;
            SchedulerAdapter.runAsync(plugin, () -> {
                analyzeAndDispatch(snapshot, fovCosine, maxRadiusSq, behindRadiusSq);
            });
        };

        if (SchedulerAdapter.isFolia()) {
            // On Folia: dispatch per-player to entity's owning region thread
            buildSnapshotFolia(onComplete);
        } else {
            // On Paper/Spigot: incremental snapshot on main thread
            SchedulerAdapter.runGlobal(plugin, () -> {
                new IncrementalCullSnapshotBuilder(onComplete).start();
            });
        }
    }

    /**
     * Folia-safe snapshot: dispatches to each player's entity scheduler,
     * collects results in thread-safe collections, triggers callback when done.
     */
    private void buildSnapshotFolia(java.util.function.Consumer<SnapshotResult> callback) {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) {
            callback.accept(new SnapshotResult(Collections.emptyMap(), Collections.emptyList()));
            return;
        }

        // Thread-safe collections for concurrent per-player writes
        java.util.concurrent.ConcurrentHashMap<UUID, List<PlayerView>> worldViewData = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.ConcurrentLinkedQueue<MobSnapshot> mobs = new java.util.concurrent.ConcurrentLinkedQueue<>();
        java.util.concurrent.ConcurrentHashMap.KeySetView<UUID, Boolean> processedMobs = java.util.concurrent.ConcurrentHashMap
                .newKeySet();
        AtomicInteger remaining = new AtomicInteger(players.size());

        for (Player player : players) {
            SchedulerAdapter.runAtEntity(plugin, player, () -> {
                try {
                    if (!player.isOnline())
                        return;

                    World world = player.getWorld();
                    Location eye = player.getEyeLocation();

                    worldViewData
                            .computeIfAbsent(world.getUID(),
                                    k -> java.util.Collections.synchronizedList(new ArrayList<>()))
                            .add(new PlayerView(
                                    eye.getX(), eye.getY(), eye.getZ(),
                                    eye.getDirection().getX(), eye.getDirection().getY(), eye.getDirection().getZ(),
                                    world.getUID()));

                    double maxRadiusSq = maxRadius * maxRadius;
                    for (Entity entity : player.getNearbyEntities(maxRadius, maxRadius, maxRadius)) {
                        if (!(entity instanceof Mob))
                            continue;
                        Mob mob = (Mob) entity;

                        double pdx = player.getX() - mob.getX();
                        double pdy = player.getY() - mob.getY();
                        double pdz = player.getZ() - mob.getZ();
                        if (pdx * pdx + pdy * pdy + pdz * pdz > maxRadiusSq)
                            continue;
                        if (!processedMobs.add(mob.getUniqueId()))
                            continue;

                        boolean shouldSkip = false;
                        boolean forceRestore = false;

                        if (protectedTypes.contains(mob.getType().name())) {
                            shouldSkip = true;
                            forceRestore = true;
                        } else if (LessLag.hasCustomName(mob)) {
                            shouldSkip = true;
                            forceRestore = true;
                        } else if (plugin.getCompatManager().isProtectedEntity(mob)) {
                            shouldSkip = true;
                            forceRestore = true;
                        } else if (mob instanceof org.bukkit.entity.Tameable
                                && ((org.bukkit.entity.Tameable) mob).isTamed()) {
                            shouldSkip = true;
                            forceRestore = true;
                        } else if (mob instanceof org.bukkit.entity.Steerable
                                && ((org.bukkit.entity.Steerable) mob).hasSaddle()) {
                            shouldSkip = true;
                            forceRestore = true;
                        } else if (mob instanceof org.bukkit.entity.Llama
                                && ((org.bukkit.entity.Llama) mob).getInventory().getDecor() != null) {
                            shouldSkip = true;
                            forceRestore = true;
                        } else if (mob.getPersistentDataContainer().has(DENSITY_SUPPRESSED_KEY, PersistentDataType.BYTE)) {
                            shouldSkip = true;
                        } else if (mob.getPersistentDataContainer().has(VILLAGER_OPTIMIZED_KEY, PersistentDataType.BYTE)) {
                            shouldSkip = true;
                        } else if (mob.getPersistentDataContainer().has(FARM_DUMB_KEY, PersistentDataType.BYTE)) {
                            shouldSkip = true;
                        }

                        boolean currentlyAware = plugin.isMobAwareSafe(mob);

                        if (shouldSkip) {
                            if (forceRestore && !currentlyAware) {
                                mobs.add(new MobSnapshot(
                                        mob.getUniqueId(), world.getUID(),
                                        mob.getX(), mob.getY(), mob.getZ(),
                                        currentlyAware, true));
                            }
                            continue;
                        }

                        mobs.add(new MobSnapshot(
                                mob.getUniqueId(), world.getUID(),
                                mob.getX(), mob.getY(), mob.getZ(),
                                currentlyAware, false));
                    }
                } finally {
                    if (remaining.decrementAndGet() == 0) {
                        // All players processed — trigger analysis
                        callback.accept(new SnapshotResult(worldViewData, new ArrayList<>(mobs)));
                    }
                }
            });
        }
    }

    // ══════════════════════════════════════════════════
    // Phase 2: SYNC — Incremental snapshot (Paper/Spigot only)
    // ══════════════════════════════════════════════════

    private class IncrementalCullSnapshotBuilder implements Runnable {
        private final java.util.function.Consumer<SnapshotResult> callback;
        private final List<Player> allPlayers;
        private int playerIndex = 0;
        private java.util.Iterator<Entity> currentEntities = null;
        private Player currentPlayer = null;

        private final Map<UUID, List<PlayerView>> worldViewData = new HashMap<>();
        private final List<MobSnapshot> mobs = new ArrayList<>();
        private final Set<UUID> processedMobs = new HashSet<>();

        private static final long MAX_NANOS_PER_TICK = 500_000; // 0.5ms budget

        IncrementalCullSnapshotBuilder(java.util.function.Consumer<SnapshotResult> callback) {
            this.callback = callback;
            this.allPlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
        }

        public void start() {
            run();
        }

        @Override
        public void run() {
            if (!plugin.isEnabled()) {
                return;
            }
            long stopTime = System.nanoTime() + MAX_NANOS_PER_TICK;

            while (playerIndex < allPlayers.size() || currentEntities != null) {
                if (System.nanoTime() > stopTime) {
                    if (!plugin.isEnabled()) {
                        return;
                    }
                    SchedulerAdapter.runGlobal(plugin, this);
                    return;
                }

                if (currentEntities == null) {
                    currentPlayer = allPlayers.get(playerIndex++);
                    if (!currentPlayer.isOnline())
                        continue;

                    World world = currentPlayer.getWorld();
                    Location eye = currentPlayer.getEyeLocation();

                    worldViewData.computeIfAbsent(world.getUID(), k -> new ArrayList<>())
                            .add(new PlayerView(
                                    eye.getX(), eye.getY(), eye.getZ(),
                                    eye.getDirection().getX(), eye.getDirection().getY(), eye.getDirection().getZ(),
                                    world.getUID()));

                    // Collect entities for this player
                    currentEntities = currentPlayer.getNearbyEntities(maxRadius, maxRadius, maxRadius).iterator();
                }

                int processedThisBatch = 0;
                double maxRadiusSq = maxRadius * maxRadius;
                while (currentEntities.hasNext()) {
                    Entity entity = currentEntities.next();
                    if (!(entity instanceof Mob))
                        continue;
                    Mob mob = (Mob) entity;

                    // Optimization: Use zero-allocation primitive math
                    double pdx = currentPlayer.getX() - mob.getX();
                    double pdy = currentPlayer.getY() - mob.getY();
                    double pdz = currentPlayer.getZ() - mob.getZ();
                    if (pdx * pdx + pdy * pdy + pdz * pdz > maxRadiusSq)
                        continue;

                    if (!processedMobs.add(mob.getUniqueId()))
                        continue;

                    boolean shouldSkip = false;
                    boolean forceRestore = false;

                    if (protectedTypes.contains(mob.getType().name())) {
                        shouldSkip = true;
                        forceRestore = true;
                    } else if (LessLag.hasCustomName(mob)) {
                        shouldSkip = true;
                        forceRestore = true;
                    } else if (plugin.getCompatManager().isProtectedEntity(mob)) {
                        shouldSkip = true;
                        forceRestore = true;
                    } else if (mob instanceof org.bukkit.entity.Tameable
                            && ((org.bukkit.entity.Tameable) mob).isTamed()) {
                        shouldSkip = true;
                        forceRestore = true;
                    } else if (mob instanceof org.bukkit.entity.Steerable
                            && ((org.bukkit.entity.Steerable) mob).hasSaddle()) {
                        shouldSkip = true;
                        forceRestore = true;
                    } else if (mob instanceof org.bukkit.entity.Llama
                            && ((org.bukkit.entity.Llama) mob).getInventory().getDecor() != null) {
                        shouldSkip = true;
                        forceRestore = true;
                    } else if (mob.getPersistentDataContainer().has(DENSITY_SUPPRESSED_KEY, PersistentDataType.BYTE)) {
                        shouldSkip = true;
                    } else if (mob.getPersistentDataContainer().has(VILLAGER_OPTIMIZED_KEY, PersistentDataType.BYTE)) {
                        shouldSkip = true;
                    } else if (mob.getPersistentDataContainer().has(FARM_DUMB_KEY, PersistentDataType.BYTE)) {
                        shouldSkip = true;
                    }

                    boolean currentlyAware = plugin.isMobAwareSafe(mob);

                    if (shouldSkip) {
                        if (forceRestore && !currentlyAware) {
                            mobs.add(new MobSnapshot(
                                    mob.getUniqueId(), currentPlayer.getWorld().getUID(),
                                    mob.getX(), mob.getY(), mob.getZ(),
                                    currentlyAware, true));
                        }
                        continue;
                    }

                    mobs.add(new MobSnapshot(
                            mob.getUniqueId(), currentPlayer.getWorld().getUID(),
                            mob.getX(), mob.getY(), mob.getZ(),
                            currentlyAware, false));

                    if (++processedThisBatch > 50) {
                        break;
                    }
                }

                if (!currentEntities.hasNext()) {
                    currentEntities = null;
                }
            }

            callback.accept(new SnapshotResult(worldViewData, mobs));
        }
    }

    // ══════════════════════════════════════════════════
    // Phase 3: ASYNC — Visibility analysis
    // ══════════════════════════════════════════════════

    private void analyzeAndDispatch(SnapshotResult snapshot, double fovCosine,
            double maxRadiusSq, double behindRadiusSq) {

        lastCulled.set(0);
        lastRestored.set(0);
        lastProcessed.set(0);

        // Results: mob snapshot → should AI be enabled?
        List<MobSnapshot> toCull = new ArrayList<>();
        List<MobSnapshot> toRestore = new ArrayList<>();

        for (MobSnapshot mob : snapshot.mobs) {
            if (mob.mustRestore) {
                if (!mob.currentlyAware) {
                    toRestore.add(mob);
                }
                lastProcessed.incrementAndGet();
                continue;
            }

            List<PlayerView> views = snapshot.worldViews.get(mob.worldUID);
            if (views == null)
                continue;

            boolean visibleToAny = false;
            boolean tooClose = false;
            boolean withinRange = false;

            for (PlayerView pv : views) {
                if (!pv.worldUID.equals(mob.worldUID))
                    continue;

                double dx = mob.x - pv.x;
                double dy = mob.y - pv.y;
                double dz = mob.z - pv.z;
                double distSq = dx * dx + dy * dy + dz * dz;

                if (distSq < behindRadiusSq) {
                    tooClose = true;
                    // If too close, AI should be active regardless of visibility
                    break;
                }

                if (distSq > maxRadiusSq)
                    continue;
                withinRange = true;

                double length = Math.sqrt(distSq);
                if (length == 0) {
                    visibleToAny = true;
                    break;
                }

                // Normalize direction to mob
                double nx = dx / length;
                double ny = dy / length;
                double nz = dz / length;

                // Dot product with look direction
                double dot = pv.dirX * nx + pv.dirY * ny + pv.dirZ * nz;
                if (dot >= fovCosine) {
                    visibleToAny = true;
                    break;
                }
            }

            boolean shouldEnable = visibleToAny || tooClose;

            if (shouldEnable && !mob.currentlyAware) {
                toRestore.add(mob);
            } else if (!shouldEnable && mob.currentlyAware && withinRange) {
                toCull.add(mob);
            }

            lastProcessed.incrementAndGet();
        }

        // Dispatch AI changes to main thread via WorkloadDistributor (direct async
        // submission)
        int droppedBatches = 0;
        droppedBatches += submitBatchedUpdates(toCull, false);
        droppedBatches += submitBatchedUpdates(toRestore, true);

        if (droppedBatches > 0) {
            plugin.getLogger().warning("[FrustumCuller] WorkloadDistributor queue full! Dropped " + droppedBatches
                    + " batches of AI updates.");
        }
    }

    private int submitBatchedUpdates(List<MobSnapshot> targets, boolean enableAI) {
        int batchSize = 50;
        int dropped = 0;
        List<MobSnapshot> batch = new ArrayList<>(batchSize);

        for (MobSnapshot mob : targets) {
            batch.add(mob);
            if (batch.size() >= batchSize) {
                if (!dispatchBatch(batch, enableAI))
                    dropped++;
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            if (!dispatchBatch(batch, enableAI))
                dropped++;
        }
        return dropped;
    }

    private boolean dispatchBatch(List<MobSnapshot> batch, boolean enableAI) {
        final List<MobSnapshot> currentBatch = new ArrayList<>(batch);

        if (SchedulerAdapter.isFolia()) {
            if (!plugin.isEnabled()) {
                return true;
            }
            // On Folia: dispatch per-entity to owning region thread via chunk location
            for (MobSnapshot mob : currentBatch) {
                World world = Bukkit.getWorld(mob.worldUID);
                if (world == null)
                    continue;
                int chunkX = (int) mob.x >> 4;
                int chunkZ = (int) mob.z >> 4;
                final UUID mobId = mob.uuid;
                SchedulerAdapter.runAtChunk(plugin, world, chunkX, chunkZ, () -> {
                    Entity entity = Bukkit.getEntity(mobId);
                    if (entity instanceof Mob && entity.isValid()) {
                        if (plugin.setMobAwareSafe((Mob) entity, enableAI)) {
                            if (enableAI) {
                                lastRestored.incrementAndGet();
                            } else {
                                lastCulled.incrementAndGet();
                            }
                        }
                    }
                });
            }
            return true;
        }

        return plugin.getWorkloadDistributor().addWorkload(() -> {
            for (MobSnapshot mob : currentBatch) {
                Entity entity = Bukkit.getEntity(mob.uuid);
                if (entity instanceof Mob && entity.isValid()) {
                    // Safety check: ensure chunk is loaded (use coordinates to avoid sync load)
                    Location loc = entity.getLocation();
                    if (!entity.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4))
                        continue;

                    if (plugin.setMobAwareSafe((Mob) entity, enableAI)) {
                        if (enableAI) {
                            lastRestored.incrementAndGet();
                        } else {
                            lastCulled.incrementAndGet();
                        }
                    }
                }
            }
        });
    }

    // ══════════════════════════════════════════════════
    // Data Classes (immutable, thread-safe)
    // ══════════════════════════════════════════════════

    private static class PlayerView {
        final double x, y, z;
        final double dirX, dirY, dirZ;
        final UUID worldUID;

        PlayerView(double x, double y, double z, double dirX, double dirY, double dirZ, UUID worldUID) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dirX = dirX;
            this.dirY = dirY;
            this.dirZ = dirZ;
            this.worldUID = worldUID;
        }
    }

    private static class MobSnapshot {
        final UUID uuid;
        final UUID worldUID;
        final double x, y, z;
        final boolean currentlyAware;
        final boolean mustRestore;

        MobSnapshot(UUID uuid, UUID worldUID, double x, double y, double z, boolean currentlyAware,
                boolean mustRestore) {
            this.uuid = uuid;
            this.worldUID = worldUID;
            this.x = x;
            this.y = y;
            this.z = z;
            this.currentlyAware = currentlyAware;
            this.mustRestore = mustRestore;
        }
    }

    private static class SnapshotResult {
        final Map<UUID, List<PlayerView>> worldViews;
        final List<MobSnapshot> mobs;

        SnapshotResult(Map<UUID, List<PlayerView>> worldViews, List<MobSnapshot> mobs) {
            this.worldViews = worldViews;
            this.mobs = mobs;
        }
    }

    // ══════════════════════════════════════════════════
    // Getters
    // ══════════════════════════════════════════════════

    public int getLastCulled() {
        return lastCulled.get();
    }

    public int getLastRestored() {
        return lastRestored.get();
    }

    public int getLastProcessed() {
        return lastProcessed.get();
    }
}
