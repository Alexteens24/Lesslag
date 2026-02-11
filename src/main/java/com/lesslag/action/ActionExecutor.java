package com.lesslag.action;

import com.lesslag.LessLag;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class ActionExecutor {

    private final LessLag plugin;
    private final Set<String> entityWhitelist;
    private final Set<String> aiProtected;

    /**
     * All valid action keys that users can use in threshold configs.
     */
    public static final Set<String> AVAILABLE_ACTIONS = Set.of(
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
            "unload-world-chunks");

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
            "unload-world-chunks");

    public ActionExecutor(LessLag plugin) {
        this.plugin = plugin;
        this.entityWhitelist = new HashSet<>();
        this.aiProtected = new HashSet<>();
        loadConfig();
    }

    public void loadConfig() {
        entityWhitelist.clear();
        aiProtected.clear();

        List<String> whitelist = plugin.getConfig().getStringList("entity-whitelist");
        for (String entry : whitelist)
            entityWhitelist.add(entry.toUpperCase());

        List<String> aiProt = plugin.getConfig().getStringList("ai-optimization.protected");
        for (String entry : aiProt)
            aiProtected.add(entry.toUpperCase());
    }

    // ══════════════════════════════════════════════════
    // Dynamic Action Executor
    // ══════════════════════════════════════════════════

    /**
     * Execute a list of actions by their config keys.
     * Must be called from the main thread.
     */
    public void executeActions(List<String> actionKeys) {
        for (String key : actionKeys) {
            executeAction(key);
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
        for (String key : actionKeys) {
            // force-gc can run on any thread, but all others need main thread
            syncActions.add(key);
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
        switch (actionKey.toLowerCase()) {
            case "clear-ground-items": {
                int cleared = clearGroundItems();
                if (cleared > 0)
                    plugin.getLogger().info("[Action] Cleared " + cleared + " ground items");
                break;
            }
            case "clear-xp-orbs": {
                int cleared = clearXPOrbs();
                if (cleared > 0)
                    plugin.getLogger().info("[Action] Cleared " + cleared + " XP orbs");
                break;
            }
            case "clear-mobs": {
                int cleared = clearExcessMobs();
                if (cleared > 0)
                    plugin.getLogger().info("[Action] Removed " + cleared + " excess mobs");
                break;
            }
            case "kill-hostile-mobs": {
                int killed = killHostileMobs();
                if (killed > 0)
                    plugin.getLogger().info("[Action] Killed " + killed + " hostile mobs");
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
                int disabled = disableMobAI();
                if (disabled > 0)
                    plugin.getLogger().info("[Action] Disabled AI for " + disabled + " entities");
                break;
            }
            case "force-gc": {
                forceGC();
                break;
            }
            case "chunk-clean": {
                int cleaned = chunkClean();
                if (cleaned > 0) {
                    plugin.getLogger().info("[Action] Cleaned " + cleaned + " chunks");
                }
                break;
            }
            case "enforce-entity-limits": {
                int removed = enforceEntityLimits();
                if (removed > 0)
                    plugin.getLogger()
                            .info("[Action] Entity limits enforced — removed " + removed + " excess entities");
                break;
            }
            case "unload-world-chunks": {
                int unloaded = unloadWorldChunks();
                if (unloaded > 0)
                    plugin.getLogger().info("[Action] Unloaded " + unloaded + " excess chunks across all worlds");
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
     * Clear all dropped items on the ground
     */
    public int clearGroundItems() {
        int count = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Item) {
                    entity.remove();
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Clear all XP orbs on the ground
     */
    public int clearXPOrbs() {
        int count = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof ExperienceOrb) {
                    entity.remove();
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Clear excess non-whitelisted, unnamed, untamed living entities
     */
    public int clearExcessMobs() {
        int count = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (shouldRemoveEntity(entity)) {
                    entity.remove();
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Kill all hostile mobs without custom names
     */
    public int killHostileMobs() {
        int count = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Monster && !isProtected(entity)) {
                    entity.remove();
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Disable AI for mobs far from players to reduce pathfinding load
     */
    public int disableMobAI() {

        int radius = plugin.getConfig().getInt("ai-optimization.active-radius", 48);
        int count = 0;

        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Mob))
                    continue;
                if (aiProtected.contains(entity.getType().name()))
                    continue;
                if (isProtected(entity))
                    continue;

                Mob mob = (Mob) entity;

                boolean nearPlayer = false;
                for (Player player : world.getPlayers()) {
                    if (entity.getLocation().distanceSquared(player.getLocation()) <= radius * radius) {
                        nearPlayer = true;
                        break;
                    }
                }

                if (!nearPlayer && plugin.isMobAwareSafe(mob)) {
                    if (plugin.setMobAwareSafe(mob, false)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /**
     * Re-enable AI for all mobs (used during recovery)
     */
    public int restoreMobAI() {

        int count = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Mob) {
                    Mob mob = (Mob) entity;
                    if (!plugin.isMobAwareSafe(mob)) {
                        if (plugin.setMobAwareSafe(mob, true)) {
                            count++;
                        }
                    }
                }
            }
        }
        return count;
    }

    /**
     * Clear all entities (items + xp + mobs)
     */
    public int clearAll() {
        return clearGroundItems() + clearXPOrbs() + clearExcessMobs();
    }

    /**
     * Reduce view distance for all worlds
     */
    public void reduceViewDistance() {
        int minVD = plugin.getConfig().getInt("action-settings.min-view-distance", 4);
        int reduceBy = plugin.getConfig().getInt("action-settings.view-distance-reduce-by", 2);
        for (World world : Bukkit.getWorlds()) {
            int currentVD = world.getViewDistance();
            if (currentVD > minVD) {
                int newVD = Math.max(minVD, currentVD - reduceBy);
                world.setViewDistance(newVD);
                plugin.getLogger().info("View Distance [" + world.getName() + "]: " + currentVD + " -> " + newVD);
            }
        }
    }

    /**
     * Reduce simulation distance for all worlds
     */
    public void reduceSimulationDistance() {
        int minSD = plugin.getConfig().getInt("action-settings.min-simulation-distance", 4);
        int reduceBy = plugin.getConfig().getInt("action-settings.simulation-distance-reduce-by", 2);
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

        int totalUnloaded = 0;
        double overloadMultiplier = plugin.getConfig().getDouble("world-chunk-guard.overload-multiplier", 2.0);

        for (World world : Bukkit.getWorlds()) {
            int playerCount = world.getPlayers().size();
            int viewDistance = world.getViewDistance();
            int loadedChunks = world.getLoadedChunks().length;
            int chunksPerPlayer = (viewDistance * 2 + 1) * (viewDistance * 2 + 1);
            int expectedMax = Math.max(playerCount * chunksPerPlayer, 100);

            if (loadedChunks > (int) (expectedMax * overloadMultiplier)) {
                int excess = loadedChunks - expectedMax;
                org.bukkit.Chunk[] chunks = world.getLoadedChunks();
                int unloaded = 0;
                for (org.bukkit.Chunk chunk : chunks) {
                    if (unloaded >= excess)
                        break;
                    // Use safe unload(true) to ensure data is saved
                    if (chunk.unload(true))
                        unloaded++;
                }
                totalUnloaded += unloaded;
            }
        }
        return totalUnloaded;
    }

    /**
     * Force Java garbage collection
     */
    public long forceGC() {
        Runtime runtime = Runtime.getRuntime();
        long beforeMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        System.gc();
        long afterMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long freedMB = Math.max(0, beforeMB - afterMB);
        if (freedMB > 0) {
            plugin.getLogger()
                    .info("GC: Freed " + freedMB + "MB (before: " + beforeMB + "MB, after: " + afterMB + "MB)");
        }
        return freedMB;
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
    public int enforceEntityLimits() {
        var section = plugin.getConfig().getConfigurationSection("entity-limits");
        if (section == null)
            return 0;

        int globalDefault = section.getInt("default", -1);
        int totalRemoved = 0;

        for (World world : Bukkit.getWorlds()) {
            // Count entities by type
            Map<String, List<Entity>> byType = new HashMap<>();
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Player)
                    continue;
                byType.computeIfAbsent(entity.getType().name(), k -> new ArrayList<>()).add(entity);
            }

            // Cache players list for this world
            List<Player> players = world.getPlayers();

            for (Map.Entry<String, List<Entity>> entry : byType.entrySet()) {
                String type = entry.getKey();
                List<Entity> entities = entry.getValue();

                // Look up limit: specific type first, then global default
                int limit;
                if (section.contains(type)) {
                    limit = section.getInt(type, -1);
                } else {
                    limit = globalDefault;
                }

                // -1 means no limit for this type
                if (limit < 0 || entities.size() <= limit)
                    continue;

                int excess = entities.size() - limit;

                // Sort: remove entities furthest from nearest player first
                if (!players.isEmpty()) {
                    // Pre-calculate distances to avoid O(N*M) in sort
                    Map<Entity, Double> distances = new HashMap<>();
                    for (Entity entity : entities) {
                        distances.put(entity, nearestPlayerDistSq(entity, players));
                    }

                    entities.sort((a, b) -> {
                        double distA = distances.get(a);
                        double distB = distances.get(b);
                        return Double.compare(distB, distA); // furthest first
                    });
                }

                int removed = 0;
                for (Entity entity : entities) {
                    if (removed >= excess)
                        break;
                    entity.remove();
                    removed++;
                }

                if (removed > 0) {
                    plugin.getLogger().warning("[EntityLimit] " + world.getName()
                            + ": removed " + removed + " " + type
                            + " (had " + entities.size() + ", limit " + limit + ")");
                }
                totalRemoved += removed;
            }
        }

        return totalRemoved;
    }

    private double nearestPlayerDistSq(Entity entity, List<Player> players) {
        double nearest = Double.MAX_VALUE;
        for (Player player : players) {
            try {
                double dist = entity.getLocation().distanceSquared(player.getLocation());
                if (dist < nearest)
                    nearest = dist;
            } catch (Exception ignored) {
            } // different worlds
        }
        return nearest;
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
