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
    private int cooldownSeconds;
    private int windowSeconds;
    private boolean notify;

    // Advanced Config
    private boolean advancedEnabled;
    private int maxFrequency; // activations/sec per block
    // activations/5s
    private boolean pistonLimitEnabled;
    private int maxPistonsPerChunkTick;

    // Data Structures

    // Original: World-isolated key → activation counter
    private final Map<ChunkKey, AtomicInteger> chunkActivations = new ConcurrentHashMap<>();

    // Original: World-isolated key → suppression expiry timestamp
    private final Map<ChunkKey, Long> suppressedChunks = new ConcurrentHashMap<>();

    // Advanced: Block location hash → activation timestamps (rolling window)
    private final Map<Long, RollingFrequency> blockFrequencies = new ConcurrentHashMap<>();

    // Advanced: Chunk key → Piston count per tick
    private final Map<ChunkKey, AtomicInteger> pistonCounts = new ConcurrentHashMap<>();

    // Notification cooldown per chunk (prevent spam)
    private final Map<ChunkKey, Long> notifyCooldowns = new ConcurrentHashMap<>();
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
        maxPistonsPerChunkTick = plugin.getConfig()
                .getInt("modules.redstone.advanced.piston-limit.max-pushes-per-chunk", 50);
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
                blockFrequencies.entrySet().removeIf(e -> e.getValue().isStale(now));

                // Remove expired suppressions
                suppressedChunks.entrySet().removeIf(entry -> entry.getValue() <= now);
                activeSuppressedChunks = suppressedChunks.size();

                // Clean stale notification cooldowns
                notifyCooldowns.entrySet().removeIf(entry -> entry.getValue() <= now);
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
        ChunkKey chunkKey = new ChunkKey(block.getWorld().getUID(), block.getX() >> 4, block.getZ() >> 4);

        // 1. Check Global Chunk Suppression (Original Feature)
        Long expiresAt = suppressedChunks.get(chunkKey);
        if (expiresAt != null) {
            if (System.currentTimeMillis() < expiresAt) {
                event.setNewCurrent(event.getOldCurrent()); // Cancel change
                return;
            } else {
                suppressedChunks.remove(chunkKey);
            }
        }

        // 2. Local Block Frequency Check (Advanced Feature)
        if (advancedEnabled) {
            long blockKey = getBlockKey(block);
            RollingFrequency freq = blockFrequencies.computeIfAbsent(blockKey, k -> new RollingFrequency());
            if (freq.incrementAndCheck(System.currentTimeMillis(), maxFrequency)) {
                // Throttled!
                event.setNewCurrent(event.getOldCurrent());
                return;
            }
        }

        // 3. Update Chunk Activation Counter
        AtomicInteger counter = chunkActivations.computeIfAbsent(chunkKey, k -> new AtomicInteger(0));
        int count = counter.incrementAndGet();

        if (count >= maxActivations) {
            suppressChunk(chunkKey, block);
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

        ChunkKey chunkKey = new ChunkKey(block.getWorld().getUID(), block.getX() >> 4, block.getZ() >> 4);
        AtomicInteger count = pistonCounts.computeIfAbsent(chunkKey, k -> new AtomicInteger(0));

        if (count.incrementAndGet() > maxPistonsPerChunkTick) {
            event.setCancelled(true);
        }
    }

    private void suppressChunk(ChunkKey chunkKey, Block block) {
        long expiry = System.currentTimeMillis() + (cooldownSeconds * 1000L);
        suppressedChunks.put(chunkKey, expiry);
        activeSuppressedChunks = suppressedChunks.size();
        totalSuppressed++;

        // Notify admins with per-chunk cooldown
        if (notify) {
            long now = System.currentTimeMillis();
            Long lastNotify = notifyCooldowns.get(chunkKey);
            if (lastNotify == null || now - lastNotify >= NOTIFY_COOLDOWN_MS) {
                notifyCooldowns.put(chunkKey, now);

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
        return ((long) block.getX() & 0x7FFFFFF) | (((long) block.getZ() & 0x7FFFFFF) << 27)
                | ((long) block.getY() << 54);
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

    // Reuse ChunkKey from original
    public Map<String, Long> getSuppressedChunks() {
        Map<String, Long> result = new HashMap<>();
        for (Map.Entry<ChunkKey, Long> entry : suppressedChunks.entrySet()) {
            result.put(entry.getKey().toString(), entry.getValue());
        }
        return result;
    }

    private static class ChunkKey {
        private final UUID worldUID;
        private final long chunkKey;

        public ChunkKey(UUID worldUID, int x, int z) {
            this.worldUID = worldUID;
            this.chunkKey = ((long) x << 32) | (z & 0xFFFFFFFFL);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            ChunkKey key = (ChunkKey) o;
            return chunkKey == key.chunkKey && worldUID.equals(key.worldUID);
        }

        @Override
        public int hashCode() {
            int result = worldUID.hashCode();
            result = 31 * result + Long.hashCode(chunkKey);
            return result;
        }
    }

    // Getters
    public long getTotalSuppressed() {
        return totalSuppressed;
    }

    public int getActiveSuppressedChunks() {
        return activeSuppressedChunks;
    }
}
