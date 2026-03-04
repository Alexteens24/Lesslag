package com.lesslag.util;

import com.lesslag.LessLag;
import org.bukkit.Bukkit;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Auto-detects conflicting plugins and server forks at startup.
 * Logs warnings and auto-adjusts LessLag features to avoid overlap.
 *
 * Detected software:
 * - Pufferfish (DAB) — conflicts with AI optimization, frustum culling
 * - ClearLag / ClearLag++ — conflicts with item/mob clearing, redstone, view
 * distance
 * - MobFarmManager — conflicts with chunk-limiter, entity limits
 */
public class CompatibilityManager {

    private final LessLag plugin;

    // Detection results
    private boolean pufferfishDetected = false;
    private boolean dabEnabled = false;
    private boolean clearlagDetected = false;
    private boolean mobFarmManagerDetected = false;

    // What we auto-disabled
    private final List<String> autoDisabled = new ArrayList<>();

    public CompatibilityManager(LessLag plugin) {
        this.plugin = plugin;
    }

    /**
     * Run all detection checks and auto-disable conflicting features.
     * Call this in onEnable() BEFORE starting monitors.
     */
    public void detect() {
        boolean autoDetect = plugin.getConfig().getBoolean("compatibility.auto-detect", true);
        if (!autoDetect) {
            plugin.getLogger().info("[Compat] Compatibility checks disabled.");
            return;
        }

        // We assume auto-adjust is always desired if auto-detect is on,
        // as per new config structure which relies on booleans to disable check
        // entirely.
        // Or we can say "auto-detect" is just detection, and "plugins.*" booleans
        // control adjustment.
        // Actually, config comment says "Automatically detect and adjust".

        detectPufferfish(true);
        detectClearlag(true);
        detectMobFarmManager(true);
        detectCustomMobPlugins();

        // Print summary
        if (!autoDisabled.isEmpty()) {
            plugin.getLogger().info("╔══════════════════════════════════════════╗");
            plugin.getLogger().info("║  Compatibility: auto-adjusted features   ║");
            plugin.getLogger().info("╠══════════════════════════════════════════╣");
            for (String msg : autoDisabled) {
                plugin.getLogger().info("║  ✗ " + msg);
            }
            plugin.getLogger().info("╚══════════════════════════════════════════╝");
            plugin.getLogger().info("[Compat] Override in config.yml → compatibility section");
        }
    }

    // ══════════════════════════════════════════════════
    // Pufferfish / DAB Detection
    // ══════════════════════════════════════════════════

    private void detectPufferfish(boolean autoAdjust) {
        // Check config override first
        boolean check = plugin.getConfig().getBoolean("compatibility.plugins.pufferfish-dab", true);
        if (!check)
            return;

        // Detect Pufferfish by checking for server brand or class existence.
        // File existence alone is unreliable as users often have leftover config files.
        boolean isBrand = detectServerFork("Pufferfish");
        boolean hasClass = false;
        try {
            Class.forName("gg.pufferfish.pufferfish.Pufferfish");
            hasClass = true;
        } catch (ClassNotFoundException ignored) {
        }

        pufferfishDetected = isBrand || hasClass;

        if (!pufferfishDetected)
            return;

        // Check if DAB is actually enabled in pufferfish.yml
        dabEnabled = checkDABEnabled();

        plugin.getLogger().info("[Compat] Pufferfish detected! DAB is " + (dabEnabled ? "ENABLED" : "DISABLED"));

        if (dabEnabled && autoAdjust) {
            // Hybrid Mode: LessLag handles visuals, Pufferfish handles distance
            plugin.getLogger().info(
                    "[Compat] Pufferfish detected. Running in Hybrid Mode (LessLag handles Visuals, Pufferfish handles Distance).");
        }
    }

    /**
     * Try to read pufferfish.yml to check if DAB is enabled.
     */
    private boolean checkDABEnabled() {
        try {
            File pufferfishFile = new File("pufferfish.yml");
            if (!pufferfishFile.exists())
                return false;

            org.bukkit.configuration.file.YamlConfiguration pufferConfig = org.bukkit.configuration.file.YamlConfiguration
                    .loadConfiguration(pufferfishFile);
            return pufferConfig.getBoolean("dab.enabled", true);
        } catch (Exception e) {
            plugin.getLogger().fine("[Compat] Could not read pufferfish.yml: " + e.getMessage());
            return true; // Assume enabled if we can't read
        }
    }

    // ══════════════════════════════════════════════════
    // ClearLag / ClearLag++ Detection
    // ══════════════════════════════════════════════════

    private void detectClearlag(boolean autoAdjust) {
        boolean check = plugin.getConfig().getBoolean("compatibility.plugins.clearlag", true);
        if (!check)
            return;

        clearlagDetected = Bukkit.getPluginManager().getPlugin("ClearLag") != null
                || Bukkit.getPluginManager().getPlugin("ClearLagg") != null
                || Bukkit.getPluginManager().getPlugin("ClearLag++") != null
                || Bukkit.getPluginManager().getPlugin("Lagg") != null;

        if (!clearlagDetected)
            return;

        plugin.getLogger().info("[Compat] ClearLag/ClearLag++ detected!");

        if (autoAdjust) {
            // ClearLag handles redstone culling, but users might prefer LessLag's
            // implementation.
            // We'll warn about potential conflict instead of forcefully disabling it.
            if (plugin.getConfig().getBoolean("modules.redstone.enabled", true)) {
                plugin.getLogger().warning("[Compat] ClearLag detected! Both plugins have Redstone Limiting enabled.");
                plugin.getLogger().warning("[Compat] Consider disabling one to avoid conflicts.");
            }

            // Remove clear-ground-items and clear-xp-orbs from threshold actions
            // (ClearLag already clears them on interval, doubling up wastes effort)
            // We log a warning instead of directly modifying threshold actions
            autoDisabled.add("WARNING: clear-ground-items/clear-xp-orbs may duplicate ClearLag's clearing");
        }
    }

    // ══════════════════════════════════════════════════
    // MobFarmManager Detection
    // ══════════════════════════════════════════════════

    private void detectMobFarmManager(boolean autoAdjust) {
        boolean check = plugin.getConfig().getBoolean("compatibility.plugins.mobfarmmanager", true);
        if (!check)
            return;

        mobFarmManagerDetected = Bukkit.getPluginManager().getPlugin("MobFarmManager") != null;

        if (!mobFarmManagerDetected)
            return;

        plugin.getLogger().info("[Compat] MobFarmManager detected!");

        if (autoAdjust) {
            // MFM manages farm entities — raise our per-chunk limit to avoid conflict
            int currentLimit = plugin.getConfig().getInt("modules.entities.chunk-limiter.max-entities-per-chunk", 50);
            if (currentLimit < 75) {
                plugin.getConfig().set("modules.entities.chunk-limiter.max-entities-per-chunk", 75);
                autoDisabled.add("Chunk Limiter threshold raised to 75 (MobFarmManager manages farms)");
            }

            // Extend scan interval to let MFM do its work first
            int currentInterval = plugin.getConfig().getInt("modules.entities.chunk-limiter.scan-interval", 30);
            if (currentInterval < 60) {
                plugin.getConfig().set("modules.entities.chunk-limiter.scan-interval", 60);
                autoDisabled.add("Chunk Limiter interval extended to 60s (defers to MobFarmManager)");
            }
        }
    }

    // ══════════════════════════════════════════════════
    // Custom Mob Plugins Detection
    // ══════════════════════════════════════════════════

    private boolean mythicMobsDetected = false;
    private boolean modelEngineDetected = false;
    private boolean citizensDetected = false;
    private boolean cmiDetected = false;
    private boolean hologramsDetected = false;
    private boolean customItemsDetected = false;
    private boolean evenMoreFishDetected = false;
    private boolean fancyNpcsDetected = false;
    private boolean fancyHologramsDetected = false;
    private boolean decentHologramsDetected = false;

    public void detectCustomMobPlugins() {
        if (Bukkit.getPluginManager().getPlugin("MythicMobs") != null) {
            mythicMobsDetected = true;
            plugin.getLogger().info("[Compat] MythicMobs detected!");
        }

        if (Bukkit.getPluginManager().getPlugin("ModelEngine") != null) {
            modelEngineDetected = true;
            plugin.getLogger().info("[Compat] ModelEngine detected!");
        }

        if (Bukkit.getPluginManager().getPlugin("Citizens") != null) {
            citizensDetected = true;
            plugin.getLogger().info("[Compat] Citizens detected!");
        }

        if (plugin.getConfig().getBoolean("compatibility.plugins.cmi", true)
                && Bukkit.getPluginManager().getPlugin("CMI") != null) {
            cmiDetected = true;
            plugin.getLogger().info("[Compat] CMI detected!");
        }

        if (plugin.getConfig().getBoolean("compatibility.plugins.holograms", true)
                && (Bukkit.getPluginManager().getPlugin("HolographicDisplays") != null
                        || Bukkit.getPluginManager().getPlugin("DecentHolograms") != null)) {
            hologramsDetected = true;
            plugin.getLogger().info("[Compat] Hologram plugin detected!");
        }

        if (plugin.getConfig().getBoolean("compatibility.plugins.custom-items", true)
                && (Bukkit.getPluginManager().getPlugin("ItemsAdder") != null
                        || Bukkit.getPluginManager().getPlugin("Oraxen") != null)) {
            customItemsDetected = true;
            plugin.getLogger().info("[Compat] Custom Items plugin detected (ItemsAdder/Oraxen)!");
        }

        if (plugin.getConfig().getBoolean("compatibility.plugins.evenmorefish", true)
                && Bukkit.getPluginManager().getPlugin("EvenMoreFish") != null) {
            evenMoreFishDetected = true;
            plugin.getLogger().info("[Compat] EvenMoreFish detected!");
        }

        if (plugin.getConfig().getBoolean("compatibility.plugins.fancynpcs", true)
                && Bukkit.getPluginManager().getPlugin("FancyNpcs") != null) {
            fancyNpcsDetected = true;
            plugin.getLogger().info("[Compat] FancyNpcs detected!");
        }

        if (plugin.getConfig().getBoolean("compatibility.plugins.fancyholograms", true)
                && Bukkit.getPluginManager().getPlugin("FancyHolograms") != null) {
            fancyHologramsDetected = true;
            plugin.getLogger().info("[Compat] FancyHolograms detected!");
        }

        if (plugin.getConfig().getBoolean("compatibility.plugins.decentholograms", true)
                && Bukkit.getPluginManager().getPlugin("DecentHolograms") != null) {
            decentHologramsDetected = true;
            plugin.getLogger().info("[Compat] DecentHolograms detected!");
        }
    }

    /**
     * Check if an entity is a custom mob from MythicMobs or ModelEngine.
     */
    public boolean isCustomMob(org.bukkit.entity.Entity entity) {
        if (entity == null)
            return false;

        if (mythicMobsDetected) {
            if (io.lumine.mythic.bukkit.MythicBukkit.inst().getMobManager().isMythicMob(entity)) {
                return true;
            }
        }

        if (modelEngineDetected) {
            try {
                // Reflection to avoid hard dependency on unavailable artifact
                Class<?> apiClass = Class.forName("com.ticxo.modelengine.api.ModelEngineAPI");
                java.lang.reflect.Method getModeledEntity = apiClass.getMethod("getModeledEntity",
                        java.util.UUID.class);
                Object modeledEntity = getModeledEntity.invoke(null, entity.getUniqueId());
                if (modeledEntity != null) {
                    return true;
                }
            } catch (Exception e) {
                // Silently fail if API changed or unavailable
            }
        }

        return false;
    }

    /**
     * Check if an entity is an NPC from Citizens.
     */
    public boolean isNPC(org.bukkit.entity.Entity entity) {
        if (!citizensDetected || entity == null)
            return false;
        return net.citizensnpcs.api.CitizensAPI.getNPCRegistry().isNPC(entity);
    }

    /**
     * Check if an entity is an NPC from FancyNpcs.
     */
    public boolean isFancyNpc(org.bukkit.entity.Entity entity) {
        if (!fancyNpcsDetected || entity == null)
            return false;

        try {
            // Support both com.fancyinnovations and de.oliver packages
            Class<?> pluginClass = null;
            try {
                pluginClass = Class.forName("com.fancyinnovations.fancynpcs.api.FancyNpcsPlugin");
            } catch (ClassNotFoundException e) {
                try {
                    pluginClass = Class.forName("de.oliver.fancynpcs.api.FancyNpcsPlugin");
                } catch (ClassNotFoundException ex) {
                    return false;
                }
            }

            Object pluginObj = pluginClass.getMethod("get").invoke(null);
            Object npcManager = pluginClass.getMethod("getNpcManager").invoke(pluginObj);

            Object npc = npcManager.getClass().getMethod("getNpc", int.class).invoke(npcManager, entity.getEntityId());
            if (npc != null) {
                return true;
            }
        } catch (Exception e) {
            // Silently fail if API changed or unavailable
        }

        return false;
    }

    /**
     * Check if an entity is part of a FancyHologram.
     */
    public boolean isFancyHologram(org.bukkit.entity.Entity entity) {
        if (!fancyHologramsDetected || entity == null)
            return false;

        try {
            Class<?> pluginClass = Class.forName("de.oliver.fancyholograms.api.FancyHologramsPlugin");
            Object pluginObj = pluginClass.getMethod("get").invoke(null);
            Object holoManager = pluginClass.getMethod("getHologramsManager").invoke(pluginObj);

            // getHolograms returns Collection<Hologram>
            java.util.Collection<?> holograms = (java.util.Collection<?>) holoManager.getClass()
                    .getMethod("getHolograms").invoke(holoManager);
            for (Object holo : holograms) {
                int id = (int) holo.getClass().getMethod("getEntityId").invoke(holo);
                if (id == entity.getEntityId()) {
                    return true;
                }
            }
        } catch (Exception e) {
            // Silently fail if API changed or unavailable
        }

        return false;
    }

    /**
     * Check if an entity is part of a DecentHologram.
     */
    public boolean isDecentHologram(org.bukkit.entity.Entity entity) {
        if (!decentHologramsDetected || entity == null)
            return false;

        try {
            // Because DecentHolograms does not expose a direct get-by-entity method,
            // and DHAPI.getHologram takes a name string, it's difficult to verify cleanly.
            // Normally DH only spawns packet-based entities that Bukkit won't see anyway.
            // But if it leaks anArmorStand/Display, we assume the name check heuristic in
            // isProtectedEntity works.
            // Just returning false here and heavily relying on the backup names checks.
            return false;
        } catch (Exception e) {
            // Silently fail if API changed or unavailable
        }

        return false;
    }

    /**
     * Unified method to check if an entity belongs to ANY supported plugin and
     * should NOT be
     * touched by LessLag (e.g. not cleared, AI not disabled, not culled).
     */
    public boolean isProtectedEntity(org.bukkit.entity.Entity entity) {
        if (entity == null)
            return false;

        // 1. Check legacy methods
        if (isNPC(entity) || isCustomMob(entity) || isFancyNpc(entity) || isFancyHologram(entity)
                || isDecentHologram(entity)) {
            return true;
        }

        // 2. Metadata / NBT checks for specific plugins
        if (cmiDetected && entity.hasMetadata("CMI-ArmorStand")) {
            return true;
        }

        // ItemsAdder & Oraxen often use armor stands, item frames, or display entities
        // as furniture
        // Checking custom name is a broad heuristic, often used by Holograms and Custom
        // Items
        if (hologramsDetected || customItemsDetected || cmiDetected) {
            if (entity instanceof org.bukkit.entity.Display || entity instanceof org.bukkit.entity.ArmorStand) {
                @SuppressWarnings("deprecation")
                String customName = entity.getCustomName();
                if (entity.isCustomNameVisible() || customName != null) {
                    return true;
                }
            }
        }

        if (evenMoreFishDetected && entity.hasMetadata("emf-fish")) {
            return true;
        }

        // General EliteMobs / other custom mob flag
        if (entity.hasMetadata("EliteMob")) {
            return true;
        }

        return false;
    }

    // ══════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════

    /**
     * Detect server fork by checking brand string or version string.
     */
    private boolean detectServerFork(String forkName) {
        try {
            String version = Bukkit.getVersion().toLowerCase();
            String name = Bukkit.getName().toLowerCase();
            String forkLower = forkName.toLowerCase();
            return version.contains(forkLower) || name.contains(forkLower);
        } catch (Exception e) {
            return false;
        }
    }

    // ── Getters ──────────────────────────────────────

    public boolean isPufferfishDetected() {
        return pufferfishDetected;
    }

    public boolean isDABEnabled() {
        return dabEnabled;
    }

    public boolean isClearlagDetected() {
        return clearlagDetected;
    }

    public boolean isMobFarmManagerDetected() {
        return mobFarmManagerDetected;
    }

    public boolean isMythicMobsDetected() {
        return mythicMobsDetected;
    }

    public boolean isModelEngineDetected() {
        return modelEngineDetected;
    }

    public boolean isCitizensDetected() {
        return citizensDetected;
    }

    public List<String> getAutoDisabledFeatures() {
        return autoDisabled;
    }
}
