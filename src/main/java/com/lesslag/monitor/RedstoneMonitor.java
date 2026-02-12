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
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redstone Suppressor — Detects and temporarily freezes runaway redstone
 * circuits (lag machines, clock loops) that fire too frequently.
 *
 * Fixes: World-isolated chunk keys prevent cross-world false suppression.
 * Uses per-chunk notification cooldown to prevent admin message spam.
 */
public class RedstoneMonitor implements Listener {

    private final LessLag plugin;
    private BukkitTask cleanupTask;

    // Config (cached)
    private int maxActivations;
    private int cooldownSeconds;
    private int windowSeconds;
    private boolean notify;

    // World-isolated key → activation counter
    private final Map<ChunkKey, AtomicInteger> chunkActivations = new ConcurrentHashMap<>();

    // World-isolated key → suppression expiry timestamp
    private final Map<ChunkKey, Long> suppressedChunks = new ConcurrentHashMap<>();

    // Notification cooldown per chunk (prevent spam)
    private final Map<ChunkKey, Long> notifyCooldowns = new ConcurrentHashMap<>();
    private static final long NOTIFY_COOLDOWN_MS = 10_000; // 10s between notifications per chunk

    // Stats
    private volatile long totalSuppressed = 0;
    private volatile int activeSuppressedChunks = 0;

    public RedstoneMonitor(LessLag plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    private void loadConfig() {
        maxActivations = plugin.getConfig().getInt("redstone-suppressor.max-activations-per-chunk", 200);
        cooldownSeconds = plugin.getConfig().getInt("redstone-suppressor.cooldown-seconds", 10);
        windowSeconds = plugin.getConfig().getInt("redstone-suppressor.window-seconds", 2);
        notify = plugin.getConfig().getBoolean("redstone-suppressor.notify", true);
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("redstone-suppressor.enabled", true))
            return;

        // Register event listener
        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Periodic cleanup + counter reset
        cleanupTask = new BukkitRunnable() {
            @Override
            public void run() {
                // Reset activation counters every window
                chunkActivations.clear();

                // Remove expired suppressions
                long now = System.currentTimeMillis();
                suppressedChunks.entrySet().removeIf(entry -> entry.getValue() <= now);
                activeSuppressedChunks = suppressedChunks.size();

                // Clean stale notification cooldowns
                notifyCooldowns.entrySet().removeIf(entry -> entry.getValue() <= now);
            }
        }.runTaskTimer(plugin, windowSeconds * 20L, windowSeconds * 20L);

        plugin.getLogger().info("Redstone Suppressor started (threshold: "
                + maxActivations + " activations/" + windowSeconds + "s)");
    }

    public void stop() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        HandlerList.unregisterAll(this);
        chunkActivations.clear();
        suppressedChunks.clear();
        notifyCooldowns.clear();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent event) {
        Block block = event.getBlock();
        // World-isolated chunk key
        ChunkKey chunkKey = new ChunkKey(block.getWorld().getUID(), block.getX() >> 4, block.getZ() >> 4);

        // Check if chunk is currently suppressed
        Long expiresAt = suppressedChunks.get(chunkKey);
        if (expiresAt != null) {
            if (System.currentTimeMillis() < expiresAt) {
                event.setNewCurrent(event.getOldCurrent());
                return;
            } else {
                suppressedChunks.remove(chunkKey);
            }
        }

        // Increment activation counter
        AtomicInteger counter = chunkActivations.computeIfAbsent(chunkKey, k -> new AtomicInteger(0));
        int count = counter.incrementAndGet();

        if (count >= maxActivations) {
            // Suppress this chunk!
            long expiry = System.currentTimeMillis() + (cooldownSeconds * 1000L);
            suppressedChunks.put(chunkKey, expiry);
            activeSuppressedChunks = suppressedChunks.size();
            totalSuppressed++;

            event.setNewCurrent(event.getOldCurrent());

            // Notify admins with per-chunk cooldown to prevent spam
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
                                    + " &8(" + count + " activations, frozen " + cooldownSeconds + "s)");
                }
            }

            plugin.getLogger().warning("[RedstoneSuppressor] Chunk (" + (block.getX() >> 4)
                    + ", " + (block.getZ() >> 4) + ") in " + block.getWorld().getName()
                    + " suppressed — " + count + " activations exceeded limit");
        }
    }

    /**
     * Force-unsuppress all chunks (used by /lg restore).
     */
    public void clearAllSuppressions() {
        suppressedChunks.clear();
        chunkActivations.clear();
        notifyCooldowns.clear();
        activeSuppressedChunks = 0;
    }

    // ── Getters ──────────────────────────────────────

    public long getTotalSuppressed() {
        return totalSuppressed;
    }

    public int getActiveSuppressedChunks() {
        return activeSuppressedChunks;
    }

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
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ChunkKey chunkKey1 = (ChunkKey) o;
            return chunkKey == chunkKey1.chunkKey && worldUID.equals(chunkKey1.worldUID);
        }

        @Override
        public int hashCode() {
            int result = worldUID.hashCode();
            result = 31 * result + Long.hashCode(chunkKey);
            return result;
        }

        @Override
        public String toString() {
            int x = (int) (chunkKey >> 32);
            int z = (int) chunkKey;
            return worldUID + ":" + x + ":" + z;
        }
    }
}
