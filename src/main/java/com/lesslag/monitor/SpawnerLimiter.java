package com.lesslag.monitor;

import com.lesslag.LessLag;
import com.lesslag.util.SchedulerAdapter;

import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SpawnerLimiter — Limits the number of entities a spawner can produce
 * per chunk (and optionally nearby chunks), inspired by MobLimit.
 *
 * <p>
 * <b>Design goals:</b>
 * <ul>
 * <li>Event-driven: no background scan needed — fires only when a spawner tries
 * to spawn.</li>
 * <li>O(entities in radius-chunks) per event — bounded by chunk entity
 * count.</li>
 * <li><b>Folia-safe</b>: {@code SpawnerSpawnEvent} fires on the chunk's
 * regional thread;
 * chunk entity access stays within that thread's owned region.</li>
 * <li>Two actions: {@code CANCEL} (deny spawn entirely) or {@code DUMB}
 * (allow but disable AI — keeps mob farm loot drops working).</li>
 * </ul>
 *
 * <p>
 * <b>Config example:</b>
 * 
 * <pre>
 * spawner-limiter:
 *   enabled: true
 *   action: CANCEL          # CANCEL or DUMB
 *   radius-chunks: 1        # 0 = spawner chunk only, 1 = 3x3 chunks, 2 = 5x5 chunks
 *   global-limit: -1        # fallback for types not listed (-1 = disabled)
 *   limits:
 *     ZOMBIE: 16
 *     SKELETON: 16
 * </pre>
 */
public class SpawnerLimiter implements Listener {

    public enum Action {
        CANCEL, DUMB
    }

    private final LessLag plugin;
    private final NamespacedKey SPAWNER_DUMB_KEY;

    // Config
    private boolean enabled;
    private Action action;
    private int radiusChunks;
    private int globalLimit;
    private Map<EntityType, Integer> limits;

    // Stats
    private final AtomicLong totalLimited = new AtomicLong(0);
    private volatile String lastLimitedType = "";

    public SpawnerLimiter(LessLag plugin) {
        this.plugin = plugin;
        this.SPAWNER_DUMB_KEY = new NamespacedKey(plugin, "spawner_dumb");
        loadConfig();
    }

    private void loadConfig() {
        enabled = plugin.getConfig().getBoolean("modules.spawner-limiter.enabled", true);
        radiusChunks = plugin.getConfig().getInt("modules.spawner-limiter.radius-chunks", 1);
        globalLimit = plugin.getConfig().getInt("modules.spawner-limiter.global-limit", -1);

        String actionStr = plugin.getConfig().getString("modules.spawner-limiter.action", "CANCEL").toUpperCase();
        try {
            action = Action.valueOf(actionStr);
        } catch (IllegalArgumentException e) {
            action = Action.CANCEL;
        }

        limits = new EnumMap<>(EntityType.class);
        var section = plugin.getConfig().getConfigurationSection("modules.spawner-limiter.limits");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    EntityType type = EntityType.valueOf(key.toUpperCase());
                    limits.put(type, section.getInt(key));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("[SpawnerLimiter] Unknown entity type in config: " + key);
                }
            }
        }
    }

    public void start() {
        if (!enabled)
            return;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("SpawnerLimiter started (action=" + action
                + ", radius=" + radiusChunks + " chunks, " + limits.size() + " type limits)");
    }

    public void stop() {
        HandlerList.unregisterAll(this);
    }

    // ══════════════════════════════════════════════════
    // Event Handler
    // ══════════════════════════════════════════════════

    /**
     * Fires on the spawner chunk's regional thread in Folia — safe to access
     * chunk entities directly.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawnerSpawn(SpawnerSpawnEvent event) {
        if (!enabled)
            return;

        EntityType type = event.getEntityType();
        int limit = limits.getOrDefault(type, globalLimit);
        if (limit < 0)
            return; // no limit configured for this type

        // Skip protected entities (MythicMobs, Citizens, ModelEngine, etc.)
        if (plugin.getCompatManager().isProtectedEntity(event.getEntity()))
            return;

        Chunk spawnerChunk = event.getLocation().getChunk();
        World world = spawnerChunk.getWorld();
        int cx = spawnerChunk.getX();
        int cz = spawnerChunk.getZ();
        int r = radiusChunks;

        // Count matching entities in radius (chunk-grid based, Folia-safe)
        int count = 0;
        outer: for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                Chunk chunk = world.getChunkAt(cx + dx, cz + dz);
                if (!chunk.isLoaded())
                    continue;
                for (Entity e : chunk.getEntities()) {
                    if (e.getType() == type) {
                        count++;
                        if (count >= limit)
                            break outer; // short-circuit
                    }
                }
            }
        }

        if (count >= limit) {
            totalLimited.incrementAndGet();
            lastLimitedType = type.name();

            if (action == Action.DUMB) {
                // Allow spawn but lobotomize — preserves loot drops at night
                Entity entity = event.getEntity();
                if (entity instanceof LivingEntity le) {
                    SchedulerAdapter.runAtEntity(plugin, le, () -> {
                        le.setAI(false);
                        le.getPersistentDataContainer().set(SPAWNER_DUMB_KEY, PersistentDataType.BYTE, (byte) 1);
                    });
                }
            } else {
                event.setCancelled(true);
            }
        }
    }

    // ── Getters ──────────────────────────────────────

    public long getTotalLimited() {
        return totalLimited.get();
    }

    public String getLastLimitedType() {
        return lastLimitedType;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
