package com.lesslag.setup.model;

import java.time.Instant;
import java.util.*;

/**
 * Snapshot of the server environment captured during discovery.
 * Includes platform info, config files found, plugin inventory, and runtime metrics.
 */
public class EnvironmentSnapshot {

    private final Instant capturedAt = Instant.now();

    // ── Platform ────────────────────────────
    private String platformName;            // "Paper", "Purpur", "Pufferfish", "Leaf", "Spigot"
    private String platformVersion;         // Full version string
    private String minecraftVersion;        // e.g. "1.21.4"
    private boolean foliaDetected;

    // ── Config files found ────────────────────────────
    private final Map<String, Boolean> configFilesPresent = new LinkedHashMap<>();

    // ── Plugin inventory ────────────────────────────
    private final List<PluginInfo> plugins = new ArrayList<>();

    // ── Runtime metrics ────────────────────────────
    private int onlinePlayers;
    private int loadedChunks;
    private int totalEntities;
    private double currentTps;
    private double currentMspt;
    private int loadedWorldCount;

    // ── Getters / Setters ────────────────────────────

    public Instant getCapturedAt() { return capturedAt; }

    public String getPlatformName() { return platformName; }
    public void setPlatformName(String platformName) { this.platformName = platformName; }

    public String getPlatformVersion() { return platformVersion; }
    public void setPlatformVersion(String version) { this.platformVersion = version; }

    public String getMinecraftVersion() { return minecraftVersion; }
    public void setMinecraftVersion(String version) { this.minecraftVersion = version; }

    public boolean isFoliaDetected() { return foliaDetected; }
    public void setFoliaDetected(boolean folia) { this.foliaDetected = folia; }

    public Map<String, Boolean> getConfigFilesPresent() { return configFilesPresent; }

    public List<PluginInfo> getPlugins() { return plugins; }

    public int getOnlinePlayers() { return onlinePlayers; }
    public void setOnlinePlayers(int count) { this.onlinePlayers = count; }

    public int getLoadedChunks() { return loadedChunks; }
    public void setLoadedChunks(int count) { this.loadedChunks = count; }

    public int getTotalEntities() { return totalEntities; }
    public void setTotalEntities(int count) { this.totalEntities = count; }

    public double getCurrentTps() { return currentTps; }
    public void setCurrentTps(double tps) { this.currentTps = tps; }

    public double getCurrentMspt() { return currentMspt; }
    public void setCurrentMspt(double mspt) { this.currentMspt = mspt; }

    public int getLoadedWorldCount() { return loadedWorldCount; }
    public void setLoadedWorldCount(int count) { this.loadedWorldCount = count; }

    /** Simple plugin descriptor. */
    public static class PluginInfo {
        private final String name;
        private final String version;
        private final boolean enabled;
        private final List<String> authors;

        public PluginInfo(String name, String version, boolean enabled, List<String> authors) {
            this.name = name;
            this.version = version;
            this.enabled = enabled;
            this.authors = authors != null ? authors : Collections.emptyList();
        }

        public String getName() { return name; }
        public String getVersion() { return version; }
        public boolean isEnabled() { return enabled; }
        public List<String> getAuthors() { return authors; }
    }
}
