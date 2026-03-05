package com.lesslag.monitor;

import com.lesslag.LessLag;
import com.lesslag.util.SchedulerAdapter;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Villager Lobotomizer — Optimizes villagers by disabling AI when they are
 * "trapped" (e.g. trading halls). Inspired by VillagerLobotomizer and
 * AntiVillagerLag.
 *
 * <p>
 * Features:
 * <ul>
 * <li>Automatically detects 1x1 trading cells</li>
 * <li>Disables AI for trapped villagers (massive performance gain)</li>
 * <li>Temporarily re-enables AI on interaction (trading/restocking)</li>
 * <li><b>Named control</b>: "nobrain" → force lobotomize, configurable
 * always-active list</li>
 * <li><b>Silent mode</b>: setSilent(true) while lobotomized (reduces audio
 * processing)</li>
 * <li><b>PDC persist</b>: lobotomized state survives chunk unload/reload via
 * PersistentDataContainer</li>
 * <li><b>Auto restock</b>: periodically restocks trades so players don't wait
 * for workstation visits</li>
 * <li><b>Zombie protection</b>: lobotomized villagers can't flee, so zombie
 * attacks are cancelled</li>
 * </ul>
 */
public class VillagerOptimizer implements Listener {

    // PDC key — persists lobotomized state across chunk unloads
    private final NamespacedKey LOBOTOMIZED_KEY;

    private final LessLag plugin;
    private SchedulerAdapter.TaskHandle scanTask;
    private SchedulerAdapter.TaskHandle cleanupTask;
    private SchedulerAdapter.TaskHandle restockTask;

    // Config
    private boolean enabled;
    private int checkInterval;
    private int restoreDuration;
    private boolean optimizeTrappedOnly;
    private boolean silentWhenLobotomized;
    private boolean persistState;
    private boolean autoRestockEnabled;
    private int autoRestockInterval; // ticks
    private boolean zombieProtection;
    private List<String> alwaysActiveNames; // names that NEVER get lobotomized
    private List<String> alwaysInactiveNames; // names that are ALWAYS lobotomized

    // State
    private final Map<UUID, ActiveVillagerInfo> activeVillagers = new ConcurrentHashMap<>();
    private final AtomicInteger optimizedVillagers = new AtomicInteger(0);

    // ── Incremental scan state ──
    private Chunk[] scanChunks = null;
    private int scanCursor = 0;
    private int scanWorldIndex = 0;
    private static final int VILLAGER_CHUNKS_PER_TICK = 20;

    private static class ActiveVillagerInfo {
        final long expiry;
        final UUID worldUID;
        final int chunkX, chunkZ;

        ActiveVillagerInfo(long expiry, UUID worldUID, int chunkX, int chunkZ) {
            this.expiry = expiry;
            this.worldUID = worldUID;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
    }

    public VillagerOptimizer(LessLag plugin) {
        this.plugin = plugin;
        this.LOBOTOMIZED_KEY = new NamespacedKey(plugin, "lobotomized");
        loadConfig();
    }

    private void loadConfig() {
        enabled = plugin.getConfig().getBoolean("modules.villager-optimizer.enabled", true);
        checkInterval = plugin.getConfig().getInt("modules.villager-optimizer.check-interval", 600);
        restoreDuration = plugin.getConfig().getInt("modules.villager-optimizer.ai-restore-duration", 30);
        optimizeTrappedOnly = plugin.getConfig().getBoolean("modules.villager-optimizer.optimize-trapped", true);
        silentWhenLobotomized = plugin.getConfig().getBoolean("modules.villager-optimizer.silent-when-lobotomized",
                true);
        persistState = plugin.getConfig().getBoolean("modules.villager-optimizer.persist-state", true);
        autoRestockEnabled = plugin.getConfig().getBoolean("modules.villager-optimizer.auto-restock.enabled", true);
        autoRestockInterval = plugin.getConfig().getInt("modules.villager-optimizer.auto-restock.interval-ticks",
                24000);
        zombieProtection = plugin.getConfig().getBoolean("modules.villager-optimizer.zombie-protection", true);

        List<String> rawActive = plugin.getConfig().getStringList("modules.villager-optimizer.always-active-names");
        List<String> rawInactive = plugin.getConfig().getStringList("modules.villager-optimizer.always-inactive-names");
        alwaysActiveNames = new ArrayList<>();
        alwaysInactiveNames = new ArrayList<>();
        for (String s : rawActive)
            alwaysActiveNames.add(s.toLowerCase());
        for (String s : rawInactive)
            alwaysInactiveNames.add(s.toLowerCase());
    }

    public void start() {
        if (!enabled)
            return;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        startScanPass();

        cleanupTask = SchedulerAdapter.runGlobalRepeating(plugin, this::cleanupActiveVillagers, 100L, 100L);

        if (autoRestockEnabled) {
            restockTask = SchedulerAdapter.runGlobalRepeating(plugin,
                    this::autoRestockLobotomizedVillagers, autoRestockInterval, autoRestockInterval);
        }

        plugin.getLogger().info("Villager Optimizer started (Interval: " + checkInterval + " ticks, "
                + (silentWhenLobotomized ? "silent, " : "")
                + (persistState ? "PDC-persist, " : "")
                + (autoRestockEnabled ? "auto-restock " + autoRestockInterval + "t, " : "")
                + (zombieProtection ? "zombie-protection" : "") + ")");
    }

    private void startScanPass() {
        if (scanTask != null)
            scanTask.cancel();
        scanChunks = null;
        scanWorldIndex = 0;
        scanCursor = 0;
        scanTask = SchedulerAdapter.runGlobalRepeating(plugin, this::scanIncremental, 1L, 1L);
    }

    public void stop() {
        if (scanTask != null) {
            scanTask.cancel();
            scanTask = null;
        }
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        if (restockTask != null) {
            restockTask.cancel();
            restockTask = null;
        }
        HandlerList.unregisterAll(this);
    }

    // ══════════════════════════════════════════════════
    // Lobotomize / Restore helpers (centralised)
    // ══════════════════════════════════════════════════

    /** Disable AI + apply all side-effects (silent, PDC, counter, metadata). */
    private void lobotomize(Villager v) {
        plugin.setMobAwareSafe(v, false);
        if (silentWhenLobotomized)
            v.setSilent(true);
        if (persistState) {
            v.getPersistentDataContainer().set(LOBOTOMIZED_KEY, PersistentDataType.BYTE, (byte) 1);
        }
        v.setMetadata("LessLag.VillagerOptimized",
                new org.bukkit.metadata.FixedMetadataValue(plugin, true));
        optimizedVillagers.incrementAndGet();
    }

    /** Re-enable AI + undo all side-effects. */
    private void restore(Villager v) {
        plugin.setMobAwareSafe(v, true);
        v.setSilent(false);
        v.getPersistentDataContainer().remove(LOBOTOMIZED_KEY);
        v.removeMetadata("LessLag.VillagerOptimized", plugin);
        v.removeMetadata("LessLag.LastTrappedCheck", plugin);
        optimizedVillagers.updateAndGet(n -> Math.max(0, n - 1));
    }

    private boolean isLobotomized(Villager v) {
        return v.hasMetadata("LessLag.VillagerOptimized");
    }

    // ══════════════════════════════════════════════════
    // Incremental scan
    // ══════════════════════════════════════════════════

    private void scanIncremental() {
        if (scanChunks == null || scanCursor >= scanChunks.length) {
            List<World> worlds = Bukkit.getWorlds();
            if (scanChunks != null && scanWorldIndex + 1 < worlds.size()) {
                scanWorldIndex++;
            } else {
                if (scanChunks != null) {
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
        int perTick = folia ? 6 : VILLAGER_CHUNKS_PER_TICK;
        int end = Math.min(scanCursor + perTick, scanChunks.length);
        for (int i = scanCursor; i < end; i++) {
            Chunk chunk = scanChunks[i];
            if (!chunk.isLoaded())
                continue;
            if (folia) {
                final Chunk c = chunk;
                SchedulerAdapter.runAtChunk(plugin, c.getWorld(), c.getX(), c.getZ(), () -> processVillagerChunk(c));
            } else {
                processVillagerChunk(chunk);
            }
        }
        scanCursor = end;
    }

    private void processVillagerChunk(Chunk chunk) {
        for (Entity entity : chunk.getEntities()) {
            if (entity.getType() != EntityType.VILLAGER)
                continue;

            Villager villager = (Villager) entity;
            if (activeVillagers.containsKey(villager.getUniqueId()))
                continue;
            if (!villager.isValid())
                continue;

            // Throttle re-checks for already-optimized villagers
            long now = System.nanoTime();
            if (isLobotomized(villager) && villager.hasMetadata("LessLag.LastTrappedCheck")) {
                long last = villager.getMetadata("LessLag.LastTrappedCheck").get(0).asLong();
                if (now - last < 120_000_000_000L)
                    continue;
            }

            if (plugin.getCompatManager().isProtectedEntity(villager))
                continue;

            // ── Named villager control ──
            NameTag nameTag = getNameTag(villager);
            if (nameTag == NameTag.ALWAYS_ACTIVE) {
                // Force restore if currently lobotomized
                if (isLobotomized(villager))
                    restore(villager);
                continue;
            }

            boolean forceInactive = (nameTag == NameTag.ALWAYS_INACTIVE);

            // ── Profession guard (skip unless forced) ──
            if (!forceInactive && !hasLearnedProfession(villager))
                continue;

            boolean shouldOptimize = forceInactive || !optimizeTrappedOnly || isTrapped(villager);

            if (shouldOptimize) {
                if (plugin.isMobAwareSafe(villager)) {
                    lobotomize(villager);
                }
                villager.setMetadata("LessLag.LastTrappedCheck",
                        new org.bukkit.metadata.FixedMetadataValue(plugin, now));
            } else if (!plugin.isMobAwareSafe(villager)) {
                // No longer trapped — restore
                restore(villager);
            }
        }
    }

    // ══════════════════════════════════════════════════
    // Event Handlers
    // ══════════════════════════════════════════════════

    /**
     * Re-enable AI on interact, then re-lobotomize after restoreDuration seconds.
     */
    @EventHandler
    public void onVillagerInteract(PlayerInteractEntityEvent event) {
        if (!enabled)
            return;
        if (event.getRightClicked().getType() != EntityType.VILLAGER)
            return;
        activateVillager((Villager) event.getRightClicked());
    }

    /**
     * PDC restore on chunk load — avoids re-scan lag spike.
     * Villagers whose chunk loads with LOBOTOMIZED_KEY set get immediately
     * re-lobotomized.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!enabled || !persistState)
            return;
        Chunk chunk = event.getChunk();
        // Dispatch on the chunk's regional thread (Folia-safe)
        if (SchedulerAdapter.isFolia()) {
            SchedulerAdapter.runAtChunk(plugin, chunk.getWorld(), chunk.getX(), chunk.getZ(),
                    () -> restoreFromPDC(chunk));
        } else {
            // Run next tick so entities are fully loaded
            SchedulerAdapter.runGlobalDelayed(plugin, () -> restoreFromPDC(chunk), 1L);
        }
    }

    private void restoreFromPDC(Chunk chunk) {
        for (Entity e : chunk.getEntities()) {
            if (!(e instanceof Villager))
                continue;
            Villager v = (Villager) e;
            if (!v.isValid())
                continue;
            if (activeVillagers.containsKey(v.getUniqueId()))
                continue;
            if (v.getPersistentDataContainer().has(LOBOTOMIZED_KEY, PersistentDataType.BYTE)) {
                // Re-apply lobotomy without incrementing counter (was already counted before
                // unload)
                plugin.setMobAwareSafe(v, false);
                if (silentWhenLobotomized)
                    v.setSilent(true);
                v.setMetadata("LessLag.VillagerOptimized",
                        new org.bukkit.metadata.FixedMetadataValue(plugin, true));
            }
        }
    }

    /**
     * Zombie protection: lobotomized villagers can't flee, so cancel zombie attacks
     * on them.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onZombieDamage(EntityDamageByEntityEvent event) {
        if (!enabled || !zombieProtection)
            return;
        if (!(event.getEntity() instanceof Villager))
            return;
        Villager v = (Villager) event.getEntity();
        if (!isLobotomized(v))
            return;

        EntityType attackerType = event.getDamager().getType();
        if (attackerType == EntityType.ZOMBIE
                || attackerType == EntityType.ZOMBIE_VILLAGER
                || attackerType == EntityType.HUSK
                || attackerType == EntityType.DROWNED) {
            event.setCancelled(true);
        }
    }

    // ══════════════════════════════════════════════════
    // Auto-Restock
    // ══════════════════════════════════════════════════

    /**
     * Periodically reset trade uses for all lobotomized villagers.
     * Mimics the vanilla restock that would happen if they could reach their
     * workstation.
     */
    private void autoRestockLobotomizedVillagers() {
        boolean folia = SchedulerAdapter.isFolia();
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                if (folia) {
                    final Chunk c = chunk;
                    SchedulerAdapter.runAtChunk(plugin, c.getWorld(), c.getX(), c.getZ(),
                            () -> restockChunk(c));
                } else {
                    restockChunk(chunk);
                }
            }
        }
    }

    private void restockChunk(Chunk chunk) {
        for (Entity e : chunk.getEntities()) {
            if (!(e instanceof Villager))
                continue;
            Villager v = (Villager) e;
            if (!isLobotomized(v))
                continue;
            if (activeVillagers.containsKey(v.getUniqueId()))
                continue;
            restockTrades(v);
        }
    }

    private void restockTrades(Villager v) {
        List<MerchantRecipe> recipes = v.getRecipes();
        if (recipes.isEmpty())
            return;
        for (MerchantRecipe recipe : recipes) {
            recipe.setUses(0);
        }
        v.setRecipes(recipes);
    }

    // ══════════════════════════════════════════════════
    // Active Villager Management
    // ══════════════════════════════════════════════════

    public void activateVillager(Villager villager) {
        if (!plugin.isMobAwareSafe(villager)) {
            restore(villager);
        }

        long expiry = System.nanoTime() + (restoreDuration * 1_000_000_000L);
        Location loc = villager.getLocation();
        activeVillagers.put(villager.getUniqueId(), new ActiveVillagerInfo(
                expiry, loc.getWorld().getUID(),
                loc.getBlockX() >> 4, loc.getBlockZ() >> 4));
    }

    private void cleanupActiveVillagers() {
        long now = System.nanoTime();
        Iterator<Map.Entry<UUID, ActiveVillagerInfo>> it = activeVillagers.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<UUID, ActiveVillagerInfo> entry = it.next();
            if (now > entry.getValue().expiry) {
                UUID uuid = entry.getKey();
                ActiveVillagerInfo info = entry.getValue();
                it.remove();

                if (SchedulerAdapter.isFolia()) {
                    World world = Bukkit.getWorld(info.worldUID);
                    if (world == null)
                        continue;
                    SchedulerAdapter.runAtChunk(plugin, world, info.chunkX, info.chunkZ, () -> {
                        Entity entity = Bukkit.getEntity(uuid);
                        if (entity instanceof Villager && entity.isValid()) {
                            Villager v = (Villager) entity;
                            if (shouldRelobotomize(v))
                                lobotomize(v);
                        }
                    });
                } else {
                    plugin.getWorkloadDistributor().addWorkload(() -> {
                        Entity entity = Bukkit.getEntity(uuid);
                        if (entity instanceof Villager && entity.isValid()) {
                            Villager v = (Villager) entity;
                            if (shouldRelobotomize(v))
                                lobotomize(v);
                        }
                    });
                }
            }
        }
    }

    private boolean shouldRelobotomize(Villager v) {
        NameTag tag = getNameTag(v);
        if (tag == NameTag.ALWAYS_ACTIVE)
            return false;
        if (tag == NameTag.ALWAYS_INACTIVE)
            return true;
        if (!hasLearnedProfession(v))
            return false;
        return !optimizeTrappedOnly || isTrapped(v);
    }

    // ══════════════════════════════════════════════════
    // Trapped Detection
    // ══════════════════════════════════════════════════

    private boolean isTrapped(Villager v) {
        if (v.getVehicle() != null)
            return true;

        Location loc = v.getLocation();
        Block feet = loc.getBlock();

        int confiningBlocks = 0;
        Block[] surroundings = {
                feet.getRelative(BlockFace.NORTH),
                feet.getRelative(BlockFace.SOUTH),
                feet.getRelative(BlockFace.EAST),
                feet.getRelative(BlockFace.WEST)
        };

        for (Block b : surroundings) {
            String name = b.getType().name();
            if (b.getType().isSolid()
                    || name.contains("GLASS")
                    || name.contains("FENCE")
                    || name.contains("WALL")
                    || name.contains("TRAPDOOR")
                    || name.contains("IRON_BARS")
                    || name.contains("DOOR")
                    || name.contains("GATE")) {
                confiningBlocks++;
            }
        }
        return confiningBlocks >= 3;
    }

    // ══════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════

    private enum NameTag {
        ALWAYS_ACTIVE, ALWAYS_INACTIVE, NORMAL
    }

    /**
     * Checks villager's custom name against the always-active/inactive lists.
     * Case-insensitive. "nobrain" forces lobotomy; configurable names skip it.
     */
    private NameTag getNameTag(Villager v) {
        net.kyori.adventure.text.Component nameComponent = v.customName();
        if (nameComponent == null)
            return NameTag.NORMAL;
        String lower = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(nameComponent).toLowerCase();
        for (String active : alwaysActiveNames) {
            if (lower.contains(active))
                return NameTag.ALWAYS_ACTIVE;
        }
        for (String inactive : alwaysInactiveNames) {
            if (lower.contains(inactive))
                return NameTag.ALWAYS_INACTIVE;
        }
        return NameTag.NORMAL;
    }

    /**
     * Returns true only if the villager has a real profession AND at least one
     * trade.
     * Villagers with NONE / NITWIT profession must keep AI to find a workstation.
     */
    private static boolean hasLearnedProfession(Villager v) {
        Villager.Profession p = v.getProfession();
        if (p == Villager.Profession.NONE || p == Villager.Profession.NITWIT)
            return false;
        return !v.getRecipes().isEmpty();
    }

    // ── Getters ──────────────────────────────────────

    public int getOptimizedCount() {
        return optimizedVillagers.get();
    }

    public int getActiveRestoredCount() {
        return activeVillagers.size();
    }
}
