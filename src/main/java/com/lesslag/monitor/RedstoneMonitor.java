package com.lesslag.monitor;

import com.lesslag.LessLag;
import com.lesslag.util.NotificationHelper;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Redstone Suppressor & Limiter
 * 
 * Features:
 * 1. Chunk Suppression: Freezes chunks exceeding max activations.
 * 2. Advanced: Frequency Limiter (per block) - Throttles fast clocks.
 * 3. Advanced: Piston Limiter (per chunk/tick) - Prevents massive piston
 * machines.
 *
 * Optimization Note:
 * This class runs entirely on the main server thread (handling Bukkit events).
 * We use standard HashMaps instead of ConcurrentHashMaps to save CPU cycles.
 */
public class RedstoneMonitor implements Listener {

    private final LessLag plugin;
    private BukkitTask cleanupTask;
    private BukkitTask pistonResetTask;

    // Config (cached)
    private boolean enabled;
    private int maxActivations;
    private int cooldownSeconds;
    private int windowSeconds;
    private boolean notify;

    // Advanced Config
    private boolean advancedEnabled;
    private int maxFrequency; // activations/sec per block
    private boolean pistonLimitEnabled;
    private boolean trackMovingBlocks;
    private int maxPistonsPerChunkTick;

    private boolean longTermEnabled;
    private int longTermWindow;
    private int longTermMax;
    private boolean longTermBreak;

    // Data Structures (Standard HashMap for Main Thread speed)

    // World UUID -> ChunkCoordKey -> activation counter
    private final Map<UUID, Map<Long, MutableInt>> chunkActivations = new HashMap<>();

    // World UUID -> ChunkCoordKey -> suppression expiry timestamp (nano)
    private final Map<UUID, Map<Long, Long>> suppressedChunks = new HashMap<>();

    // Advanced: World UUID -> BlockKey -> rolling frequency
    private final Map<UUID, Map<Long, RollingFrequency>> blockFrequencies = new HashMap<>();

    // Long-term: World UUID -> BlockKey -> LongTermClock
    private final Map<UUID, Map<Long, LongTermClock>> longTermClocks = new HashMap<>();

    // World UUID -> ChunkCoordKey -> Piston count per tick
    private final Map<UUID, Map<Long, MutableInt>> pistonCounts = new HashMap<>();

    // Notification cooldown per chunk (prevent spam)
    private final Map<UUID, Map<Long, Long>> notifyCooldowns = new HashMap<>();
    private static final long NOTIFY_COOLDOWN_NANO = 10_000_000_000L;

    // Stats
    private long totalSuppressed = 0;
    private int activeSuppressedChunks = 0;

    public RedstoneMonitor(LessLag plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    private void loadConfig() {
        // Original config
        enabled = plugin.getConfig().getBoolean("modules.redstone.enabled", true);
        maxActivations = plugin.getConfig().getInt("modules.redstone.max-activations-per-chunk", 200);
        cooldownSeconds = plugin.getConfig().getInt("modules.redstone.cooldown-seconds", 10);
        windowSeconds = plugin.getConfig().getInt("modules.redstone.window-seconds", 2);
        notify = plugin.getConfig().getBoolean("modules.redstone.notify", true);

        // Advanced config
        advancedEnabled = plugin.getConfig().getBoolean("modules.redstone.advanced.enabled", true);
        maxFrequency = plugin.getConfig().getInt("modules.redstone.advanced.max-frequency", 20);

        pistonLimitEnabled = plugin.getConfig().getBoolean("modules.redstone.advanced.piston-limit.enabled", true);
        trackMovingBlocks = plugin.getConfig().getBoolean("modules.redstone.advanced.piston-limit.track-moving-blocks",
                true);
        maxPistonsPerChunkTick = plugin.getConfig()
                .getInt("modules.redstone.advanced.piston-limit.max-pushes-per-chunk", 50);

        longTermEnabled = plugin.getConfig().getBoolean("modules.redstone.long-term.enabled", true);
        longTermWindow = plugin.getConfig().getInt("modules.redstone.long-term.window-seconds", 120);
        longTermMax = plugin.getConfig().getInt("modules.redstone.long-term.max-activations", 100);
        longTermBreak = plugin.getConfig().getStringList("modules.redstone.long-term.actions").contains("break-block");
    }

    public void start() {
        if (!enabled && !advancedEnabled)
            return;

        // Register event listener
        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Periodic cleanup + counter reset
        cleanupTask = new BukkitRunnable() {
            @Override
            public void run() {
                // Reset activation counters every window
                chunkActivations.clear();

                long now = System.nanoTime();

                // Advanced: Prune stale block frequencies
                cleanupMap(blockFrequencies, now, (freq, time) -> freq.isStale(time));

                // Long-term: Prune expired clocks
                long windowNanos = longTermWindow * 1_000_000_000L;
                cleanupMap(longTermClocks, now, (clock, time) -> clock.isExpired(time, windowNanos));

                // Remove expired suppressions and update counts
                int activeCount = 0;
                Iterator<Map.Entry<UUID, Map<Long, Long>>> suppressionIt = suppressedChunks.entrySet().iterator();
                while (suppressionIt.hasNext()) {
                    Map.Entry<UUID, Map<Long, Long>> entry = suppressionIt.next();
                    Map<Long, Long> chunks = entry.getValue();
                    chunks.values().removeIf(expiry -> expiry <= now);

                    if (chunks.isEmpty()) {
                        suppressionIt.remove();
                    } else {
                        activeCount += chunks.size();
                    }
                }
                activeSuppressedChunks = activeCount;

                // Clean stale notification cooldowns
                cleanupMap(notifyCooldowns, now, (lastNotify, time) -> time - lastNotify > NOTIFY_COOLDOWN_NANO);
            }
        }.runTaskTimer(plugin, windowSeconds * 20L, windowSeconds * 20L);

        // Piston counter reset (every tick)
        if (pistonLimitEnabled) {
            pistonResetTask = new BukkitRunnable() {
                @Override
                public void run() {
                    pistonCounts.clear();
                }
            }.runTaskTimer(plugin, 1L, 1L);
        }

        plugin.getLogger().info("Redstone Suppressor & Limiter started (Main Thread Optimized).");
    }

    /**
     * Generic map cleanup helper to reduce code duplication
     */
    private <T> void cleanupMap(Map<UUID, Map<Long, T>> map, long now,
            java.util.function.BiPredicate<T, Long> predicate) {
        Iterator<Map.Entry<UUID, Map<Long, T>>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Map<Long, T>> entry = it.next();
            Map<Long, T> inner = entry.getValue();
            inner.values().removeIf(value -> predicate.test(value, now));
            if (inner.isEmpty()) {
                it.remove();
            }
        }
    }

    public void stop() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        if (pistonResetTask != null) {
            pistonResetTask.cancel();
            pistonResetTask = null;
        }
        HandlerList.unregisterAll(this);
        chunkActivations.clear();
        suppressedChunks.clear();
        notifyCooldowns.clear();
        blockFrequencies.clear();
        pistonCounts.clear();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent event) {
        // FAIL-FAST: Only care about signal CHANGES
        if (event.getOldCurrent() == event.getNewCurrent())
            return;

        Block block = event.getBlock();
        UUID worldUID = block.getWorld().getUID();
        // Shift operations are faster than division
        long chunkKey = getChunkKey(block.getX() >> 4, block.getZ() >> 4);

        // 1. FAIL-FAST: Chunk Suppression (Check this FIRST to save CPU)
        // If the chunk is already suppressed, we don't want to do frequency checks
        Map<Long, Long> worldSuppressed = suppressedChunks.get(worldUID);
        if (worldSuppressed != null) {
            Long expiresAt = worldSuppressed.get(chunkKey);
            if (expiresAt != null) {
                if (System.nanoTime() < expiresAt) {
                    event.setNewCurrent(event.getOldCurrent()); // Cancel change
                    return;
                } else {
                    worldSuppressed.remove(chunkKey);
                }
            }
        }

        // 2. Local Block Frequency Check
        if (advancedEnabled) {
            long blockKey = getBlockKey(block);
            Map<Long, RollingFrequency> worldFreqs = blockFrequencies.computeIfAbsent(worldUID, k -> new HashMap<>());
            RollingFrequency freq = worldFreqs.computeIfAbsent(blockKey, k -> new RollingFrequency());

            // Note: rolling check can involve system time, so we call it here
            if (freq.incrementAndCheck(System.nanoTime(), maxFrequency)) {
                event.setNewCurrent(event.getOldCurrent());
                return;
            }
        }

        // 3. Long-term Check
        if (longTermEnabled) {
            long blockKey = getBlockKey(block);
            Map<Long, LongTermClock> worldClocks = longTermClocks.computeIfAbsent(worldUID, k -> new HashMap<>());
            LongTermClock clock = worldClocks.computeIfAbsent(blockKey,
                    k -> new LongTermClock(System.nanoTime()));

            clock.increment();

            if (clock.getCount() > longTermMax
                    && !clock.isExpired(System.nanoTime(), longTermWindow * 1_000_000_000L)) {
                handleLongTermViolation(block, clock);
                event.setNewCurrent(event.getOldCurrent());
                return;
            }
        }

        // 4. Update Chunk Activation Counter
        Map<Long, MutableInt> worldActivations = chunkActivations.computeIfAbsent(worldUID, k -> new HashMap<>());
        MutableInt counter = worldActivations.computeIfAbsent(chunkKey, k -> new MutableInt());

        if (counter.incrementAndGet() >= maxActivations) {
            suppressChunk(worldUID, chunkKey, block);
            event.setNewCurrent(event.getOldCurrent());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        checkPiston(event.getBlock(), event, event.getDirection(), event.getBlocks());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        checkPiston(event.getBlock(), event, event.getDirection(), event.getBlocks());
    }

    private void checkPiston(Block block, org.bukkit.event.Cancellable event, BlockFace direction,
            java.util.List<Block> blocks) {
        if (!pistonLimitEnabled)
            return;

        // Dynamic Tracking for Moving Clocks (only if enabled)
        if (longTermEnabled && trackMovingBlocks && !((org.bukkit.event.Cancellable) event).isCancelled()) {
            handlePistonMovement(block, direction, blocks);
        }

        UUID worldUID = block.getWorld().getUID();
        long chunkKey = getChunkKey(block.getX() >> 4, block.getZ() >> 4);

        Map<Long, MutableInt> worldPistons = pistonCounts.computeIfAbsent(worldUID, k -> new HashMap<>());
        MutableInt count = worldPistons.computeIfAbsent(chunkKey, k -> new MutableInt());

        if (count.incrementAndGet() > maxPistonsPerChunkTick) {
            event.setCancelled(true);
        }
    }

    private void handleLongTermViolation(Block block, LongTermClock clock) {
        if (clock.getCount() % 20 != 0)
            return;

        String worldName = block.getWorld().getName();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        if (notify) {
            NotificationHelper.notifyAdmins(
                    "&c⚠ Persistent Redstone Clock (&f" + x + "," + y + "," + z + "&c) in " + worldName
                            + " &7(" + clock.getCount() + " acts/" + longTermWindow + "s)");
        }

        if (longTermBreak) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                block.breakNaturally();
                Map<Long, LongTermClock> worldMap = longTermClocks.get(block.getWorld().getUID());
                if (worldMap != null)
                    worldMap.remove(getBlockKey(block));
            });
        }
    }

    private void handlePistonMovement(Block piston, BlockFace direction, java.util.List<Block> blocks) {
        UUID worldUID = piston.getWorld().getUID();
        Map<Long, LongTermClock> worldClocks = longTermClocks.get(worldUID);
        if (worldClocks == null || worldClocks.isEmpty())
            return;

        int dx = direction.getModX();
        int dy = direction.getModY();
        int dz = direction.getModZ();

        Map<Long, LongTermClock> updates = new HashMap<>();

        for (Block b : blocks) {
            if (b.getType().isAir())
                continue;

            long oldKey = getBlockKey(b);
            LongTermClock clock = worldClocks.remove(oldKey);

            if (clock != null) {
                // Determine new position
                int newX = b.getX() + dx;
                int newY = b.getY() + dy;
                int newZ = b.getZ() + dz;

                long newKey = getBlockKey(newX, newY, newZ);
                updates.put(newKey, clock);
            }
        }

        if (!updates.isEmpty()) {
            worldClocks.putAll(updates);
        }
    }

    private void suppressChunk(UUID worldUID, long chunkKey, Block block) {
        long expiry = System.nanoTime() + (cooldownSeconds * 1_000_000_000L);

        Map<Long, Long> worldSuppressed = suppressedChunks.computeIfAbsent(worldUID, k -> new HashMap<>());
        worldSuppressed.put(chunkKey, expiry);
        totalSuppressed++;

        if (notify) {
            long now = System.nanoTime();
            Map<Long, Long> worldCooldowns = notifyCooldowns.computeIfAbsent(worldUID, k -> new HashMap<>());
            Long lastNotify = worldCooldowns.get(chunkKey);

            if (lastNotify == null || now - lastNotify >= NOTIFY_COOLDOWN_NANO) {
                worldCooldowns.put(chunkKey, now);

                Location loc = block.getLocation();
                String world = loc.getWorld() != null ? loc.getWorld().getName() : "unknown";
                NotificationHelper.notifyAdmins(
                        "&e⚠ Redstone suppressed in chunk (&f"
                                + (block.getX() >> 4) + "&8, &f" + (block.getZ() >> 4)
                                + "&e) in &f" + world
                                + " &8(frozen " + cooldownSeconds + "s)");
            }
        }

        plugin.getLogger().warning("[Redstone] Suppressed chunk (" + (block.getX() >> 4)
                + ", " + (block.getZ() >> 4) + ") in " + block.getWorld().getName());
    }

    public void setMaxActivations(int maxActivations) {
        this.maxActivations = maxActivations;
    }

    public void clearAllSuppressions() {
        suppressedChunks.clear();
        chunkActivations.clear();
        notifyCooldowns.clear();
        blockFrequencies.clear();
        pistonCounts.clear();
        activeSuppressedChunks = 0;
    }

    // Packing logic
    private long getBlockKey(Block block) {
        return getBlockKey(block.getX(), block.getY(), block.getZ());
    }

    private long getBlockKey(int x, int y, int z) {
        // 27 bits X, 27 bits Z, 10 bits Y (Y is usually 0-384, fits in 10 bits: 1024)
        // Note: Y is shifted 54, so we have 64 bits total.
        // X and Z masking: 0x7FFFFFF (27 bits)
        return ((long) x & 0x7FFFFFF) | (((long) z & 0x7FFFFFF) << 27) | ((long) y << 54);
    }

    private long getChunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    // Data Classes
    private static class MutableInt {
        int value;

        public int incrementAndGet() {
            return ++value;
        }
    }

    private static class RollingFrequency {
        private long lastSecondStart = 0;
        private int count = 0;

        public boolean incrementAndCheck(long now, int maxPerSec) {
            if (now - lastSecondStart > 1_000_000_000L) {
                lastSecondStart = now;
                count = 0;
            }
            count++;
            return count > maxPerSec;
        }

        public boolean isStale(long now) {
            return now - lastSecondStart > 5_000_000_000L;
        }
    }

    private static class LongTermClock {
        private final long startTime;
        private int count;

        public LongTermClock(long startTime) {
            this.startTime = startTime;
            this.count = 0;
        }

        public void increment() {
            this.count++;
        }

        public int getCount() {
            return count;
        }

        public boolean isExpired(long now, long windowNanos) {
            return (now - startTime) > windowNanos;
        }
    }

    public Map<String, Long> getSuppressedChunks() {
        Map<String, Long> result = new HashMap<>();
        for (Map.Entry<UUID, Map<Long, Long>> worldEntry : suppressedChunks.entrySet()) {
            UUID worldUID = worldEntry.getKey();
            for (Map.Entry<Long, Long> chunkEntry : worldEntry.getValue().entrySet()) {
                long chunkKey = chunkEntry.getKey();
                int x = (int) (chunkKey >> 32);
                int z = (int) chunkKey;
                result.put(worldUID + ":" + x + ":" + z, chunkEntry.getValue());
            }
        }
        return result;
    }

    public long getTotalSuppressed() {
        return totalSuppressed;
    }

    public int getActiveSuppressedChunks() {
        return activeSuppressedChunks;
    }
}
