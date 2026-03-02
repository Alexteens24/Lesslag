package com.lesslag.setup.detect;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;
import java.util.logging.Logger;

/**
 * Reads and caches server configuration files for rule evaluation.
 * Supports: server.properties, bukkit.yml, spigot.yml,
 * config/paper-global.yml, config/paper-world-defaults.yml,
 * purpur.yml, pufferfish.yml, leaves.yml, and per-world overrides.
 */
public class ConfigAdapter {

    private static final Logger LOG = Logger.getLogger("LessLag-Setup");

    /** Well-known config file paths relative to server root. */
    private static final String[] KNOWN_CONFIGS = {
        "server.properties",
        "bukkit.yml",
        "spigot.yml",
        "config/paper-global.yml",
        "config/paper-world-defaults.yml",
        "paper.yml",                   // legacy pre-1.19
        "purpur.yml",
        "pufferfish.yml",
        "leaves.yml"
    };

    private final File serverRoot;
    private final Map<String, YamlConfiguration> loadedConfigs = new LinkedHashMap<>();
    private final Map<String, Boolean> filePresence = new LinkedHashMap<>();
    private Properties serverProperties;

    public ConfigAdapter() {
        this(new File("."));
    }

    public ConfigAdapter(File serverRoot) {
        this.serverRoot = serverRoot;
    }

    /** Scan and load all known config files. Thread-safe to call from async. */
    public void scan() {
        loadedConfigs.clear();
        filePresence.clear();
        serverProperties = null;

        for (String path : KNOWN_CONFIGS) {
            File file = new File(serverRoot, path);
            boolean exists = file.exists() && file.isFile();
            filePresence.put(path, exists);

            if (!exists) continue;

            if (path.equals("server.properties")) {
                try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                    serverProperties = new Properties();
                    serverProperties.load(fis);
                } catch (Exception e) {
                    LOG.warning("Failed to read server.properties: " + e.getMessage());
                    serverProperties = null;
                }
            } else {
                try {
                    loadedConfigs.put(path, YamlConfiguration.loadConfiguration(file));
                } catch (Exception e) {
                    LOG.warning("Failed to parse " + path + ": " + e.getMessage());
                }
            }
        }

        // Scan per-world paper overrides: config/paper-world/<world>.yml
        File paperWorldDir = new File(serverRoot, "config");
        if (paperWorldDir.isDirectory()) {
            File[] worldFiles = paperWorldDir.listFiles((dir, name) ->
                name.startsWith("paper-world-") && name.endsWith(".yml")
                    && !name.equals("paper-world-defaults.yml"));
            if (worldFiles != null) {
                for (File wf : worldFiles) {
                    String rel = "config/" + wf.getName();
                    filePresence.put(rel, true);
                    try {
                        loadedConfigs.put(rel, YamlConfiguration.loadConfiguration(wf));
                    } catch (Exception e) {
                        LOG.warning("Failed to parse " + rel + ": " + e.getMessage());
                    }
                }
            }
        }

        LOG.info("Config scan complete. Found " + filePresence.values().stream()
            .filter(Boolean::booleanValue).count() + " config files.");
    }

    // ── Accessors ────────────────────────────

    public Map<String, Boolean> getFilePresence() {
        return Collections.unmodifiableMap(filePresence);
    }

    /** Get a YAML config by relative path (e.g. "spigot.yml"). */
    public YamlConfiguration getYaml(String relativePath) {
        return loadedConfigs.get(relativePath);
    }

    /** Read a specific key from a named config file. Returns null if missing. */
    public Object getValue(String configFile, String key) {
        if (configFile.equals("server.properties")) {
            return serverProperties != null ? serverProperties.getProperty(key) : null;
        }
        YamlConfiguration yaml = loadedConfigs.get(configFile);
        return yaml != null ? yaml.get(key) : null;
    }

    /** Read a string value with default. */
    public String getString(String configFile, String key, String def) {
        Object val = getValue(configFile, key);
        return val != null ? val.toString() : def;
    }

    /** Read an int value with default. */
    public int getInt(String configFile, String key, int def) {
        Object val = getValue(configFile, key);
        if (val instanceof Number) return ((Number) val).intValue();
        if (val != null) {
            try { return Integer.parseInt(val.toString().trim()); }
            catch (NumberFormatException ignored) {}
        }
        return def;
    }

    /** Read a boolean value with default. */
    public boolean getBoolean(String configFile, String key, boolean def) {
        Object val = getValue(configFile, key);
        if (val instanceof Boolean) return (Boolean) val;
        if (val != null) return Boolean.parseBoolean(val.toString().trim());
        return def;
    }

    /** Returns true if the config file was found. */
    public boolean isPresent(String configFile) {
        return filePresence.getOrDefault(configFile, false);
    }

    public Properties getServerProperties() { return serverProperties; }
}
