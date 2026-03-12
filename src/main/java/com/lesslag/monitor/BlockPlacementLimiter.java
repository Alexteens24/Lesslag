package com.lesslag.monitor;

import com.lesslag.LessLag;
import com.lesslag.util.SchedulerAdapter;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * BlockPlacementLimiter — Preventive block limit enforcement per chunk.
 * Inspired by the Insights plugin's approach of blocking placement before
 * lag can occur, rather than cleaning up after the fact.
 *
 * <p>
 * <b>Architecture: unified async-populated cache, O(1) on event thread</b>
 * <p>
 * All materials go through the same cache:
 * <ul>
 * <li><b>Tile entities</b> (HOPPER, CHEST, FURNACE ...): cache is seeded
 * synchronously on first access via {@code getTileEntities()} — fast O(T),
 * called ONCE per chunk, not on every event.</li>
 * <li><b>Non-tile blocks</b> (PISTON, OBSERVER ...): cache seeded async via
 * {@code ChunkSnapshot} — zero main-thread cost.</li>
 * <li>After seeding: every place/break is a single {@link AtomicInteger}
 * increment/decrement — O(1), no scanning ever again.</li>
 * </ul>
 *
 * <p>
 * <b>Folia compatibility</b>: {@code BlockPlaceEvent}/{@code BlockBreakEvent}
 * dispatch on the block's regional thread. Cache reads/writes use
 * {@link ConcurrentHashMap} + {@link AtomicInteger} — thread-safe for
 * concurrent
 * regional threads. Player notifications use
 * {@link SchedulerAdapter#runAtEntity}.
 */
public class BlockPlacementLimiter implements Listener {

    /** Bypass permission — players with this node skip all limits. */
    public static final String BYPASS_PERMISSION = "lesslag.block-limit.bypass";

    /**
     * Tile-entity materials — their counts can be seeded synchronously and cheaply
     * via {@code Chunk#getTileEntities()} on first access (no async needed).
     */
    private static final Set<Material> TILE_ENTITY_MATERIALS;

    static {
        Set<Material> s = java.util.EnumSet.noneOf(Material.class);
        for (Material m : new Material[] {
                Material.HOPPER, Material.CHEST, Material.TRAPPED_CHEST,
                Material.DISPENSER, Material.DROPPER, Material.FURNACE,
                Material.BLAST_FURNACE, Material.SMOKER,
                Material.BREWING_STAND, Material.BEACON, Material.JUKEBOX,
                Material.LECTERN, Material.BELL
        }) {
            if (m != null)
                s.add(m);
        }
        TILE_ENTITY_MATERIALS = Collections.unmodifiableSet(s);
    }

    private final LessLag plugin;

    // Config
    private volatile boolean enabled;
    private volatile String messageTemplate;
    private volatile Map<Material, Integer> limits = Collections.emptyMap();

    /**
     * Unified per-chunk block-count cache.
     * Key: "world:chunkX:chunkZ" → material → count
     *
     * <p>
     * Cache lifecycle:
     * <ol>
     * <li>First event for a material in a chunk: cache miss.</li>
     * <li>Tile materials → seed synchronously via getTileEntities() (fast,
     * O(T)).</li>
     * <li>Non-tile materials → queue async ChunkSnapshot scan; allow this
     * placement.</li>
     * <li>All subsequent events → O(1) AtomicInteger get/increment/decrement.</li>
     * <li>ChunkUnloadEvent → remove entry (memory GC).</li>
     * </ol>
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<Material, AtomicInteger>> chunkCache = new ConcurrentHashMap<>();

    /** Chunks whose async scan is already in-flight (avoid duplicate scans). */
    private final Set<String> scanningChunks = ConcurrentHashMap.newKeySet();

    // Runtime stats
    private final AtomicLong totalBlocked = new AtomicLong(0);
    private final ConcurrentHashMap<Material, AtomicLong> blockedPerMaterial = new ConcurrentHashMap<>();

    public BlockPlacementLimiter(LessLag plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    // ══════════════════════════════════════════════════
    // Config
    // ══════════════════════════════════════════════════

    public void loadConfig() {
        this.enabled = plugin.getConfig().getBoolean("modules.block-placement-limiter.enabled", true);
        this.messageTemplate = plugin.getConfig().getString(
                "modules.block-placement-limiter.message",
                "<red>Chunk limit reached! Cannot place more <yellow>%material% <red>in this chunk (%count%/%limit%).");

        Map<Material, Integer> newLimits = new EnumMap<>(Material.class);
        org.bukkit.configuration.ConfigurationSection sec = plugin.getConfig()
                .getConfigurationSection("modules.block-placement-limiter.limits");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                try {
                    Material mat = Material.valueOf(key.toUpperCase());
                    int limit = sec.getInt(key, 32);
                    if (limit > 0)
                        newLimits.put(mat, limit);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("[BlockPlacementLimiter] Unknown material in config: " + key);
                }
            }
        }
        this.limits = Collections.unmodifiableMap(newLimits);

        // Invalidate cache on reload so new limits take effect
        chunkCache.clear();
        scanningChunks.clear();

        if (enabled) {
            plugin.getLogger().info("[BlockPlacementLimiter] Loaded " + newLimits.size() + " material limit(s).");
        }
    }

    // ══════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════

    public void start() {
        if (!enabled)
            return;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("BlockPlacementLimiter started (unified cache, O(1) per event).");
    }

    public void stop() {
        HandlerList.unregisterAll(this);
        chunkCache.clear();
        scanningChunks.clear();
    }

    // ══════════════════════════════════════════════════
    // Event Handlers
    // ══════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!enabled)
            return;

        Player player = event.getPlayer();
        if (player.hasPermission(BYPASS_PERMISSION))
            return;

        Block placed = event.getBlockPlaced();
        Material material = placed.getType();

        Map<Material, Integer> currentLimits = this.limits;
        Integer limit = currentLimits.get(material);
        if (limit == null)
            return;

        Chunk chunk = placed.getChunk();
        int count = getCount(chunk, material, currentLimits);

        if (count < 0) {
            // Cache miss for non-tile material — async scan triggered, allow this one.
            // The counter was already seeded to 1 inside getCount().
            return;
        }

        if (count >= limit) {
            event.setCancelled(true);
            totalBlocked.incrementAndGet();
            blockedPerMaterial.computeIfAbsent(material, k -> new AtomicLong(0)).incrementAndGet();

            // Notify player on their entity thread (Folia-safe)
            if (messageTemplate != null && !messageTemplate.isEmpty()) {
                String msg = messageTemplate
                        .replace("%material%", formatMaterialName(material))
                        .replace("%count%", String.valueOf(count))
                        .replace("%limit%", String.valueOf(limit));
                SchedulerAdapter.runAtEntity(plugin, player, () -> LessLag.sendActionBar(player, msg));
            }

            plugin.getLogger().fine("[BlockPlacementLimiter] Blocked " + material.name()
                    + " by " + player.getName()
                    + " in chunk (" + chunk.getX() + "," + chunk.getZ() + ")"
                    + " [" + count + "/" + limit + "]");
        } else {
            // Allowed — increment the cache
            incrementCache(chunk, material);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!enabled)
            return;
        Material material = event.getBlock().getType();
        if (!limits.containsKey(material))
            return;
        decrementCache(event.getBlock().getChunk(), material);
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        String key = chunkKey(event.getChunk());
        chunkCache.remove(key);
        scanningChunks.remove(key);
    }

    // ══════════════════════════════════════════════════
    // Cache read + lazy seeding
    // ══════════════════════════════════════════════════

    /**
     * Returns the cached block count for (chunk, material).
     *
     * <ul>
     * <li>If cached: returns the current count. Caller must increment on
     * allow.</li>
     * <li>If not cached (tile material): seeds synchronously via
     * {@code getTileEntities()} (O(T), fast), returns the seeded value.</li>
     * <li>If not cached (non-tile material): triggers async scan, seeds cache to 1
     * provisionally, returns -1 so the caller knows to allow and skip
     * increment.</li>
     * </ul>
     */
    private int getCount(Chunk chunk, Material material, Map<Material, Integer> currentLimits) {
        String key = chunkKey(chunk);
        ConcurrentHashMap<Material, AtomicInteger> matMap = chunkCache.get(key);

        if (matMap != null) {
            AtomicInteger counter = matMap.get(material);
            if (counter != null)
                return counter.get();
        }

        // Cache miss — seed it
        if (TILE_ENTITY_MATERIALS.contains(material)) {
            // Sync seed: getTileEntities() is fast (server already maintains this list)
            int existing = countTileEntities(chunk, material);
            ConcurrentHashMap<Material, AtomicInteger> m = chunkCache.computeIfAbsent(key,
                    k -> new ConcurrentHashMap<>());
            // putIfAbsent to avoid races between concurrent region threads
            AtomicInteger counter = new AtomicInteger(existing);
            AtomicInteger prev = m.putIfAbsent(material, counter);
            return prev != null ? prev.get() : existing;
        } else {
            // Async seed for non-tile blocks — trigger background scan
            triggerAsyncScan(chunk, currentLimits);
            // Seed to 1 provisionally (this placement is going to be allowed)
            ConcurrentHashMap<Material, AtomicInteger> m = chunkCache.computeIfAbsent(key,
                    k -> new ConcurrentHashMap<>());
            m.putIfAbsent(material, new AtomicInteger(1));
            return -1; // Signal to caller: allow, don't increment again
        }
    }

    private void incrementCache(Chunk chunk, Material material) {
        String key = chunkKey(chunk);
        ConcurrentHashMap<Material, AtomicInteger> matMap = chunkCache.get(key);
        if (matMap == null)
            return;
        AtomicInteger counter = matMap.get(material);
        if (counter != null)
            counter.incrementAndGet();
    }

    private void decrementCache(Chunk chunk, Material material) {
        String key = chunkKey(chunk);
        ConcurrentHashMap<Material, AtomicInteger> matMap = chunkCache.get(key);
        if (matMap == null)
            return;
        AtomicInteger counter = matMap.get(material);
        if (counter != null)
            counter.updateAndGet(v -> Math.max(0, v - 1));
    }

    // ══════════════════════════════════════════════════
    // Async scan (non-tile materials, initial seed only)
    // ══════════════════════════════════════════════════

    private void triggerAsyncScan(Chunk chunk, Map<Material, Integer> currentLimits) {
        String key = chunkKey(chunk);
        if (!scanningChunks.add(key))
            return; // already in-flight

        final int cx = chunk.getX();
        final int cz = chunk.getZ();
        final String worldName = chunk.getWorld().getName();
        final int minY = chunk.getWorld().getMinHeight();
        final int maxY = chunk.getWorld().getMaxHeight();

        // Only non-tile materials need async scanning
        Set<Material> toScan = java.util.EnumSet.noneOf(Material.class);
        for (Material m : currentLimits.keySet()) {
            if (!TILE_ENTITY_MATERIALS.contains(m))
                toScan.add(m);
        }
        if (toScan.isEmpty()) {
            scanningChunks.remove(key);
            return;
        }

        // ChunkSnapshot is immutable — safe to read from any thread
        final org.bukkit.ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);

        SchedulerAdapter.runAsync(plugin, () -> {
            try {
                Map<Material, Integer> counts = new EnumMap<>(Material.class);
                for (Material m : toScan)
                    counts.put(m, 0);

                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = minY; y < maxY; y++) {
                            try {
                                Material type = snapshot.getBlockType(x, y, z);
                                if (toScan.contains(type)) {
                                    counts.put(type, counts.get(type) + 1);
                                }
                            } catch (ArrayIndexOutOfBoundsException ignored) {
                            }
                        }
                    }
                }

                // Merge scan result into cache — keep whichever is higher
                // (provisional increments from the scan window are respected)
                ConcurrentHashMap<Material, AtomicInteger> matMap = chunkCache.computeIfAbsent(key,
                        k -> new ConcurrentHashMap<>());
                for (Map.Entry<Material, Integer> e : counts.entrySet()) {
                    int scanned = e.getValue();
                    matMap.compute(e.getKey(), (mat, existing) -> {
                        if (existing == null)
                            return new AtomicInteger(scanned);
                        existing.updateAndGet(v -> Math.max(v, scanned));
                        return existing;
                    });
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[BlockPlacementLimiter] Async scan failed for "
                        + worldName + " " + cx + "," + cz + ": " + e.getMessage());
            } finally {
                scanningChunks.remove(key);
            }
        });
    }

    // ══════════════════════════════════════════════════
    // Tile-entity sync seed (called once per chunk per material)
    // ══════════════════════════════════════════════════

    private int countTileEntities(Chunk chunk, Material material) {
        int count = 0;
        for (org.bukkit.block.BlockState state : chunk.getTileEntities()) {
            if (state.getType() == material)
                count++;
        }
        return count;
    }

    // ══════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════

    private static String chunkKey(Chunk chunk) {
        return chunk.getWorld().getName() + ':' + chunk.getX() + ':' + chunk.getZ();
    }

    /** HOPPER → "Hopper", STICKY_PISTON → "Sticky Piston" */
    private static String formatMaterialName(Material material) {
        String raw = material.name().replace('_', ' ');
        StringBuilder sb = new StringBuilder(raw.length());
        boolean cap = true;
        for (char c : raw.toCharArray()) {
            if (c == ' ') {
                sb.append(c);
                cap = true;
            } else if (cap) {
                sb.append(Character.toUpperCase(c));
                cap = false;
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    // ══════════════════════════════════════════════════
    // Getters
    // ══════════════════════════════════════════════════

    public boolean isEnabled() {
        return enabled;
    }

    public long getTotalBlocked() {
        return totalBlocked.get();
    }

    public Map<Material, Integer> getLimits() {
        return limits;
    }

    public Map<Material, AtomicLong> getBlockedPerMaterial() {
        return Collections.unmodifiableMap(blockedPerMaterial);
    }
}
