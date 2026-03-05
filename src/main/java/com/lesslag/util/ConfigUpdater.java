package com.lesslag.util;

import com.lesslag.LessLag;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Automatically migrates the on-disk config.yml to the latest version bundled
 * in the plugin JAR.
 *
 * <p>
 * Algorithm (non-destructive):
 * <ol>
 * <li>Load the default config embedded in the JAR.</li>
 * <li>Compare {@code config-version} of disk vs. JAR.</li>
 * <li>Deep-merge: add any missing keys from the default, never overwriting
 * values that the server admin has already customised.</li>
 * <li>Bump {@code config-version} and save.</li>
 * </ol>
 *
 * <p>
 * Call {@link #update(LessLag)} once in {@code onEnable()}, right after
 * {@code saveDefaultConfig()}.
 */
public final class ConfigUpdater {

    private ConfigUpdater() {
    }

    /**
     * Run the migration check. If the disk config is older than the JAR default,
     * new keys are merged in and the file is saved.
     *
     * @param plugin the LessLag plugin instance
     */
    public static void update(LessLag plugin) {
        // Load the bundled default config from inside the JAR
        InputStream defaultStream = plugin.getResource("config.yml");
        if (defaultStream == null) {
            plugin.getLogger().warning("[ConfigUpdater] Could not load bundled config.yml — skipping update check.");
            return;
        }

        YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                new InputStreamReader(defaultStream, StandardCharsets.UTF_8));

        int diskVersion = plugin.getConfig().getInt("config-version", 1);
        int bundledVersion = defaultConfig.getInt("config-version", 1);

        if (diskVersion >= bundledVersion) {
            plugin.getLogger().info("[ConfigUpdater] Config is up-to-date (version " + diskVersion + ").");
            return;
        }

        plugin.getLogger().info("[ConfigUpdater] Updating config from version " + diskVersion
                + " → " + bundledVersion + "...");

        // Deep-merge: add missing keys without touching existing ones
        List<String> added = deepMerge(plugin, defaultConfig);

        // Bump the version
        plugin.getConfig().set("config-version", bundledVersion);
        plugin.saveConfig();

        if (added.isEmpty()) {
            plugin.getLogger().info("[ConfigUpdater] No new keys to add — bumped version to " + bundledVersion + ".");
        } else {
            plugin.getLogger().info("[ConfigUpdater] Config updated to version " + bundledVersion
                    + ". Added " + added.size() + " new key(s):");
            for (String key : added) {
                plugin.getLogger().info("  + " + key);
            }
        }
    }

    /**
     * Recursively merge all keys from {@code defaults} into the live plugin config,
     * skipping any key that already exists on disk.
     *
     * @param plugin   plugin instance (for live config access)
     * @param defaults the bundled default YamlConfiguration
     * @return list of dot-notation keys that were newly added
     */
    private static List<String> deepMerge(LessLag plugin, YamlConfiguration defaults) {
        List<String> added = new ArrayList<>();
        mergeSection(plugin, defaults, "", added);
        return added;
    }

    /**
     * Recursive helper. {@code prefix} is the current dot-path (empty at root).
     */
    private static void mergeSection(LessLag plugin, YamlConfiguration defaults,
            String prefix, List<String> added) {
        Set<String> keys = prefix.isEmpty()
                ? defaults.getKeys(false)
                : (defaults.getConfigurationSection(prefix) != null
                        ? defaults.getConfigurationSection(prefix).getKeys(false)
                        : Set.of());

        for (String key : keys) {
            String fullKey = prefix.isEmpty() ? key : prefix + "." + key;

            // Skip the version key itself — we handle it separately
            if ("config-version".equals(fullKey))
                continue;

            boolean isSection = defaults.isConfigurationSection(fullKey);

            if (isSection) {
                // Recurse into sub-section
                mergeSection(plugin, defaults, fullKey, added);
            } else {
                // Leaf value — only add if absent from disk config
                if (!plugin.getConfig().contains(fullKey)) {
                    plugin.getConfig().set(fullKey, defaults.get(fullKey));
                    added.add(fullKey);
                }
            }
        }
    }
}
