package com.lesslag.monitor;

import com.lesslag.LessLag;
import com.lesslag.action.ActionExecutor;
import com.lesslag.util.SchedulerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * World Chunk Overload Protection — Detects worlds with an abnormally high
 * number of loaded chunks relative to their player count and view distance.
 *
 * Architecture: ASYNC detection → SYNC snapshot → ASYNC analysis → SYNC actions
 * 1. Periodic check runs ASYNC
 * 2. Brief SYNC dispatch to snapshot world data (chunk count, player positions)
 * 3. ASYNC analysis (sorting, distance calculations)
 * 4. SYNC dispatch of actual chunk unloads via WorkloadDistributor (batched)
 *
 * Escalation stages:
 * 1. Soft Unload: unload(false) — batched via WorkloadDistributor
 * 2. Force Unload: unload(true) — batched via WorkloadDistributor
 * 3. World Evacuation: teleport players out and unload entire world
 */
public class WorldChunkGuard {

    private final LessLag plugin;
    private final ActionExecutor actionExecutor;
    private SchedulerAdapter.TaskHandle checkTask;

    // Per-world retry counter
    private final Map<String, Integer> retryCounters = new ConcurrentHashMap<>();

    // Per-world stats
    private final Map<String, WorldChunkStatus> worldStatuses = new ConcurrentHashMap<>();

    // Stats
    private volatile long lastCheckTime = 0;
    private volatile int lastTotalUnloaded = 0;

    public WorldChunkGuard(LessLag plugin, ActionExecutor actionExecutor) {
        this.plugin = plugin;
        this.actionExecutor = actionExecutor;
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("modules.chunks.world-guard.enabled", true))
            return;

        int intervalTicks = plugin.getConfig().getInt("modules.chunks.world-guard.check-interval", 1200);

        // ── ASYNC periodic check ──
        checkTask = SchedulerAdapter.runAsyncRepeating(plugin, () -> {
            beginAsyncCheck();
        }, 200L, intervalTicks);

        plugin.getLogger().info("World Chunk Guard started ASYNC (interval: " + intervalTicks + " ticks)");
    }

    public void stop() {
        if (checkTask != null) {
            checkTask.cancel();
            checkTask = null;
        }
    }

    // ══════════════════════════════════════════════════
    // Phase 1: ASYNC → dispatch to SYNC for snapshot
    // ══════════════════════════════════════════════════

    private void beginAsyncCheck() {
        double overloadMultiplier = plugin.getConfig().getDouble("modules.chunks.world-guard.overload-multiplier", 2.0);
        int maxRetries = plugin.getConfig().getInt("modules.chunks.world-guard.max-retries", 5);
        boolean notify = plugin.getConfig().getBoolean("modules.chunks.world-guard.notify", true);
        String evacuateWorldName = plugin.getConfig().getString("modules.chunks.world-guard.evacuate-world", "world");
        int maxChunksPerPlayer = plugin.getConfig().getInt("modules.chunks.world-guard.max-chunks-per-player", 200);
        List<String> actions = plugin.getConfig().getStringList("modules.chunks.world-guard.actions");
        List<String> ignoredWorlds = plugin.getConfig().getStringList("modules.chunks.world-guard.ignored-worlds");
        boolean keepSpawnLoaded = plugin.getConfig().getBoolean("modules.chunks.world-guard.keep-spawn-loaded", true);

        // Brief SYNC dispatch to collect world snapshots
        SchedulerAdapter.runGlobal(plugin, () -> {
            List<WorldSnapshot> snapshots = collectSnapshots(ignoredWorlds, keepSpawnLoaded);

            // Back to ASYNC for analysis
            SchedulerAdapter.runAsync(plugin, () -> {
                analyzeSnapshots(snapshots, overloadMultiplier, maxRetries, notify, evacuateWorldName,
                        maxChunksPerPlayer, actions);
            });
        });
    }

    // ══════════════════════════════════════════════════
    // Phase 2: SYNC — Quick snapshot collection
    // ══════════════════════════════════════════════════

    /**
     * Collect lightweight snapshots of all worlds.
     * Runs on MAIN THREAD — kept as fast as possible.
     */
    private List<WorldSnapshot> collectSnapshots(List<String> ignoredWorlds, boolean checkSpawnRules) {
        List<WorldSnapshot> snapshots = new ArrayList<>();

        for (World world : Bukkit.getWorlds()) {
            if (ignoredWorlds.contains(world.getName()))
                continue;

            List<Player> players = world.getPlayers();
            int playerCount = players.size();
            int viewDistance = world.getViewDistance();
            Chunk[] loadedChunks = world.getLoadedChunks();
            int chunkCount = loadedChunks.length;

            // Snapshot player positions for async distance calculations
            List<double[]> playerPositions = new ArrayList<>(playerCount);
            Set<Long> playerChunkKeys = new HashSet<>();
            for (Player player : players) {
                Location loc = player.getLocation();
                playerPositions.add(new double[] { loc.getX(), loc.getZ() });
                int cx = loc.getBlockX() >> 4;
                int cz = loc.getBlockZ() >> 4;
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        playerChunkKeys.add(chunkKey(cx + dx, cz + dz));
                    }
                }
            }

            // Snapshot spawn chunk keys if protected
            // Snapshot chunk coordinates (lightweight — just ints)
            List<int[]> chunkCoords = new ArrayList<>(chunkCount);
            for (Chunk chunk : loadedChunks) {
                chunkCoords.add(new int[] { chunk.getX(), chunk.getZ() });
            }

            Set<Long> spawnChunkKeys = new HashSet<>();
            if (checkSpawnRules && world.getKeepSpawnInMemory()) {
                Location spawn = world.getSpawnLocation();
                int scx = spawn.getBlockX() >> 4;
                int scz = spawn.getBlockZ() >> 4;
                // Bukkit usually keeps a 16x16 or 11x11 area loaded around spawn. We'll protect
                // a generous 16 chunk radius to be safe with MV-Core
                int spawnRadius = 12;
                for (int dx = -spawnRadius; dx <= spawnRadius; dx++) {
                    for (int dz = -spawnRadius; dz <= spawnRadius; dz++) {
                        spawnChunkKeys.add(chunkKey(scx + dx, scz + dz));
                    }
                }
            }

            snapshots.add(new WorldSnapshot(
                    world.getName(), playerCount, viewDistance, chunkCount,
                    playerPositions, playerChunkKeys, chunkCoords, spawnChunkKeys));
        }

        return snapshots;
    }

    // ══════════════════════════════════════════════════
    // Phase 3: ASYNC — Analysis & sorting
    // ══════════════════════════════════════════════════

    /**
     * Analyze snapshots asynchronously. Heavy sorting/distance calculations
     * happen here, off the main thread.
     */
    private void analyzeSnapshots(List<WorldSnapshot> snapshots, double overloadMultiplier,
            int maxRetries, boolean notify, String evacuateWorldName,
            int maxChunksPerPlayer, List<String> actions) {
        for (WorldSnapshot snap : snapshots) {
            int expectedMax;
            if (maxChunksPerPlayer > 0) {
                expectedMax = Math.max(snap.playerCount * maxChunksPerPlayer, 100);
            } else {
                int chunksPerPlayer = (snap.viewDistance * 2 + 1) * (snap.viewDistance * 2 + 1);
                expectedMax = Math.max(snap.playerCount * chunksPerPlayer, 100);
            }

            boolean overloaded = snap.chunkCount > (int) (expectedMax * overloadMultiplier);

            WorldChunkStatus status = new WorldChunkStatus(
                    snap.worldName, snap.playerCount, snap.viewDistance,
                    snap.chunkCount, expectedMax, overloaded);
            worldStatuses.put(snap.worldName, status);

            if (!overloaded) {
                retryCounters.remove(snap.worldName);
                continue;
            }

            // ── OVERLOADED — prepare unload plan ASYNC ──
            int retries = retryCounters.getOrDefault(snap.worldName, 0);
            boolean forceMode = retries > 0;
            int excess = snap.chunkCount - expectedMax;

            if (retries == 0 && actions != null && !actions.isEmpty()) {
                SchedulerAdapter.runGlobal(plugin, () -> {
                    World w = Bukkit.getWorld(snap.worldName);
                    if (w != null) {
                        for (String action : actions) {
                            if (action.equalsIgnoreCase("reduce-view-distance")) {
                                actionExecutor.reduceViewDistance(w);
                            }
                        }
                    }
                });
            }

            plugin.getLogger().warning("[WorldChunkGuard] " + snap.worldName
                    + " OVERLOADED: " + snap.chunkCount + " chunks loaded"
                    + " (expected max: approx. " + expectedMax + ", players: " + snap.playerCount
                    + ", VD: " + snap.viewDistance + ") [attempt " + (retries + 1) + "/" + maxRetries + "]");

            // Check if unloading is enabled in actions (default true if list is empty or
            // contains it)
            boolean doUnload = actions == null || actions.isEmpty() || actions.contains("unload-unused");

            if (!doUnload) {
                // If unloading is disabled, we just rely on other actions (like view distance
                // reduction)
                // But we still track retries to eventually evacuate if needed?
                // Or maybe just skip? For now, we proceed to ensure logic flows, but candidates
                // will be empty
                // if we don't build them.
                // Let's just return here to avoid unloading if not requested.
                // But we need to update status/retries?
                // If we don't unload, we can't reduce chunk count directly.
                // We'll increment retries later if still overloaded.
                // Let's assume we skip building candidates.
            }

            // Build sorted unload candidates ASYNC (heavy sort is off main thread)
            List<int[]> candidates = new ArrayList<>();
            if (doUnload) {
                for (int[] coord : snap.chunkCoords) {
                    long key = chunkKey(coord[0], coord[1]);
                    if (snap.playerChunkKeys.contains(key))
                        continue;
                    if (snap.spawnChunkKeys.contains(key))
                        continue; // Protected spawn chunk

                    candidates.add(coord);
                }
            }

            // Sort by distance from nearest player (furthest first) — ASYNC
            if (doUnload && !snap.playerPositions.isEmpty()) {
                candidates.sort((a, b) -> {
                    double distA = nearestPlayerDistSq(a, snap.playerPositions);
                    double distB = nearestPlayerDistSq(b, snap.playerPositions);
                    return Double.compare(distB, distA); // furthest first
                });
            }

            // Trim to only what we need to unload
            if (candidates.size() > excess) {
                candidates = candidates.subList(0, excess);
            }

            // Dispatch chunk unloads to main thread via WorkloadDistributor
            final List<int[]> unloadTargets = new ArrayList<>(candidates);
            final String worldName = snap.worldName;
            final boolean useForce = forceMode;
            final int retryCount = retries;
            final AtomicInteger unloadedCount = new AtomicInteger(0);
            final AtomicInteger failedCount = new AtomicInteger(0);

            // Submit batched unload work to main thread
            SchedulerAdapter.runGlobal(plugin, () -> {
                World world = Bukkit.getWorld(worldName);
                if (world == null)
                    return;

                int batchSize = 20;
                List<int[]> batch = new ArrayList<>(batchSize);

                if (SchedulerAdapter.isFolia()) {
                    // On Folia: dispatch each chunk unload to its owning region thread
                    for (int[] coord : unloadTargets) {
                        final int cx = coord[0];
                        final int cz = coord[1];
                        Chunk chunk = world.getChunkAt(cx, cz);
                        plugin.getWorkloadDistributor().addChunkWorkload(chunk, () -> {
                            if (!chunk.isLoaded()) return;
                            try {
                                boolean success = chunk.unload(useForce);
                                if (success)
                                    unloadedCount.incrementAndGet();
                                else
                                    failedCount.incrementAndGet();
                            } catch (UnsupportedOperationException e) {
                                failedCount.incrementAndGet();
                            }
                        });
                    }
                } else {
                    // On Paper/Spigot: batch chunks for tick-spreading
                    for (int[] coord : unloadTargets) {
                        batch.add(coord);
                        if (batch.size() >= batchSize) {
                            final List<int[]> currentBatch = new ArrayList<>(batch);
                            plugin.getWorkloadDistributor().addWorkload(() -> {
                                World w = Bukkit.getWorld(worldName);
                                if (w == null)
                                    return;
                                for (int[] c : currentBatch) {
                                    Chunk chunk = w.getChunkAt(c[0], c[1]);
                                    if (!chunk.isLoaded())
                                        continue;
                                    boolean success = chunk.unload(useForce);
                                    if (success)
                                        unloadedCount.incrementAndGet();
                                    else
                                        failedCount.incrementAndGet();
                                }
                            });
                            batch.clear();
                        }
                    }

                    if (!batch.isEmpty()) {
                        final List<int[]> currentBatch = new ArrayList<>(batch);
                        plugin.getWorkloadDistributor().addWorkload(() -> {
                            World w = Bukkit.getWorld(worldName);
                            if (w == null)
                                return;
                            for (int[] c : currentBatch) {
                                Chunk chunk = w.getChunkAt(c[0], c[1]);
                                if (!chunk.isLoaded())
                                    continue;
                                boolean success = chunk.unload(useForce);
                                if (success)
                                    unloadedCount.incrementAndGet();
                                else
                                    failedCount.incrementAndGet();
                            }
                        });
                    }
                }

                // Schedule a follow-up check by adding it to the END of the workload queue
                plugin.getWorkloadDistributor().addWorkload(() -> {
                    World w = Bukkit.getWorld(worldName);
                    if (w == null)
                        return;

                    int unloaded = unloadedCount.get();
                    int failed = failedCount.get();
                    int remaining = w.getLoadedChunks().length;
                    boolean meaningfulReduction = remaining < (int) (expectedMax * overloadMultiplier);

                    WorldChunkStatus s = worldStatuses.get(worldName);

                    if (meaningfulReduction) {
                        retryCounters.remove(worldName);
                        if (s != null)
                            s.lastAction = "Unloaded " + unloaded + " chunks";

                        if (notify) {
                            notifyAdmins("<yellow>⚠ <gray>[WorldChunkGuard] <white>" + worldName
                                    + "<gray>: Unloaded <yellow>" + unloaded + " <gray>excess chunks"
                                    + " (<white>" + remaining + "<gray> remaining)");
                        }
                        plugin.getLogger().info("[WorldChunkGuard] " + worldName
                                + ": Successfully unloaded " + unloaded + " chunks"
                                + " (" + remaining + " remaining)");
                    } else {
                        int newRetries = retryCount + 1;
                        retryCounters.put(worldName, newRetries);

                        if (newRetries >= maxRetries) {
                            if (s != null)
                                s.lastAction = "EVACUATING — chunk unload blocked";

                            plugin.getLogger().severe("[WorldChunkGuard] " + worldName
                                    + ": CHUNK UNLOAD BLOCKED after " + maxRetries + " attempts!");

                            if (notify) {
                                notifyAdmins("<dark_red><bold>⚠ CRITICAL <red>[WorldChunkGuard] <white>" + worldName
                                        + " <red>chunk overload cannot be resolved!"
                                        + "\n<red>  Evacuating players to <white>" + evacuateWorldName + "<red>...");
                            }

                            evacuateWorld(w, evacuateWorldName);
                            retryCounters.remove(worldName);
                        } else {
                            if (s != null)
                                s.lastAction = "Unload partially blocked (" + newRetries + "/" + maxRetries + ")";

                            if (notify) {
                                notifyAdmins("<red>⚠ <gray>[WorldChunkGuard] <white>" + worldName
                                        + "<red>: Chunk unload partially blocked!"
                                        + " <gray>Attempt <yellow>" + newRetries + "<gray>/<red>" + maxRetries
                                        + " <dark_gray>(unloaded: " + unloaded + ", failed: " + failed + ")");
                            }
                        }
                    }

                    lastTotalUnloaded = unloaded;
                });
            });
        }

        lastCheckTime = System.currentTimeMillis();
    }

    // ══════════════════════════════════════════════════
    // Evacuation (SYNC — must be main thread)
    // ══════════════════════════════════════════════════

    private void evacuateWorld(World world, String targetWorldName) {
        World targetWorld = Bukkit.getWorld(targetWorldName);

        if (targetWorld == null || targetWorld.equals(world)) {
            for (World w : Bukkit.getWorlds()) {
                if (!w.equals(world)) {
                    targetWorld = w;
                    break;
                }
            }
        }

        if (targetWorld == null) {
            plugin.getLogger().severe("[WorldChunkGuard] Cannot evacuate " + world.getName()
                    + " — no other world available!");
            return;
        }

        Location spawnLoc = targetWorld.getSpawnLocation();
        List<Player> playersToMove = new ArrayList<>(world.getPlayers());

        for (Player player : playersToMove) {
            try {
                SchedulerAdapter.teleportEntity(plugin, player, spawnLoc);
                LessLag.sendMessage(player,
                        "<red><bold>⚠ <white>You have been evacuated from <yellow>" + world.getName()
                                + " <white>due to critical chunk overload.");
            } catch (Exception e) {
                plugin.getLogger().warning("[WorldChunkGuard] Failed to teleport "
                        + player.getName() + ": " + e.getMessage());
            }
        }

        final World finalTargetWorld = targetWorld;
        SchedulerAdapter.runGlobalDelayed(plugin, () -> {
            if (world.getPlayers().isEmpty()) {
                boolean unloaded = false;

                // Folia does not support Bukkit.unloadWorld() — skip it entirely
                if (!SchedulerAdapter.isFolia()) {
                    try {
                        unloaded = Bukkit.unloadWorld(world, true);
                    } catch (UnsupportedOperationException e) {
                        plugin.getLogger().warning("[WorldChunkGuard] Bukkit.unloadWorld() not supported on this server.");
                    }
                }

                if (unloaded) {
                    plugin.getLogger().info("[WorldChunkGuard] World " + world.getName()
                            + " fully unloaded after evacuation.");
                    notifyAdmins("<green>✔ <gray>[WorldChunkGuard] World <white>" + world.getName()
                            + " <gray>fully unloaded. <yellow>" + playersToMove.size()
                            + " <gray>player(s) moved to <white>" + finalTargetWorld.getName());
                } else {
                    // Default world or Folia — force-unload chunks via WorkloadDistributor
                    Chunk[] chunks = world.getLoadedChunks();
                    AtomicInteger forceCount = new AtomicInteger(0);

                    if (SchedulerAdapter.isFolia()) {
                        // On Folia: dispatch per-chunk to owning region thread
                        for (Chunk chunk : chunks) {
                            plugin.getWorkloadDistributor().addChunkWorkload(chunk, () -> {
                                try {
                                    if (chunk.isLoaded() && chunk.unload(true)) {
                                        forceCount.incrementAndGet();
                                    }
                                } catch (UnsupportedOperationException ignored) {}
                            });
                        }
                    } else {
                        // On Paper/Spigot: batch chunks for tick-spreading
                        int batchSize = 20;
                        List<Chunk> batch = new ArrayList<>(batchSize);

                        for (Chunk chunk : chunks) {
                            batch.add(chunk);
                            if (batch.size() >= batchSize) {
                                final List<Chunk> currentBatch = new ArrayList<>(batch);
                                plugin.getWorkloadDistributor().addWorkload(() -> {
                                    for (Chunk c : currentBatch) {
                                        if (c.isLoaded() && c.unload(true)) {
                                            forceCount.incrementAndGet();
                                        }
                                    }
                                });
                                batch.clear();
                            }
                        }

                        if (!batch.isEmpty()) {
                            final List<Chunk> currentBatch = new ArrayList<>(batch);
                            plugin.getWorkloadDistributor().addWorkload(() -> {
                                for (Chunk c : currentBatch) {
                                    if (c.isLoaded() && c.unload(true)) {
                                        forceCount.incrementAndGet();
                                    }
                                }
                            });
                        }
                    }

                    plugin.getWorkloadDistributor().addWorkload(() -> {
                        plugin.getLogger().info("[WorldChunkGuard] Force-unloaded " + forceCount.get()
                                + " chunks from " + world.getName() + " (world kept loaded)");
                        notifyAdmins("<yellow>⚠ <gray>[WorldChunkGuard] <white>" + world.getName()
                                + " <gray>cannot be fully unloaded (default world)."
                                + " Force-unloaded <yellow>" + forceCount.get() + " <gray>chunks.");
                    });
                }
            }
        }, 20L);
    }

    // ══════════════════════════════════════════════════
    // Utilities
    // ══════════════════════════════════════════════════

    private void notifyAdmins(String message) {
        com.lesslag.util.NotificationHelper.notifyAdmins(message);
    }

    private long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /**
     * Distance calculation using snapshot data — safe for ASYNC.
     */
    private double nearestPlayerDistSq(int[] chunkCoord, List<double[]> playerPositions) {
        double cx = (chunkCoord[0] << 4) + 8;
        double cz = (chunkCoord[1] << 4) + 8;
        double nearest = Double.MAX_VALUE;
        for (double[] pos : playerPositions) {
            double dx = cx - pos[0];
            double dz = cz - pos[1];
            double distSq = dx * dx + dz * dz;
            if (distSq < nearest)
                nearest = distSq;
        }
        return nearest;
    }

    // ══════════════════════════════════════════════════
    // Getters
    // ══════════════════════════════════════════════════

    public long getLastCheckTime() {
        return lastCheckTime;
    }

    public int getLastTotalUnloaded() {
        return lastTotalUnloaded;
    }

    public Map<String, WorldChunkStatus> getWorldStatuses() {
        return Collections.unmodifiableMap(worldStatuses);
    }

    // ══════════════════════════════════════════════════
    // Data Classes
    // ══════════════════════════════════════════════════

    /**
     * Lightweight immutable snapshot — safe across threads.
     */
    private static class WorldSnapshot {
        final String worldName;
        final int playerCount;
        final int viewDistance;
        final int chunkCount;
        final List<double[]> playerPositions;
        final Set<Long> playerChunkKeys;
        final List<int[]> chunkCoords;
        final Set<Long> spawnChunkKeys;

        WorldSnapshot(String worldName, int playerCount, int viewDistance, int chunkCount,
                List<double[]> playerPositions, Set<Long> playerChunkKeys, List<int[]> chunkCoords,
                Set<Long> spawnChunkKeys) {
            this.worldName = worldName;
            this.playerCount = playerCount;
            this.viewDistance = viewDistance;
            this.chunkCount = chunkCount;
            this.playerPositions = playerPositions;
            this.playerChunkKeys = playerChunkKeys;
            this.chunkCoords = chunkCoords;
            this.spawnChunkKeys = spawnChunkKeys;
        }
    }

    public static class WorldChunkStatus {
        public final String worldName;
        public final int playerCount;
        public final int viewDistance;
        public final int loadedChunks;
        public final int expectedMax;
        public final boolean overloaded;
        public volatile String lastAction = "OK";

        public WorldChunkStatus(String worldName, int playerCount, int viewDistance,
                int loadedChunks, int expectedMax, boolean overloaded) {
            this.worldName = worldName;
            this.playerCount = playerCount;
            this.viewDistance = viewDistance;
            this.loadedChunks = loadedChunks;
            this.expectedMax = expectedMax;
            this.overloaded = overloaded;
        }
    }
}
