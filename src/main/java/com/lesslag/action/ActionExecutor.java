package com.lesslag.action;

import com.lesslag.LessLag;
import com.lesslag.WorkloadDistributor;
import com.lesslag.util.NotificationHelper;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class ActionExecutor {

    private final LessLag plugin;
    private final Set<String> entityWhitelist;
    private final Set<String> aiProtected;
    private FileConfiguration messagesConfig;

    /**
     * Sorted list for consistent display order.
     */
    public static final List<String> ACTIONS_SORTED = List.of(
            "clear-ground-items",
            "clear-xp-orbs",
            "clear-mobs",
            "kill-hostile-mobs",
            "reduce-view-distance",
            "reduce-simulation-distance",
            "disable-mob-ai",
            "force-gc",
            "chunk-clean",
            "enforce-entity-limits",
            "unload-world-chunks",
            "notify-admin");

    /**
     * All valid action keys that users can use in threshold configs.
     * Derived from ACTIONS_SORTED to ensure consistency.
     */
    public static final Set<String> AVAILABLE_ACTIONS = new HashSet<>(ACTIONS_SORTED);

    public ActionExecutor(LessLag plugin) {
        this.plugin = plugin;
        this.entityWhitelist = new HashSet<>();
        this.aiProtected = new HashSet<>();
        loadConfig();
    }

    public void loadConfig() {
        entityWhitelist.clear();
        aiProtected.clear();

        List<String> whitelist = plugin.getConfig().getStringList("modules.entities.chunk-limiter.whitelist");
        for (String entry : whitelist)
            entityWhitelist.add(entry.toUpperCase());

        List<String> aiProt = plugin.getConfig().getStringList("modules.mob-ai.protected");
        for (String entry : aiProt)
            aiProtected.add(entry.toUpperCase());

        File msgFile = new File(plugin.getDataFolder(), "messages.yml");
        if (msgFile.exists()) {
            messagesConfig = YamlConfiguration.loadConfiguration(msgFile);
        }
    }

    private String getMsg(String key, String def) {
        if (messagesConfig != null) {
            String val = messagesConfig.getString(key);
            if (val != null) {
                return val.replace("&", "§"); // Simple color translation
            }
        }
        return def;
    }

    // ══════════════════════════════════════════════════
    // Dynamic Action Executor
    // ══════════════════════════════════════════════════

    /**
     * Execute a list of actions by their config keys.
     * Must be called from the main thread.
     */
    public void executeActions(List<String> actionKeys) {
        Set<String> executed = new HashSet<>();
        for (String key : actionKeys) {
            if (executed.add(key.toLowerCase())) {
                executeAction(key);
            }
        }
    }

    /**
     * Async entry point: runs read-only analysis on the async thread,
     * then dispatches entity modifications to the main thread.
     * Returns a CompletableFuture that completes when all actions are done.
     */
    public CompletableFuture<Void> executeActionsAsync(List<String> actionKeys) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        // Separate read-only actions from modification actions
        List<String> syncActions = new ArrayList<>();
        Set<String> uniqueKeys = new HashSet<>();
        for (String key : actionKeys) {
            // Deduplicate here as well to avoid redundant scheduling
            if (uniqueKeys.add(key.toLowerCase())) {
                syncActions.add(key);
            }
        }

        // Log analysis info async (safe — read-only)
        if (plugin.getAsyncExecutor() == null || plugin.getAsyncExecutor().isShutdown()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    executeActions(syncActions);
                    future.complete(null);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
            return future;
        }

        try {
            plugin.getAsyncExecutor().execute(() -> {
                try {
                    // Quick entity count snapshot for logging (approximation)
                    // Actual modifications dispatched to main thread
                    plugin.getLogger().info("[Async] Preparing " + syncActions.size()
                            + " actions for main-thread execution");
                } catch (Exception e) {
                    plugin.getLogger().warning("[Async] Analysis error: " + e.getMessage());
                }

                // Dispatch all modifications to the main thread
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        executeActions(syncActions);
                        future.complete(null);
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    }
                });
            });
        } catch (Exception e) {
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * Execute console commands with placeholder support.
     */
    public void executeCommands(List<String> commands, double currentTPS) {
        for (String cmd : commands) {
            String formatted = cmd.replace("{tps}", String.format("%.1f", currentTPS));
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), formatted);
                plugin.getLogger().info("[Command] Executed: " + formatted);
            } catch (Exception e) {
                plugin.getLogger().warning("[Command] Failed to execute: " + formatted + " — " + e.getMessage());
            }
        }
    }

    /**
     * Execute a single action by its config key.
     */
    public void executeAction(String actionKey) {
        String scheduledMsg = getMsg("action.scheduled", "&aScheduled cleanup task: %action%");
        switch (actionKey.toLowerCase()) {
            case "clear-ground-items": {
                clearGroundItems();
                plugin.getLogger().info(scheduledMsg.replace("%action%", "Clear Ground Items"));
                break;
            }
            case "clear-xp-orbs": {
                clearXPOrbs();
                plugin.getLogger().info(scheduledMsg.replace("%action%", "Clear XP Orbs"));
                break;
            }
            case "clear-mobs": {
                clearExcessMobs();
                plugin.getLogger().info(scheduledMsg.replace("%action%", "Clear Excess Mobs"));
                break;
            }
            case "kill-hostile-mobs": {
                killHostileMobs();
                plugin.getLogger().info(scheduledMsg.replace("%action%", "Kill Hostile Mobs"));
                break;
            }
            case "reduce-view-distance": {
                reduceViewDistance();
                break;
            }
            case "reduce-simulation-distance": {
                reduceSimulationDistance();
                break;
            }
            case "disable-mob-ai": {
                disableMobAI();
                plugin.getLogger().info(scheduledMsg.replace("%action%", "Disable Mob AI"));
                break;
            }
            case "force-gc": {
                forceGC();
                break;
            }
            case "chunk-clean": {
                int cleaned = chunkClean();
                // ChunkLimiter logs itself
                break;
            }
            case "enforce-entity-limits": {
                enforceEntityLimits();
                plugin.getLogger().info(scheduledMsg.replace("%action%", "Enforce Entity Limits"));
                break;
            }
            case "unload-world-chunks": {
                int unloaded = unloadWorldChunks();
                if (unloaded > 0)
                    plugin.getLogger().info("[Action] Unloaded " + unloaded + " excess chunks across all worlds");
                break;
            }
            case "notify-admin": {
                // Predictive optimization sends its own detailed notification.
                // This action exists to prevent errors when used as a default in config.
                NotificationHelper.notifyAdminsAsync("&e⚠ &7[Action] &fAdmin notification triggered.");
                break;
            }
            default: {
                plugin.getLogger().warning("Unknown action: '" + actionKey + "'");
                break;
            }
        }
    }

    // ══════════════════════════════════════════════════
    // Individual Action Methods
    // ══════════════════════════════════════════════════

    /**
     * Helper method to process entities with a filter
     */
    private void processEntities(java.util.function.Predicate<Entity> filter) {
        WorkloadDistributor distributor = plugin.getWorkloadDistributor();
        int batchSize = 20;

        for (World world : Bukkit.getWorlds()) {
            Chunk[] chunks = world.getLoadedChunks();
            List<Chunk> batch = new ArrayList<>(batchSize);

            for (Chunk chunk : chunks) {
                batch.add(chunk);
                if (batch.size() >= batchSize) {
                    final List<Chunk> currentBatch = new ArrayList<>(batch);
                    distributor.addWorkload(() -> {
                        for (Chunk c : currentBatch) {
                            if (!c.isLoaded())
                                continue;
                            for (Entity entity : c.getEntities()) {
                                if (filter.test(entity)) {
                                    if (!entity.isValid())
                                        continue;
                                    entity.remove();
                                }
                            }
                        }
                    });
                    batch.clear();
                }
            }

            if (!batch.isEmpty()) {
                final List<Chunk> currentBatch = new ArrayList<>(batch);
                distributor.addWorkload(() -> {
                    for (Chunk c : currentBatch) {
                        if (!c.isLoaded())
                            continue;
                        for (Entity entity : c.getEntities()) {
                            if (filter.test(entity)) {
                                if (!entity.isValid())
                                    continue;
                                entity.remove();
                            }
                        }
                    }
                });
            }
        }
    }

    /**
     * Clear all dropped items on the ground
     */
    public void clearGroundItems() {
        processEntities(entity -> entity instanceof Item);
    }

    /**
     * Clear all XP orbs on the ground
     */
    public void clearXPOrbs() {
        processEntities(entity -> entity instanceof ExperienceOrb);
    }

    /**
     * Clear excess non-whitelisted, unnamed, untamed living entities
     */
    public void clearExcessMobs() {
        processEntities(entity -> entity instanceof LivingEntity && shouldRemoveEntity(entity));
    }

    /**
     * Kill all hostile mobs without custom names
     */
    public void killHostileMobs() {
        processEntities(entity -> entity instanceof Monster && !isProtected(entity));
    }

    /**
     * Disable AI for mobs far from players to reduce pathfinding load
     */
    public void disableMobAI() {
        if (plugin.getCompatManager().isDABEnabled()) {
            plugin.getLogger().info("Skipping disable-mob-ai action because Pufferfish DAB is enabled.");
            return;
        }

        int radius = plugin.getConfig().getInt("modules.mob-ai.active-radius", 48);
        int chunkRadius = (radius >> 4) + 1;
        WorkloadDistributor distributor = plugin.getWorkloadDistributor();

        for (World world : Bukkit.getWorlds()) {
            // Capture player chunks on main thread to avoid async issues
            Set<Long> playerChunks = new HashSet<>();
            for (Player player : world.getPlayers()) {
                int px = player.getLocation().getBlockX() >> 4;
                int pz = player.getLocation().getBlockZ() >> 4;
                for (int x = px - chunkRadius; x <= px + chunkRadius; x++) {
                    for (int z = pz - chunkRadius; z <= pz + chunkRadius; z++) {
                        playerChunks.add(((long) x << 32) | (z & 0xFFFFFFFFL));
                    }
                }
            }

            Chunk[] chunks = world.getLoadedChunks();
            for (Chunk chunk : chunks) {
                distributor.addWorkload(() -> {
                    if (!chunk.isLoaded())
                        return;
                    long chunkKey = ((long) chunk.getX() << 32) | (chunk.getZ() & 0xFFFFFFFFL);
                    if (playerChunks.contains(chunkKey))
                        return;

                    for (Entity entity : chunk.getEntities()) {
                        if (entity instanceof Mob) {
                            Mob mob = (Mob) entity;
                            if (aiProtected.contains(mob.getType().name()))
                                continue;
                            if (isProtected(mob))
                                continue;

                            if (plugin.isMobAwareSafe(mob)) {
                                plugin.setMobAwareSafe(mob, false);
                            }
                        }
                    }
                });
            }
        }
    }

    /**
     * Re-enable AI for all mobs (used during recovery)
     */
    public int restoreMobAI() {

        int count = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Mob mob : world.getEntitiesByClass(Mob.class)) {
                if (!plugin.isMobAwareSafe(mob)) {
                    if (plugin.setMobAwareSafe(mob, true)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /**
     * Clear all entities (items + xp + mobs)
     */
    public void clearAll() {
        clearGroundItems();
        clearXPOrbs();
        clearExcessMobs();
    }

    /**
     * Reduce view distance for all worlds
     */
    public void reduceViewDistance() {
        for (World world : Bukkit.getWorlds()) {
            reduceViewDistance(world);
        }
    }

    /**
     * Reduce view distance for a specific world
     */
    public void reduceViewDistance(World world) {
        int minVD = plugin.getConfig().getInt("modules.chunks.view-distance.min", 4);
        int reduceBy = plugin.getConfig().getInt("modules.chunks.view-distance.reduce-by", 2);
        int currentVD = world.getViewDistance();
        if (currentVD > minVD) {
            int newVD = Math.max(minVD, currentVD - reduceBy);
            world.setViewDistance(newVD);
            plugin.getLogger().info("View Distance [" + world.getName() + "]: " + currentVD + " -> " + newVD);
        }
    }

    /**
     * Reduce simulation distance for all worlds
     */
    public void reduceSimulationDistance() {
        int minSD = plugin.getConfig().getInt("modules.chunks.simulation-distance.min", 4);
        int reduceBy = plugin.getConfig().getInt("modules.chunks.simulation-distance.reduce-by", 2);
        if (!plugin.isSimulationDistanceSupported()) {
            plugin.getLogger().fine("Simulation distance API not available");
            return;
        }

        for (World world : Bukkit.getWorlds()) {
            Integer currentSD = plugin.getSimulationDistanceSafe(world);
            if (currentSD == null) {
                continue;
            }
            if (currentSD > minSD) {
                int newSD = Math.max(minSD, currentSD - reduceBy);
                if (plugin.setSimulationDistanceSafe(world, newSD)) {
                    plugin.getLogger()
                            .info("Simulation Distance [" + world.getName() + "]: " + currentSD + " -> " + newSD);
                }
            }
        }
    }

    public int chunkClean() {
        if (plugin.getChunkLimiter() == null)
            return 0;
        plugin.getChunkLimiter().manualClean();
        plugin.getLogger().info("Smart Chunk Limiter scan started (async)...");
        return 0;
    }

    /**
     * Trigger chunk unload across all overloaded worlds.
     * Uses WorldChunkGuard's logic if available.
     */
    public int unloadWorldChunks() {
        if (plugin.getWorldChunkGuard() == null)
            return 0;

        int totalScheduled = 0;
        double overloadMultiplier = plugin.getConfig().getDouble("modules.chunks.world-guard.overload-multiplier", 2.0);
        WorkloadDistributor distributor = plugin.getWorkloadDistributor();

        for (World world : Bukkit.getWorlds()) {
            int playerCount = world.getPlayers().size();
            int viewDistance = world.getViewDistance();
            int loadedChunks = world.getLoadedChunks().length;
            int chunksPerPlayer = (viewDistance * 2 + 1) * (viewDistance * 2 + 1);
            int expectedMax = Math.max(playerCount * chunksPerPlayer, 100);

            if (loadedChunks > (int) (expectedMax * overloadMultiplier)) {
                int excess = loadedChunks - expectedMax;
                org.bukkit.Chunk[] chunks = world.getLoadedChunks();
                int scheduled = 0;
                for (org.bukkit.Chunk chunk : chunks) {
                    if (scheduled >= excess)
                        break;
                    distributor.addWorkload(() -> {
                        if (chunk.isLoaded()) {
                            chunk.unload();
                        }
                    });
                    scheduled++;
                }
                totalScheduled += scheduled;
            }
        }
        return totalScheduled;
    }

    /**
     * Force Java garbage collection
     */
    public long forceGC() {
        // Safety: Removed explicit GC to prevent "Stop-the-world" lag spikes
        plugin.getLogger().warning("Manual GC is disabled for safety reasons.");
        return 0;
    }

    /**
     * Restore all world settings and mob AI to original values
     */
    public void restoreDefaults() {
        for (World world : Bukkit.getWorlds()) {
            int originalVD = plugin.getOriginalViewDistance(world);
            Integer originalSD = plugin.getOriginalSimulationDistance(world);
            try {
                world.setViewDistance(originalVD);
            } catch (Exception ignored) {
            }
            if (originalSD != null) {
                plugin.setSimulationDistanceSafe(world, originalSD);
            }
        }

        int restored = restoreMobAI();
        plugin.getLogger().info("Restored view/simulation distances and re-enabled AI for "
                + restored + " mobs");
    }

    // ══════════════════════════════════════════════════
    // Helper Methods
    // ══════════════════════════════════════════════════

    private boolean shouldRemoveEntity(Entity entity) {
        if (!(entity instanceof LivingEntity))
            return false;
        if (entity instanceof Player)
            return false;
        if (entity instanceof ArmorStand)
            return false;
        if (entityWhitelist.contains(entity.getType().name()))
            return false;
        return !isProtected(entity);
    }

    private boolean isProtected(Entity entity) {
        if (LessLag.hasCustomName(entity))
            return true;
        if (entity instanceof Tameable && ((Tameable) entity).isTamed())
            return true;
        if (entity instanceof LivingEntity && ((LivingEntity) entity).isLeashed())
            return true;
        if (entityWhitelist.contains(entity.getType().name()))
            return true;
        if (!entity.getPassengers().isEmpty() || entity.getVehicle() != null)
            return true;
        return false;
    }

    /**
     * Get formatted memory info string
     */
    public String getMemoryInfo() {
        Runtime rt = Runtime.getRuntime();
        long usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMB = rt.maxMemory() / (1024 * 1024);
        return usedMB + "MB / " + maxMB + "MB (" + (usedMB * 100 / maxMB) + "%)";
    }

    /**
     * Count entities by type across all worlds
     */
    public Map<String, Integer> getEntityBreakdown() {
        Map<String, Integer> breakdown = new TreeMap<>();
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                breakdown.merge(entity.getType().name(), 1, (a, b) -> a + b);
            }
        }
        return breakdown;
    }

    /**
     * Enforce hard entity limits per type per world.
     * This OVERRIDES all protection (whitelist, named, tamed, etc.)
     * to prevent griefing via entity spam (e.g. 100 Withers).
     *
     * Reads limits from config: entity-limits.<TYPE> = max count per world.
     * A global "default" key sets the fallback for unlisted types.
     * Entities are removed newest-first (furthest from players first).
     */
    /**
     * Enforce hard entity limits per type per world.
     * This OVERRIDES all protection (whitelist, named, tamed, etc.)
     * to prevent griefing via entity spam.
     *
     * architecture: SYNC snapshot -> ASYNC sort -> SYNC execution
     */
    public void enforceEntityLimits() {
        var section = plugin.getConfig().getConfigurationSection("modules.entities.limits.per-world-limit");
        if (section == null)
            return;

        int globalDefault = section.getInt("default", -1);

        Map<String, Integer> limits = new HashMap<>();
        for (String key : section.getKeys(false)) {
            if ("default".equalsIgnoreCase(key))
                continue;
            limits.put(key.toUpperCase(), section.getInt(key));
        }

        // ── Phase 1: SYNC Snapshot ──
        // Capture all necessary data on main thread to avoid async API access
        Map<UUID, WorldSnapshot> snapshots = new HashMap<>();
        Map<UUID, String> worldNames = new HashMap<>();

        for (World world : Bukkit.getWorlds()) {
            List<Player> players = world.getPlayers();
            List<org.bukkit.util.Vector> playerLocs = new ArrayList<>(players.size());
            for (Player p : players) {
                playerLocs.add(p.getLocation().toVector());
            }

            List<EntitySnapshot> entitySnapshots = new ArrayList<>();
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Player)
                    continue;

                List<String> categories = new ArrayList<>();
                if (entity instanceof Monster)
                    categories.add("MONSTER");
                if (entity instanceof Animals)
                    categories.add("ANIMAL");
                if (entity instanceof WaterMob)
                    categories.add("WATER_ANIMAL");
                if (entity instanceof Ambient)
                    categories.add("AMBIENT");

                entitySnapshots.add(new EntitySnapshot(entity, entity.getLocation().toVector(), entity.getType().name(),
                        categories));
            }

            snapshots.put(world.getUID(), new WorldSnapshot(playerLocs, entitySnapshots));
            worldNames.put(world.getUID(), world.getName());
        }

        // ── Phase 2: ASYNC Analysis ──
        if (plugin.getAsyncExecutor() == null || plugin.getAsyncExecutor().isShutdown()) {
            plugin.getLogger().warning("[EntityLimit] Async executor unavailable; skipping enforcement.");
            return;
        }

        plugin.getAsyncExecutor().execute(() -> {
            Map<UUID, List<Entity>> toRemove = new HashMap<>();

            for (Map.Entry<UUID, WorldSnapshot> entry : snapshots.entrySet()) {
                UUID worldId = entry.getKey();
                WorldSnapshot snap = entry.getValue();
                String worldName = worldNames.getOrDefault(worldId, "unknown");

                // Group by type
                Map<String, List<EntitySnapshot>> byType = new HashMap<>();
                for (EntitySnapshot es : snap.entities) {
                    byType.computeIfAbsent(es.type, k -> new ArrayList<>()).add(es);
                }

                List<Entity> worldRemovalList = new ArrayList<>();

                for (Map.Entry<String, List<EntitySnapshot>> typeEntry : byType.entrySet()) {
                    String type = typeEntry.getKey();
                    List<EntitySnapshot> entities = typeEntry.getValue();

                    // Determine limit: Specific Type -> Category -> Default
                    int limit = -1;
                    if (limits.containsKey(type.toUpperCase())) {
                        limit = limits.get(type.toUpperCase());
                    } else if (!entities.isEmpty()) {
                        // Check categories (all entities in list have same categories/type)
                        for (String cat : entities.get(0).categories) {
                            if (limits.containsKey(cat)) {
                                limit = limits.get(cat);
                                break;
                            }
                        }
                    }

                    if (limit == -1)
                        limit = globalDefault;

                    if (limit < 0 || entities.size() <= limit)
                        continue;

                    int excess = entities.size() - limit;

                    // Sort by distance to nearest player (furthest first)
                    if (!snap.playerLocs.isEmpty()) {
                        // Pre-calculate distances
                        for (EntitySnapshot es : entities) {
                            es.distanceSq = nearestPlayerDistSq(es.loc, snap.playerLocs);
                        }

                        entities.sort((a, b) -> Double.compare(b.distanceSq, a.distanceSq));
                    }

                    // Mark for removal
                    for (int i = 0; i < excess; i++) {
                        worldRemovalList.add(entities.get(i).entity);
                    }

                    if (excess > 0) {
                        plugin.getLogger().warning("[EntityLimit] " + worldName
                                + ": scheduled removal of " + excess + " " + type
                                + " (had " + entities.size() + ", limit " + limit + ")");
                    }
                }

                if (!worldRemovalList.isEmpty()) {
                    toRemove.put(worldId, worldRemovalList);
                }
            }

            // ── Phase 3: SYNC Execution ──
            if (!toRemove.isEmpty()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    WorkloadDistributor distributor = plugin.getWorkloadDistributor();
                    int totalScheduled = 0;
                    int batchSize = 50;

                    for (List<Entity> list : toRemove.values()) {
                        List<Entity> batch = new ArrayList<>(batchSize);
                        for (Entity e : list) {
                            batch.add(e);
                            if (batch.size() >= batchSize) {
                                final List<Entity> currentBatch = new ArrayList<>(batch);
                                distributor.addWorkload(() -> {
                                    for (Entity entity : currentBatch) {
                                        if (entity.isValid())
                                            entity.remove();
                                    }
                                });
                                batch.clear();
                            }
                            totalScheduled++;
                        }

                        if (!batch.isEmpty()) {
                            final List<Entity> currentBatch = new ArrayList<>(batch);
                            distributor.addWorkload(() -> {
                                for (Entity entity : currentBatch) {
                                    if (entity.isValid())
                                        entity.remove();
                                }
                            });
                        }
                    }

                    if (totalScheduled > 0) {
                        plugin.getLogger().info("[EntityLimit] Scheduled " + totalScheduled
                                + " entities for removal via WorkloadDistributor");
                    }
                });
            }
        });
    }

    private double nearestPlayerDistSq(org.bukkit.util.Vector entityLoc, List<org.bukkit.util.Vector> playerLocs) {
        double nearest = Double.MAX_VALUE;
        for (org.bukkit.util.Vector pLoc : playerLocs) {
            double dist = entityLoc.distanceSquared(pLoc);
            if (dist < nearest)
                nearest = dist;
        }
        return nearest;
    }

    // Snapshot classes
    private static class WorldSnapshot {
        final List<org.bukkit.util.Vector> playerLocs;
        final List<EntitySnapshot> entities;

        WorldSnapshot(List<org.bukkit.util.Vector> p, List<EntitySnapshot> e) {
            this.playerLocs = p;
            this.entities = e;
        }
    }

    private static class EntitySnapshot {
        final Entity entity;
        final org.bukkit.util.Vector loc;
        final String type;
        final List<String> categories;
        double distanceSq = 0;

        EntitySnapshot(Entity e, org.bukkit.util.Vector l, String t, List<String> c) {
            this.entity = e;
            this.loc = l;
            this.type = t;
            this.categories = c;
        }
    }

    /**
     * Get total entity count across all worlds
     */
    public int getTotalEntityCount() {
        int total = 0;
        for (World world : Bukkit.getWorlds()) {
            total += world.getEntities().size();
        }
        return total;
    }
}
