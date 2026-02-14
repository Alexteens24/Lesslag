package com.lesslag.monitor;

import com.lesslag.LessLag;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

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
    private BukkitTask task;

    // Config (cached)
    private double maxRadius;
    private double fovDegrees;
    private double behindRadius;
    private int intervalTicks;
    private Set<String> protectedTypes = Collections.emptySet();

    // Stats
    private final AtomicInteger lastCulled = new AtomicInteger(0);
    private final AtomicInteger lastRestored = new AtomicInteger(0);
    private final AtomicInteger lastProcessed = new AtomicInteger(0);

    public FrustumCuller(LessLag plugin) {
        this.plugin = plugin;
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
        task = new BukkitRunnable() {
            @Override
            public void run() {
                beginAsyncCull();
            }
        }.runTaskTimerAsynchronously(plugin, 100L, intervalTicks);

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
        double fovCosine = Math.cos(Math.toRadians(fovDegrees / 2.0));
        double maxRadiusSq = maxRadius * maxRadius;
        double behindRadiusSq = behindRadius * behindRadius;

        // Start incremental snapshot builder on main thread
        Bukkit.getScheduler().runTask(plugin, () -> {
            new IncrementalCullSnapshotBuilder(snapshot -> {
                if (snapshot.mobs.isEmpty())
                    return;

                // Back to ASYNC for heavy calculations
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    analyzeAndDispatch(snapshot, fovCosine, maxRadiusSq, behindRadiusSq);
                });
            }).start();
        });
    }

    // ══════════════════════════════════════════════════
    // Phase 2: SYNC — Incremental snapshot
    // ══════════════════════════════════════════════════

    private class IncrementalCullSnapshotBuilder implements Runnable {
        private final java.util.function.Consumer<SnapshotResult> callback;
        private final List<Player> allPlayers;
        private int playerIndex = 0;

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
            long stopTime = System.nanoTime() + MAX_NANOS_PER_TICK;

            while (playerIndex < allPlayers.size()) {
                if (System.nanoTime() > stopTime) {
                    Bukkit.getScheduler().runTask(plugin, this);
                    return;
                }

                Player player = allPlayers.get(playerIndex++);
                if (!player.isOnline()) continue;

                World world = player.getWorld();
                Location eye = player.getEyeLocation();

                worldViewData.computeIfAbsent(world.getUID(), k -> new ArrayList<>())
                        .add(new PlayerView(
                        eye.getX(), eye.getY(), eye.getZ(),
                        eye.getDirection().getX(), eye.getDirection().getY(), eye.getDirection().getZ(),
                        world.getUID()));

                // Iterate nearby entities
                for (Entity entity : player.getNearbyEntities(maxRadius, maxRadius, maxRadius)) {
                    if (!(entity instanceof Mob)) continue;
                    Mob mob = (Mob) entity;

                    // Optimization: Use distanceSquared() for precise range check
                    if (player.getLocation().distanceSquared(mob.getLocation()) > maxRadius * maxRadius)
                        continue;

                    if (!processedMobs.add(mob.getUniqueId())) continue;

                    if (protectedTypes.contains(mob.getType().name()))
                        continue;
                    if (LessLag.hasCustomName(mob))
                        continue;
                    if (mob.hasMetadata("LessLag.DensitySuppressed"))
                        continue;
                    if (mob.hasMetadata("LessLag.VillagerOptimized"))
                        continue;
                    if (mob instanceof org.bukkit.entity.Tameable
                            && ((org.bukkit.entity.Tameable) mob).isTamed())
                        continue;

                    Location loc = mob.getLocation();
                    boolean currentlyAware = plugin.isMobAwareSafe(mob);

                    mobs.add(new MobSnapshot(
                            mob.getUniqueId(), world.getUID(),
                            loc.getX(), loc.getY(), loc.getZ(),
                            currentlyAware));
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

        // Results: mob UUID → should AI be enabled?
        List<UUID> toCull = new ArrayList<>();
        List<UUID> toRestore = new ArrayList<>();

        for (MobSnapshot mob : snapshot.mobs) {
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
                toRestore.add(mob.uuid);
            } else if (!shouldEnable && mob.currentlyAware && withinRange) {
                toCull.add(mob.uuid);
            }

            lastProcessed.incrementAndGet();
        }

        // Dispatch AI changes to main thread via WorkloadDistributor (direct async submission)
        int droppedBatches = 0;
        droppedBatches += submitBatchedUpdates(toCull, false);
        droppedBatches += submitBatchedUpdates(toRestore, true);

        if (droppedBatches > 0) {
            plugin.getLogger().warning("[FrustumCuller] WorkloadDistributor queue full! Dropped " + droppedBatches + " batches of AI updates.");
        }
    }

    private int submitBatchedUpdates(List<UUID> targets, boolean enableAI) {
        int batchSize = 50;
        int dropped = 0;
        List<UUID> batch = new ArrayList<>(batchSize);

        for (UUID uuid : targets) {
            batch.add(uuid);
            if (batch.size() >= batchSize) {
                if (!dispatchBatch(batch, enableAI)) dropped++;
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            if (!dispatchBatch(batch, enableAI)) dropped++;
        }
        return dropped;
    }

    private boolean dispatchBatch(List<UUID> batch, boolean enableAI) {
        final List<UUID> currentBatch = new ArrayList<>(batch);
        return plugin.getWorkloadDistributor().addWorkload(() -> {
            for (UUID id : currentBatch) {
                Entity entity = Bukkit.getEntity(id);
                if (entity instanceof Mob && entity.isValid()) {
                    // Safety check: ensure chunk is loaded (use coordinates to avoid sync load)
                    Location loc = entity.getLocation();
                    if (!entity.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) continue;

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

        MobSnapshot(UUID uuid, UUID worldUID, double x, double y, double z, boolean currentlyAware) {
            this.uuid = uuid;
            this.worldUID = worldUID;
            this.x = x;
            this.y = y;
            this.z = z;
            this.currentlyAware = currentlyAware;
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
