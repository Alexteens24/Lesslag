package com.lesslag.monitor;

import com.lesslag.LessLag;
import com.lesslag.action.ActionExecutor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

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
    private BukkitTask checkTask;

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
        checkTask = new BukkitRunnable() {
            @Override
            public void run() {
                beginAsyncCheck();
            }
        }.runTaskTimerAsynchronously(plugin, 200L, intervalTicks);

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

        // Brief SYNC dispatch to collect world snapshots
        Bukkit.getScheduler().runTask(plugin, () -> {
            List<WorldSnapshot> snapshots = collectSnapshots();

            // Back to ASYNC for analysis
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
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
    private List<WorldSnapshot> collectSnapshots() {
        List<WorldSnapshot> snapshots = new ArrayList<>();

        for (World world : Bukkit.getWorlds()) {
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

            // Snapshot chunk coordinates (lightweight — just ints)
            List<int[]> chunkCoords = new ArrayList<>(chunkCount);
            for (Chunk chunk : loadedChunks) {
                chunkCoords.add(new int[] { chunk.getX(), chunk.getZ() });
            }

            snapshots.add(new WorldSnapshot(
                    world.getName(), playerCount, viewDistance, chunkCount,
                    playerPositions, playerChunkKeys, chunkCoords));
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
                Bukkit.getScheduler().runTask(plugin, () -> {
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
                    + " (expected max: ~" + expectedMax + ", players: " + snap.playerCount
                    + ", VD: " + snap.viewDistance + ") [attempt " + (retries + 1) + "/" + maxRetries + "]");

            // Check if unloading is enabled in actions (default true if list is empty or
            // contains it)
            boolean doUnload = actions == null || actions.isEmpty() || actions.contains("unload-unused");

            if (!doUnload) {
                // If unloading is disabled, we just rely on other actions (like view distance reduction)
                // But we still track retries to eventually evacuate if needed?
                // Or maybe just skip? For now, we proceed to ensure logic flows, but candidates will be empty
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
            Bukkit.getScheduler().runTask(plugin, () -> {
                World world = Bukkit.getWorld(worldName);
                if (world == null)
                    return;

                for (int[] coord : unloadTargets) {
                    plugin.getWorkloadDistributor().addWorkload(() -> {
                        World w = Bukkit.getWorld(worldName);
                        if (w == null)
                            return;

                        Chunk chunk = w.getChunkAt(coord[0], coord[1]);
                        if (!chunk.isLoaded())
                            return;

                        boolean success = chunk.unload(useForce);
                        if (success) {
                            unloadedCount.incrementAndGet();
                        } else {
                            failedCount.incrementAndGet();
                        }
                    });
                }

                // Schedule a follow-up check after workload completes
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
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
                            notifyAdmins("&e⚠ &7[WorldChunkGuard] &f" + worldName
                                    + "&7: Unloaded &e" + unloaded + " &7excess chunks"
                                    + " (&f" + remaining + "&7 remaining)");
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
                                notifyAdmins("&4&l⚠ CRITICAL &c[WorldChunkGuard] &f" + worldName
                                        + " &cchunk overload cannot be resolved!"
                                        + "\n&c  Evacuating players to &f" + evacuateWorldName + "&c...");
                            }

                            evacuateWorld(w, evacuateWorldName);
                            retryCounters.remove(worldName);
                        } else {
                            if (s != null)
                                s.lastAction = "Unload partially blocked (" + newRetries + "/" + maxRetries + ")";

                            if (notify) {
                                notifyAdmins("&c⚠ &7[WorldChunkGuard] &f" + worldName
                                        + "&c: Chunk unload partially blocked!"
                                        + " &7Attempt &e" + newRetries + "&7/&c" + maxRetries
                                        + " &8(unloaded: " + unloaded + ", failed: " + failed + ")");
                            }
                        }
                    }

                    lastTotalUnloaded = unloaded;
                }, 40L); // 2 seconds later — give WorkloadDistributor time
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
                player.teleport(spawnLoc);
                LessLag.sendMessage(player,
                        "&c&l⚠ &fYou have been evacuated from &e" + world.getName()
                                + " &fdue to critical chunk overload.");
            } catch (Exception e) {
                plugin.getLogger().warning("[WorldChunkGuard] Failed to teleport "
                        + player.getName() + ": " + e.getMessage());
            }
        }

        final World finalTargetWorld = targetWorld;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (world.getPlayers().isEmpty()) {
                boolean unloaded = Bukkit.unloadWorld(world, true);
                if (unloaded) {
                    plugin.getLogger().info("[WorldChunkGuard] World " + world.getName()
                            + " fully unloaded after evacuation.");
                    notifyAdmins("&a✔ &7[WorldChunkGuard] World &f" + world.getName()
                            + " &7fully unloaded. &e" + playersToMove.size()
                            + " &7player(s) moved to &f" + finalTargetWorld.getName());
                } else {
                    // Default world — force-unload chunks via WorkloadDistributor
                    Chunk[] chunks = world.getLoadedChunks();
                    AtomicInteger forceCount = new AtomicInteger(0);
                    for (Chunk chunk : chunks) {
                        plugin.getWorkloadDistributor().addWorkload(() -> {
                            if (chunk.isLoaded() && chunk.unload(true)) {
                                forceCount.incrementAndGet();
                            }
                        });
                    }
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        plugin.getLogger().info("[WorldChunkGuard] Force-unloaded " + forceCount.get()
                                + " chunks from " + world.getName() + " (world kept loaded)");
                        notifyAdmins("&e⚠ &7[WorldChunkGuard] &f" + world.getName()
                                + " &7cannot be fully unloaded (default world)."
                                + " Force-unloaded &e" + forceCount.get() + " &7chunks.");
                    }, 60L);
                }
            }
        }, 20L);
    }

    // ══════════════════════════════════════════════════
    // Utilities
    // ══════════════════════════════════════════════════

    private void notifyAdmins(String message) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("lesslag.notify")) {
                LessLag.sendMessage(player, plugin.getPrefix() + message);
            }
        }
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

        WorldSnapshot(String worldName, int playerCount, int viewDistance, int chunkCount,
                List<double[]> playerPositions, Set<Long> playerChunkKeys, List<int[]> chunkCoords) {
            this.worldName = worldName;
            this.playerCount = playerCount;
            this.viewDistance = viewDistance;
            this.chunkCount = chunkCount;
            this.playerPositions = playerPositions;
            this.playerChunkKeys = playerChunkKeys;
            this.chunkCoords = chunkCoords;
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
