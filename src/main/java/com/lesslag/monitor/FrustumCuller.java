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

    // Stats
    private final AtomicInteger lastCulled = new AtomicInteger(0);
    private final AtomicInteger lastRestored = new AtomicInteger(0);
    private final AtomicInteger lastProcessed = new AtomicInteger(0);

    public FrustumCuller(LessLag plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    private void loadConfig() {
        maxRadius = plugin.getConfig().getDouble("ai-optimization.active-radius", 48);
        fovDegrees = plugin.getConfig().getDouble("ai-optimization.frustum-culling.fov-degrees", 110);
        behindRadius = plugin.getConfig().getDouble("ai-optimization.frustum-culling.behind-safe-radius", 12);
        intervalTicks = plugin.getConfig().getInt("ai-optimization.frustum-culling.interval-ticks", 40);
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("ai-optimization.frustum-culling.enabled", true))
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

        // Brief SYNC snapshot
        Bukkit.getScheduler().runTask(plugin, () -> {
            SnapshotResult snapshot = collectSnapshot();
            if (snapshot.mobs.isEmpty())
                return;

            // Back to ASYNC for heavy calculations
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                analyzeAndDispatch(snapshot, fovCosine, maxRadiusSq, behindRadiusSq);
            });
        });
    }

    // ══════════════════════════════════════════════════
    // Phase 2: SYNC — Quick snapshot
    // ══════════════════════════════════════════════════

    private SnapshotResult collectSnapshot() {
        Set<String> protectedTypes = loadProtectedTypes();
        Map<UUID, List<PlayerView>> worldViewData = new HashMap<>();
        List<MobSnapshot> mobs = new ArrayList<>();

        for (World world : Bukkit.getWorlds()) {
            List<Player> players = world.getPlayers();
            if (players.isEmpty())
                continue;

            // Snapshot player views
            List<PlayerView> views = new ArrayList<>(players.size());
            for (Player player : players) {
                Location eye = player.getEyeLocation();
                views.add(new PlayerView(
                        eye.getX(), eye.getY(), eye.getZ(),
                        eye.getDirection().getX(), eye.getDirection().getY(), eye.getDirection().getZ(),
                        world.getUID()));
            }
            worldViewData.put(world.getUID(), views);

            // Snapshot mob data
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Mob))
                    continue;
                if (protectedTypes.contains(entity.getType().name()))
                    continue;
                if (LessLag.hasCustomName(entity))
                    continue;
                if (entity instanceof org.bukkit.entity.Tameable
                        && ((org.bukkit.entity.Tameable) entity).isTamed())
                    continue;

                Mob mob = (Mob) entity;
                Location loc = mob.getLocation();
                boolean currentlyAware = plugin.isMobAwareSafe(mob);

                mobs.add(new MobSnapshot(
                        mob.getUniqueId(), world.getUID(),
                        loc.getX(), loc.getY(), loc.getZ(),
                        currentlyAware));
            }
        }

        return new SnapshotResult(worldViewData, mobs);
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
            boolean withinRange = false;

            for (PlayerView pv : views) {
                if (!pv.worldUID.equals(mob.worldUID))
                    continue;

                double dx = mob.x - pv.x;
                double dy = mob.y - pv.y;
                double dz = mob.z - pv.z;
                double distSq = dx * dx + dy * dy + dz * dz;

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

            if (withinRange && !visibleToAny) {
                // Check behind-safe-radius
                boolean tooClose = false;
                for (PlayerView pv : views) {
                    if (!pv.worldUID.equals(mob.worldUID))
                        continue;
                    double dx = mob.x - pv.x;
                    double dy = mob.y - pv.y;
                    double dz = mob.z - pv.z;
                    if (dx * dx + dy * dy + dz * dz < behindRadiusSq) {
                        tooClose = true;
                        break;
                    }
                }

                if (!tooClose && mob.currentlyAware) {
                    toCull.add(mob.uuid);
                }
            } else if (visibleToAny && !mob.currentlyAware) {
                toRestore.add(mob.uuid);
            }

            lastProcessed.incrementAndGet();
        }

        // Dispatch AI changes to main thread via WorkloadDistributor (batched)
        if (!toCull.isEmpty() || !toRestore.isEmpty()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                // Batch cull
                for (UUID uuid : toCull) {
                    plugin.getWorkloadDistributor().addWorkload(() -> {
                        Entity entity = Bukkit.getEntity(uuid);
                        if (entity instanceof Mob && entity.isValid()) {
                            if (plugin.setMobAwareSafe((Mob) entity, false)) {
                                lastCulled.incrementAndGet();
                            }
                        }
                    });
                }

                // Batch restore
                for (UUID uuid : toRestore) {
                    plugin.getWorkloadDistributor().addWorkload(() -> {
                        Entity entity = Bukkit.getEntity(uuid);
                        if (entity instanceof Mob && entity.isValid()) {
                            if (plugin.setMobAwareSafe((Mob) entity, true)) {
                                lastRestored.incrementAndGet();
                            }
                        }
                    });
                }
            });
        }
    }

    private Set<String> loadProtectedTypes() {
        Set<String> set = new HashSet<>();
        for (String s : plugin.getConfig().getStringList("ai-optimization.protected"))
            set.add(s.toUpperCase());
        return set;
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
