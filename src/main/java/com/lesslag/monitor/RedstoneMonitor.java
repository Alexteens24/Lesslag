package com.lesslag.monitor;

import com.lesslag.LessLag;
import com.lesslag.util.NotificationHelper;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redstone Suppressor & Limiter
 * 
 * Features:
 * 1. Chunk Suppression: Freezes chunks exceeding max activations (original
 * feature).
 * 2. Advanced: Frequency Limiter (per block) - Throttles fast clocks.
 * 3. Advanced: Piston Limiter (per chunk/tick) - Prevents massive piston
 * machines.
 */
public class RedstoneMonitor implements Listener {

    private final LessLag plugin;
    private BukkitTask cleanupTask;
    private BukkitTask pistonResetTask;

    // Config (cached)
    private boolean enabled;
    private int maxActivations;

    public void setMaxActivations(int maxActivations) {
        this.maxActivations = maxActivations;
    }

    private int cooldownSeconds;
    private int windowSeconds;
    private boolean notify;

    // Advanced Config
    private boolean advancedEnabled;
    private int maxFrequency; // activations/sec per block
    // activations/5s
    private boolean pistonLimitEnabled;
    private boolean trackMovingBlocks;
    private int maxPistonsPerChunkTick;

    private boolean longTermEnabled;
    private int longTermWindow;
    private int longTermMax;
    private boolean longTermBreak;

    // Data Structures

    // Refactored: World UUID -> (ChunkCoordKey -> activation counter)
    private final Map<UUID, Map<Long, AtomicInteger>> chunkActivations = new ConcurrentHashMap<>();

    // Refactored: World UUID -> (ChunkCoordKey -> suppression expiry timestamp)
    private final Map<UUID, Map<Long, Long>> suppressedChunks = new ConcurrentHashMap<>();

    // Advanced: World UUID -> (BlockKey -> activation timestamps (rolling window))
    private final Map<UUID, Map<Long, RollingFrequency>> blockFrequencies = new ConcurrentHashMap<>();

    // Long-term: World UUID -> (BlockKey -> LongTermClock)
    private final Map<UUID, Map<Long, LongTermClock>> longTermClocks = new ConcurrentHashMap<>();

    // Refactored: World UUID -> (ChunkCoordKey -> Piston count per tick)
    private final Map<UUID, Map<Long, AtomicInteger>> pistonCounts = new ConcurrentHashMap<>();

    // Notification cooldown per chunk (prevent spam)
    private final Map<UUID, Map<Long, Long>> notifyCooldowns = new ConcurrentHashMap<>();
    private static final long NOTIFY_COOLDOWN_MS = 10_000;

    // Stats
    private volatile long totalSuppressed = 0;
    private volatile int activeSuppressedChunks = 0;

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

                // Advanced: Prune stale block frequencies
                long now = System.currentTimeMillis();
                for (Map.Entry<UUID, Map<Long, RollingFrequency>> entry : blockFrequencies.entrySet()) {
                    entry.getValue().values().removeIf(freq -> freq.isStale(now));
                    if (entry.getValue().isEmpty()) {
                        blockFrequencies.remove(entry.getKey());
                    }
                }

                // Long-term: Prune expired clocks
                for (Map.Entry<UUID, Map<Long, LongTermClock>> entry : longTermClocks.entrySet()) {
                    entry.getValue().values().removeIf(clock -> clock.isExpired(now, longTermWindow));
                    if (entry.getValue().isEmpty()) {
                        longTermClocks.remove(entry.getKey());
                    }
                }

                // Remove expired suppressions
                int activeCount = 0;
                for (Map.Entry<UUID, Map<Long, Long>> worldEntry : suppressedChunks.entrySet()) {
                    Map<Long, Long> chunks = worldEntry.getValue();
                    chunks.entrySet().removeIf(entry -> entry.getValue() <= now);
                    if (chunks.isEmpty()) {
                        suppressedChunks.remove(worldEntry.getKey());
                    } else {
                        activeCount += chunks.size();
                    }
                }
                activeSuppressedChunks = activeCount;

                // Clean stale notification cooldowns
                for (Map.Entry<UUID, Map<Long, Long>> worldEntry : notifyCooldowns.entrySet()) {
                    Map<Long, Long> cooldowns = worldEntry.getValue();
                    cooldowns.entrySet().removeIf(entry -> now - entry.getValue() > NOTIFY_COOLDOWN_MS);
                    if (cooldowns.isEmpty()) {
                        notifyCooldowns.remove(worldEntry.getKey());
                    }
                }
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

        plugin.getLogger().info("Redstone Suppressor & Limiter started.");
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
        // Optimization: Only care about signal CHANGES (0->15 or 15->0) to filter
        // constant noise
        if (event.getOldCurrent() == event.getNewCurrent())
            return;

        Block block = event.getBlock();
        UUID worldUID = block.getWorld().getUID();
        long chunkKey = getChunkKey(block.getX() >> 4, block.getZ() >> 4);

        // 1. Check Global Chunk Suppression (Original Feature)
        Map<Long, Long> worldSuppressed = suppressedChunks.get(worldUID);
        if (worldSuppressed != null) {
            Long expiresAt = worldSuppressed.get(chunkKey);
            if (expiresAt != null) {
                if (System.currentTimeMillis() < expiresAt) {
                    event.setNewCurrent(event.getOldCurrent()); // Cancel change
                    return;
                } else {
                    worldSuppressed.remove(chunkKey);
                }
            }
        }

        // 2. Local Block Frequency Check (Advanced Feature)
        if (advancedEnabled) {
            long blockKey = getBlockKey(block);
            Map<Long, RollingFrequency> worldFreqs = blockFrequencies.computeIfAbsent(worldUID,
                    k -> new ConcurrentHashMap<>());
            RollingFrequency freq = worldFreqs.computeIfAbsent(blockKey, k -> new RollingFrequency());
            if (freq.incrementAndCheck(System.currentTimeMillis(), maxFrequency)) {
                // Throttled!
                event.setNewCurrent(event.getOldCurrent());
                return;
            }
        }

        // 3. Long-term Check (New Feature)
        if (longTermEnabled) {
            long blockKey = getBlockKey(block);
            Map<Long, LongTermClock> worldClocks = longTermClocks.computeIfAbsent(worldUID,
                    k -> new ConcurrentHashMap<>());
            LongTermClock clock = worldClocks.computeIfAbsent(blockKey,
                    k -> new LongTermClock(System.currentTimeMillis()));

            clock.increment();
            // clock.setCurrentLocation(block.getLocation()); // Removed for now to fix lint

            if (clock.getCount() > longTermMax && !clock.isExpired(System.currentTimeMillis(), longTermWindow)) {
                // Limit exceeded!
                handleLongTermViolation(block, clock);
                event.setNewCurrent(event.getOldCurrent());
                return;
            }
        }

        // 3. Update Chunk Activation Counter
        Map<Long, AtomicInteger> worldActivations = chunkActivations.computeIfAbsent(worldUID,
                k -> new ConcurrentHashMap<>());
        AtomicInteger counter = worldActivations.computeIfAbsent(chunkKey, k -> new AtomicInteger(0));
        int count = counter.incrementAndGet();

        if (count >= maxActivations) {
            suppressChunk(worldUID, chunkKey, block);
            event.setNewCurrent(event.getOldCurrent());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        checkPiston(event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        checkPiston(event.getBlock(), event);
    }

    private void checkPiston(Block block, org.bukkit.event.Cancellable event) {
        if (!advancedEnabled || !pistonLimitEnabled)
            return;

        // Dynamic Tracking: Handle moving clocks (Optional - heavy)
        if (longTermEnabled && trackMovingBlocks && !event.isCancelled()) {
            handlePistonMovement(block,
                    event instanceof BlockPistonExtendEvent ? ((BlockPistonExtendEvent) event).getDirection()
                            : ((BlockPistonRetractEvent) event).getDirection(),
                    event instanceof BlockPistonExtendEvent ? ((BlockPistonExtendEvent) event).getBlocks()
                            : ((BlockPistonRetractEvent) event).getBlocks());
        }

        UUID worldUID = block.getWorld().getUID();
        long chunkKey = getChunkKey(block.getX() >> 4, block.getZ() >> 4);

        Map<Long, AtomicInteger> worldPistons = pistonCounts.computeIfAbsent(worldUID, k -> new ConcurrentHashMap<>());
        AtomicInteger count = worldPistons.computeIfAbsent(chunkKey, k -> new AtomicInteger(0));

        if (count.incrementAndGet() > maxPistonsPerChunkTick) {
            event.setCancelled(true);
        }
    }

    private void handleLongTermViolation(Block block, LongTermClock clock) {
        // Prevent spamming actions every tick
        if (clock.getCount() % 20 != 0)
            return;

        String worldName = block.getWorld().getName();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        if (notify) {
            NotificationHelper.notifyAdmins(
                    "&c⚠ Persistent Redstone Clock detected at &f" + worldName + " " + x + "," + y + "," + z
                            + " &7(" + clock.getCount() + " activations/" + longTermWindow + "s)");
        }

        if (longTermBreak) {
            // Simulate Silk Touch break if possible, or just break naturally
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                block.breakNaturally();
                // Note: accurate silk touch requires NMS or more complex logic, breakNaturally
                // is safe default
                longTermClocks.get(block.getWorld().getUID()).remove(getBlockKey(block));
            });
        }
    }

    private void handlePistonMovement(Block piston, org.bukkit.block.BlockFace direction,
            java.util.List<Block> blocks) {
        UUID worldUID = piston.getWorld().getUID();
        Map<Long, LongTermClock> worldClocks = longTermClocks.get(worldUID);
        if (worldClocks == null)
            return;

        // Vector shift
        int dx = direction.getModX();
        int dy = direction.getModY();
        int dz = direction.getModZ();

        // Use temporary map to prevent overwriting keys if blocks move into each
        // other's
        // previous positions
        Map<Long, LongTermClock> updates = new HashMap<>();

        for (Block b : blocks) {
            if (b.getType() == org.bukkit.Material.AIR)
                continue;

            long oldKey = getBlockKey(b);
            LongTermClock clock = worldClocks.remove(oldKey);

            if (clock != null) {
                // Fast re-keying:
                int newX = b.getX() + dx;
                int newY = b.getY() + dy;
                int newZ = b.getZ() + dz;

                long newKey = (((long) newX & 0x7FFFFFF) | (((long) newZ & 0x7FFFFFF) << 27)
                        | ((long) newY << 54));

                updates.put(newKey, clock);
            }
        }

        if (!updates.isEmpty()) {
            worldClocks.putAll(updates);
        }
    }

    private void suppressChunk(UUID worldUID, long chunkKey, Block block) {
        long expiry = System.currentTimeMillis() + (cooldownSeconds * 1000L);

        Map<Long, Long> worldSuppressed = suppressedChunks.computeIfAbsent(worldUID, k -> new ConcurrentHashMap<>());
        worldSuppressed.put(chunkKey, expiry);

        // Recalculate size roughly? Or just wait for cleanup task to update exact
        // count.
        // For simplicity, we just increment counter, but exact count is updated
        // periodically.
        totalSuppressed++;

        // Notify admins with per-chunk cooldown
        if (notify) {
            long now = System.currentTimeMillis();
            Map<Long, Long> worldCooldowns = notifyCooldowns.computeIfAbsent(worldUID, k -> new ConcurrentHashMap<>());
            Long lastNotify = worldCooldowns.get(chunkKey);

            if (lastNotify == null || now - lastNotify >= NOTIFY_COOLDOWN_MS) {
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

        plugin.getLogger().warning("[RedstoneSuppressor] Chunk (" + (block.getX() >> 4)
                + ", " + (block.getZ() >> 4) + ") in " + block.getWorld().getName()
                + " suppressed — activations exceeded limit");
    }

    public void clearAllSuppressions() {
        suppressedChunks.clear();
        chunkActivations.clear();
        notifyCooldowns.clear();
        blockFrequencies.clear();
        activeSuppressedChunks = 0;
    }

    private long getBlockKey(Block block) {
        // Just packed coordinates (key is unique within a world map)
        return (((long) block.getX() & 0x7FFFFFF) | (((long) block.getZ() & 0x7FFFFFF) << 27)
                | ((long) block.getY() << 54));
    }

    private long getChunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    // ── Data Classes ──────────────────────────────────────

    private static class RollingFrequency {
        private long lastSecondStart = 0;
        private int count = 0;

        // Returns true if limit exceeded (throttle)
        public synchronized boolean incrementAndCheck(long now, int maxPerSec) {
            if (now - lastSecondStart > 1000) {
                lastSecondStart = now;
                count = 0;
            }
            count++;
            return count > maxPerSec;
        }

        public boolean isStale(long now) {
            return now - lastSecondStart > 5000;
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

        public boolean isExpired(long now, int windowSeconds) {
            return (now - startTime) > (windowSeconds * 1000L);
        }

    }

    // Adjusted for compatibility
    public Map<String, Long> getSuppressedChunks() {
        Map<String, Long> result = new HashMap<>();
        for (Map.Entry<UUID, Map<Long, Long>> worldEntry : suppressedChunks.entrySet()) {
            UUID worldUID = worldEntry.getKey();
            for (Map.Entry<Long, Long> chunkEntry : worldEntry.getValue().entrySet()) {
                long chunkKey = chunkEntry.getKey();
                int x = (int) (chunkKey >> 32);
                int z = (int) chunkKey;
                // Format: WorldUUID:x:z
                result.put(worldUID + ":" + x + ":" + z, chunkEntry.getValue());
            }
        }
        return result;
    }

    // Getters
    public long getTotalSuppressed() {
        return totalSuppressed;
    }

    public int getActiveSuppressedChunks() {
        return activeSuppressedChunks;
    }
}
