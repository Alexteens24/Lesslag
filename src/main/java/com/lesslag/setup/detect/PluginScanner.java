package com.lesslag.setup.detect;

import com.lesslag.setup.model.EnvironmentSnapshot.PluginInfo;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.logging.Logger;

/**
 * Scans loaded plugins and identifies potential conflicts
 * with LessLag's lag-control features.
 */
public class PluginScanner {

    private static final Logger LOG = Logger.getLogger("LessLag-Setup");

    /** Plugins known to overlap with LessLag functionality. */
    private static final Map<String, String> CONFLICT_CATALOG = new LinkedHashMap<>();
    static {
        CONFLICT_CATALOG.put("ClearLag", "Entity/item clearing — overlaps with LessLag entity management");
        CONFLICT_CATALOG.put("LagAssist", "TPS optimization — overlaps with LessLag TPS monitoring and actions");
        CONFLICT_CATALOG.put("EntityTrackerFixer", "Entity tracking — overlaps with frustum culling");
        CONFLICT_CATALOG.put("Pufferfish", "DAB (activation range) — overlaps with AI culling");
        CONFLICT_CATALOG.put("FarmControl", "Breeding/farm limits — overlaps with breeding limiter and density optimizer");
        CONFLICT_CATALOG.put("MobFarmManager", "Mob farm management — overlaps with density optimizer");
        CONFLICT_CATALOG.put("StackMob", "Mob stacking — may interfere with entity counting");
        CONFLICT_CATALOG.put("WildStacker", "Mob/item stacking — may interfere with entity counting");
        CONFLICT_CATALOG.put("RoseStacker", "Mob/item stacking — may interfere with entity counting");
        CONFLICT_CATALOG.put("Insights", "Chunk scanning — may duplicate chunk limiter work");
        CONFLICT_CATALOG.put("ServerBooster", "Performance booster — may apply same optimizations");
        CONFLICT_CATALOG.put("spark", "Profiler — complementary, can provide data for tuning");
    }

    private final List<PluginInfo> discoveredPlugins = new ArrayList<>();
    private final Map<String, String> detectedConflicts = new LinkedHashMap<>();
    private boolean sparkPresent;

    public void scan() {
        discoveredPlugins.clear();
        detectedConflicts.clear();
        sparkPresent = false;

        try {
            Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
            for (Plugin p : plugins) {
                String name = p.getName();
                @SuppressWarnings("deprecation")
                String version = p.getDescription().getVersion();
                boolean enabled = p.isEnabled();
                @SuppressWarnings("deprecation")
                List<String> authors = p.getDescription().getAuthors();

                discoveredPlugins.add(new PluginInfo(name, version, enabled, authors));

                // Check conflicts
                for (Map.Entry<String, String> entry : CONFLICT_CATALOG.entrySet()) {
                    if (name.equalsIgnoreCase(entry.getKey())
                        || name.toLowerCase().contains(entry.getKey().toLowerCase())) {
                        detectedConflicts.put(name, entry.getValue());
                    }
                }

                if (name.equalsIgnoreCase("spark")) {
                    sparkPresent = true;
                }
            }
        } catch (Exception e) {
            LOG.warning("Plugin scan failed: " + e.getMessage());
        }

        LOG.info("Plugin scan complete. " + discoveredPlugins.size() + " plugins, "
            + detectedConflicts.size() + " potential conflicts.");
    }

    public List<PluginInfo> getDiscoveredPlugins() {
        return Collections.unmodifiableList(discoveredPlugins);
    }

    public Map<String, String> getDetectedConflicts() {
        return Collections.unmodifiableMap(detectedConflicts);
    }

    public boolean isSparkPresent() { return sparkPresent; }

    /** Compute a simple hash of the plugin list for audit trails. */
    public String computePluginListHash() {
        StringBuilder sb = new StringBuilder();
        for (PluginInfo p : discoveredPlugins) {
            sb.append(p.getName()).append(':').append(p.getVersion()).append(';');
        }
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString().substring(0, 16);
        } catch (Exception e) {
            return "unknown";
        }
    }
}
