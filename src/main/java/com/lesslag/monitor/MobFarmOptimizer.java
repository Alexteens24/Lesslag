package com.lesslag.monitor;

import com.lesslag.LessLag;
import com.lesslag.util.SchedulerAdapter;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MobFarmOptimizer — Disables AI for hostile mobs in dark, player-free areas
 * (typical mob grinder rooms), inspired by FarmControl.
 *
 * <p>
 * <b>Why</b>: Mobs in a dark grinder room that haven't seen a player in seconds
 * still run random stroll / look-around goals every tick — pure CPU waste.
 * Disabling their AI while no player is nearby eliminates this cost without
 * affecting loot drops or despawn behaviour.
 *
 * <p>
 * <b>Architecture</b>:
 * <ul>
 * <li>Incremental chunk scan (same pattern as {@link VillagerOptimizer}) —
 * processes a few chunks per tick to avoid bulk spikes.</li>
 * <li>Disable AI when: light level ≤ threshold AND no player within activation
 * range.</li>
 * <li>Re-enable AI when: player comes within activation range OR mob is no
 * longer in dark.</li>
 * <li>Interaction event re-enables immediately so players can always see mob
 * reactions.</li>
 * </ul>
 *
 * <p>
 * <b>Folia</b>: each chunk is processed via
 * {@link SchedulerAdapter#runAtChunk} on the correct regional thread.
 * {@code entity.setAI()} is always called on the entity's owning thread.
 */
public class MobFarmOptimizer implements Listener {

    private final NamespacedKey FARM_DUMB_KEY;

    private final LessLag plugin;
    private SchedulerAdapter.TaskHandle scanTask;

    // Config
    private boolean enabled;
    private int checkInterval;
    private int maxLightLevel;
    private double playerActivationRange;
    private double playerActivationRangeSq;
    private Set<EntityType> includedTypes; // empty = all hostile

    // Stats
    private final AtomicInteger dumbed = new AtomicInteger(0);

    // Incremental scan state
    private Chunk[] scanChunks = null;
    private int scanCursor = 0;
    private int scanWorldIndex = 0;
    private static final int CHUNKS_PER_TICK = 15;

    public MobFarmOptimizer(LessLag plugin) {
        this.plugin = plugin;
        this.FARM_DUMB_KEY = new NamespacedKey(plugin, "farm_dumb");
        loadConfig();
    }

    private void loadConfig() {
        enabled = plugin.getConfig().getBoolean("modules.mob-farm-optimizer.enabled", true);
        checkInterval = plugin.getConfig().getInt("modules.mob-farm-optimizer.check-interval", 300);
        maxLightLevel = plugin.getConfig().getInt("modules.mob-farm-optimizer.max-light-level", 0);
        playerActivationRange = plugin.getConfig().getDouble("modules.mob-farm-optimizer.player-activation-range",
                24.0);
        playerActivationRangeSq = playerActivationRange * playerActivationRange;

        includedTypes = new HashSet<>();
        for (String s : plugin.getConfig().getStringList("modules.mob-farm-optimizer.types")) {
            try {
                includedTypes.add(EntityType.valueOf(s.toUpperCase()));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("[MobFarmOptimizer] Unknown entity type: " + s);
            }
        }
    }

    public void start() {
        if (!enabled)
            return;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        startScanPass();
        plugin.getLogger().info("MobFarmOptimizer started (interval=" + checkInterval + "t, light≤"
                + maxLightLevel + ", activation=" + (int) playerActivationRange + "b)");
    }

    public void stop() {
        if (scanTask != null) {
            scanTask.cancel();
            scanTask = null;
        }
        HandlerList.unregisterAll(this);
    }

    // ══════════════════════════════════════════════════
    // Incremental Scan
    // ══════════════════════════════════════════════════

    private void startScanPass() {
        if (scanTask != null)
            scanTask.cancel();
        scanChunks = null;
        scanCursor = 0;
        scanWorldIndex = 0;
        scanTask = SchedulerAdapter.runGlobalRepeating(plugin, this::scanIncremental, 1L, 1L);
    }

    private void scanIncremental() {
        if (scanChunks == null || scanCursor >= scanChunks.length) {
            List<World> worlds = Bukkit.getWorlds();
            if (scanChunks != null && scanWorldIndex + 1 < worlds.size()) {
                scanWorldIndex++;
            } else {
                if (scanChunks != null) {
                    // Cycle complete — idle until next pass
                    scanChunks = null;
                    if (scanTask != null) {
                        scanTask.cancel();
                        scanTask = null;
                    }
                    SchedulerAdapter.runGlobalDelayed(plugin, this::startScanPass, checkInterval);
                    return;
                }
                scanWorldIndex = 0;
            }
            if (worlds.isEmpty())
                return;
            if (scanWorldIndex >= worlds.size()) {
                scanChunks = null;
                if (scanTask != null) {
                    scanTask.cancel();
                    scanTask = null;
                }
                SchedulerAdapter.runGlobalDelayed(plugin, this::startScanPass, checkInterval);
                return;
            }
            scanChunks = worlds.get(scanWorldIndex).getLoadedChunks();
            scanCursor = 0;
        }

        boolean folia = SchedulerAdapter.isFolia();
        int perTick = folia ? 5 : CHUNKS_PER_TICK;
        int end = Math.min(scanCursor + perTick, scanChunks.length);
        for (int i = scanCursor; i < end; i++) {
            Chunk chunk = scanChunks[i];
            if (!chunk.isLoaded())
                continue;
            if (folia) {
                final Chunk c = chunk;
                SchedulerAdapter.runAtChunk(plugin, c.getWorld(), c.getX(), c.getZ(), () -> processChunk(c));
            } else {
                processChunk(chunk);
            }
        }
        scanCursor = end;
    }

    private void processChunk(Chunk chunk) {
        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof LivingEntity))
                continue;
            LivingEntity mob = (LivingEntity) entity;

            if (!isCandidate(entity)) {
                if (isDumbed(mob))
                    restore(mob);
                continue;
            }
            // Skip if a player is nearby (mob should stay active)
            if (isPlayerNearby(mob)) {
                if (isDumbed(mob))
                    restore(mob);
                continue;
            }

            // Check light level
            int light = mob.getLocation().getBlock().getLightLevel();
            if (light <= maxLightLevel) {
                if (!isDumbed(mob) && mob.isValid()) {
                    dumb(mob);
                }
            } else {
                // Light changed (someone placed torches etc.) — restore
                if (isDumbed(mob))
                    restore(mob);
            }
        }
    }

    // ══════════════════════════════════════════════════
    // Dumb / Restore
    // ══════════════════════════════════════════════════

    private void dumb(LivingEntity mob) {
        mob.setAI(false);
        mob.getPersistentDataContainer().set(FARM_DUMB_KEY, PersistentDataType.BYTE, (byte) 1);
        dumbed.incrementAndGet();
    }

    private void restore(LivingEntity mob) {
        mob.setAI(true);
        mob.getPersistentDataContainer().remove(FARM_DUMB_KEY);
        dumbed.updateAndGet(n -> Math.max(0, n - 1));
    }

    private boolean isDumbed(LivingEntity mob) {
        return mob.getPersistentDataContainer().has(FARM_DUMB_KEY);
    }

    // ══════════════════════════════════════════════════
    // Interaction — instant restore on player click/trade
    // ══════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!enabled)
            return;
        Entity e = event.getRightClicked();
        if (!(e instanceof LivingEntity le))
            return;
        if (isDumbed(le))
            restore(le);
    }

    // ══════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════

    /**
     * Returns true if this entity should be considered for AI suppression.
     * Skips: players, bosses, protected/custom entities, mounts, leashed mobs,
     * and types not in the include-list (if configured).
     */
    private boolean isCandidate(Entity entity) {
        if (!(entity instanceof Monster || entity instanceof Slime))
            return false;
        if (entity instanceof Player)
            return false;

        // Skip bosses
        EntityType type = entity.getType();
        if (type == EntityType.WITHER || type == EntityType.ENDER_DRAGON
                || type == EntityType.ELDER_GUARDIAN)
            return false;

        // Skip if riding or being ridden
        if (entity.getVehicle() != null || !entity.getPassengers().isEmpty())
            return false;

        LivingEntity le = (LivingEntity) entity;

        // Skip leashed mobs (player's pet / named mob)
        if (le.isLeashed())
            return false;

        // Skip protected entities (MythicMobs, Citizens, etc.)
        if (plugin.getCompatManager().isProtectedEntity(entity))
            return false;

        // If type filter is set, only include listed types
        if (!includedTypes.isEmpty() && !includedTypes.contains(type))
            return false;

        return true;
    }

    /**
     * Checks if any online player is within the activation range.
     * Folia-safe: we only check players in the same world; cross-region entity
     * reads via {@code World#getPlayers()} are thread-safe on Paper/Folia.
     */
    private boolean isPlayerNearby(LivingEntity mob) {
        for (Player p : mob.getWorld().getPlayers()) {
            double dx = p.getX() - mob.getX();
            double dy = p.getY() - mob.getY();
            double dz = p.getZ() - mob.getZ();
            if (dx * dx + dy * dy + dz * dz <= playerActivationRangeSq) {
                return true;
            }
        }
        return false;
    }

    // ── Getters ──────────────────────────────────────

    public int getDumbedCount() {
        return dumbed.get();
    }

    public boolean isEnabled() {
        return enabled;
    }
}
