package com.lesslag.monitor;

import com.lesslag.LessLag;
import com.lesslag.util.SchedulerAdapter;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Monitors player movement and prevents them from traveling across chunk
 * borders too quickly (e.g., using Elytra launchers or speed hacks).
 * 
 * If a player crosses more than the configured amount of chunks per second,
 * they will be rubberbanded back to a safe location to prevent the server
 * from aggressively generating and loading chunks.
 */
public class MovementLimiter implements Listener {

    private final LessLag plugin;
    private boolean enabled;
    private int maxChunksPerSecond;
    private String action;
    private boolean cancelVelocity;
    private String warningMessage;
    private boolean notifyAdmin;

    // Track how many chunk borders a player has crossed in the current second
    private final Map<UUID, AtomicInteger> chunksCrossed = new ConcurrentHashMap<>();

    // Store the last known safe location for each player
    private final Map<UUID, Location> safeLocations = new ConcurrentHashMap<>();

    private SchedulerAdapter.TaskHandle cleanupTask;

    public MovementLimiter(LessLag plugin) {
        this.plugin = plugin;
        reloadConfig();
    }

    public void reloadConfig() {
        this.enabled = plugin.getConfig().getBoolean("modules.movement-limiter.enabled", true);
        this.maxChunksPerSecond = plugin.getConfig().getInt("modules.movement-limiter.max-chunks-per-second", 20);
        this.action = plugin.getConfig().getString("modules.movement-limiter.action", "rubberband");
        this.cancelVelocity = plugin.getConfig().getBoolean("modules.movement-limiter.cancel-velocity", true);
        this.warningMessage = plugin.getConfig().getString("modules.movement-limiter.message",
                "&cYou are moving too fast! Server needs time to load chunks.");
        this.notifyAdmin = plugin.getConfig().getBoolean("modules.movement-limiter.notify-admin", true);
    }

    public void start() {
        if (!enabled)
            return;

        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        // Reset the chunk crossing counters every second
        cleanupTask = SchedulerAdapter.runAsyncRepeating(plugin, () -> {
            chunksCrossed.clear();

            // Periodically clean up safe locations for offline players
            safeLocations.keySet().removeIf(uuid -> {
                Player player = plugin.getServer().getPlayer(uuid);
                return player == null || !player.isOnline();
            });
        }, 20L, 20L); // 20 ticks = 1 second
    }

    public void stop() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        chunksCrossed.clear();
        safeLocations.clear();
        PlayerMoveEvent.getHandlerList().unregister(this);
        PlayerQuitEvent.getHandlerList().unregister(this);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!enabled)
            return;

        Location from = event.getFrom();
        Location to = event.getTo();

        // Only process if the player actually moved across a block boundary
        // (optimization)
        if (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Update safe location every time they move within the same chunk
        int fromChunkX = from.getBlockX() >> 4;
        int fromChunkZ = from.getBlockZ() >> 4;
        int toChunkX = to.getBlockX() >> 4;
        int toChunkZ = to.getBlockZ() >> 4;

        if (fromChunkX == toChunkX && fromChunkZ == toChunkZ) {
            // Player is moving inside the same chunk, update their safe location
            // We only update if they are on the ground or falling safely,
            // but for simplicity, we'll update it periodically when they aren't crossing
            // borders.
            if (((org.bukkit.entity.Entity) player).isOnGround() || !player.isGliding()) {
                safeLocations.put(uuid, from.clone());
            }
            return;
        }

        // --- Player has crossed a chunk border ---

        AtomicInteger count = chunksCrossed.computeIfAbsent(uuid, k -> new AtomicInteger(0));
        int crossed = count.incrementAndGet();

        // Check if they exceeded the limit
        if (crossed > maxChunksPerSecond) {
            event.setCancelled(true); // Always cancel the move event first

            if ("rubberband".equalsIgnoreCase(action)) {
                // Determine where to send them back to
                Location safeLoc = safeLocations.get(uuid);
                if (safeLoc == null) {
                    safeLoc = from; // Fallback to where they just were
                }

                // Actually teleport them back (teleportAsync is extremely safe and
                // natively supports Folia's region-based ticking without throwing exceptions)
                player.teleportAsync(safeLoc);
            }

            if (cancelVelocity) {
                player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                // If they were gliding via Elytra, optionally force them to stop to immediately
                // halt momentum
                // We won't forcefully unequip the elytra natively unless necessary, but setting
                // velocity to 0 often works.
            }

            if (warningMessage != null && !warningMessage.isEmpty() && crossed == maxChunksPerSecond + 1) {
                // Only send the message once per second to avoid spam
                LessLag.sendMessage(player, warningMessage);
            }

            if (notifyAdmin && crossed == maxChunksPerSecond + 1) {
                plugin.getSafeLogger().warning("[MovementLimiter] Player " + player.getName() +
                        " exceeded chunk border limit (" + crossed + "/" + maxChunksPerSecond
                        + " per second). Action applied.");
            }
        } else {
            // Update their safe location right before they cross a new chunk border,
            // ensuring we have a very recent fallback point.
            if (((org.bukkit.entity.Entity) player).isOnGround() || !player.isGliding()) {
                safeLocations.put(uuid, from.clone());
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        chunksCrossed.remove(uuid);
        safeLocations.remove(uuid);
    }
}
