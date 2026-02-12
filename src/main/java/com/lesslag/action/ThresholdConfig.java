package com.lesslag.action;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.*;
import java.util.logging.Logger;

/**
 * Represents a single configurable TPS threshold with its associated actions,
 * notification settings, and console commands.
 */
public class ThresholdConfig {

    private final String name;
    private final double tps;
    private final boolean enabled;
    private final int priority;
    private final String message;
    private final boolean broadcast;
    private final String broadcastMessage;
    private final List<String> actions;
    private final List<String> commands;

    // Per-threshold notification settings
    private final boolean notifyChat;
    private final boolean notifyActionbar;
    private final boolean notifySound;
    private final String soundType;
    private final float soundVolume;
    private final float soundPitch;

    public ThresholdConfig(String name, double tps, boolean enabled, int priority,
            String message, boolean broadcast, String broadcastMessage,
            List<String> actions, List<String> commands,
            boolean notifyChat, boolean notifyActionbar, boolean notifySound,
            String soundType, float soundVolume, float soundPitch) {
        this.name = name;
        this.tps = tps;
        this.enabled = enabled;
        this.priority = priority;
        this.message = message;
        this.broadcast = broadcast;
        this.broadcastMessage = broadcastMessage;
        this.actions = Collections.unmodifiableList(actions);
        this.commands = Collections.unmodifiableList(commands);
        this.notifyChat = notifyChat;
        this.notifyActionbar = notifyActionbar;
        this.notifySound = notifySound;
        this.soundType = soundType;
        this.soundVolume = soundVolume;
        this.soundPitch = soundPitch;
    }

    /**
     * Load all thresholds from config, validate actions, return sorted list.
     * Sorted by TPS descending (highest threshold first → match lowest/most
     * severe).
     */
    public static List<ThresholdConfig> loadFromConfig(FileConfiguration config, Logger logger) {
        List<ThresholdConfig> thresholds = new ArrayList<>();
        ConfigurationSection section = config.getConfigurationSection("thresholds");

        if (section == null) {
            logger.warning("No 'thresholds' section found in config.yml!");
            return thresholds;
        }

        // Global notification defaults
        boolean globalChat = config.getBoolean("notifications.chat", true);
        boolean globalActionbar = config.getBoolean("notifications.actionbar", true);
        boolean globalSound = config.getBoolean("notifications.sound", true);

        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null)
                continue;

            boolean enabled = entry.getBoolean("enabled", true);
            double tps = entry.getDouble("tps", 16.0);
            int priority = entry.getInt("priority", 0);
            String message = entry.getString("message", "&e⚠ TPS: {tps}");
            boolean broadcast = entry.getBoolean("broadcast", false);
            String broadcastMessage = entry.getString("broadcast-message", null);
            List<String> actions = entry.getStringList("actions");
            List<String> commands = entry.getStringList("commands");

            // Per-threshold notification (fallback to global defaults)
            ConfigurationSection notify = entry.getConfigurationSection("notify");
            boolean chat = notify != null ? notify.getBoolean("chat", globalChat) : globalChat;
            boolean actionbar = notify != null ? notify.getBoolean("actionbar", globalActionbar) : globalActionbar;
            boolean sound = notify != null ? notify.getBoolean("sound", globalSound) : globalSound;
            String soundType = notify != null ? notify.getString("sound-type", "BLOCK_NOTE_BLOCK_PLING")
                    : "BLOCK_NOTE_BLOCK_PLING";
            float soundVolume = notify != null ? (float) notify.getDouble("sound-volume", 1.0) : 1.0f;
            float soundPitch = notify != null ? (float) notify.getDouble("sound-pitch", 1.0) : 1.0f;

            // Validate actions
            List<String> validActions = new ArrayList<>();
            for (String action : actions) {
                if (ActionExecutor.AVAILABLE_ACTIONS.contains(action.toLowerCase())) {
                    validActions.add(action.toLowerCase());
                } else {
                    logger.warning("[Threshold: " + key + "] Unknown action '" + action
                            + "' — skipping. Available: " + ActionExecutor.AVAILABLE_ACTIONS);
                }
            }

            if (!enabled) {
                logger.info("[Threshold: " + key + "] Disabled — skipping.");
                continue;
            }

            thresholds.add(new ThresholdConfig(key, tps, enabled, priority, message,
                    broadcast, broadcastMessage, validActions, commands,
                    chat, actionbar, sound, soundType, soundVolume, soundPitch));
        }

        // Sort: by TPS descending, then by priority descending
        thresholds.sort((a, b) -> {
            int tpsCmp = Double.compare(b.tps, a.tps);
            return tpsCmp != 0 ? tpsCmp : Integer.compare(b.priority, a.priority);
        });

        return thresholds;
    }

    // ── Getters ──────────────────────────────────────────

    public String getName() {
        return name;
    }

    public double getTps() {
        return tps;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getPriority() {
        return priority;
    }

    public String getMessage() {
        return message;
    }

    public boolean isBroadcast() {
        return broadcast;
    }

    public String getBroadcastMessage() {
        return broadcastMessage;
    }

    public List<String> getActions() {
        return actions;
    }

    public List<String> getCommands() {
        return commands;
    }

    public boolean isNotifyChat() {
        return notifyChat;
    }

    public boolean isNotifyActionbar() {
        return notifyActionbar;
    }

    public boolean isNotifySound() {
        return notifySound;
    }

    public String getSoundType() {
        return soundType;
    }

    public float getSoundVolume() {
        return soundVolume;
    }

    public float getSoundPitch() {
        return soundPitch;
    }

    /**
     * Get severity index (higher = more severe, i.e. lower TPS).
     */
    public int getSeverity(List<ThresholdConfig> allThresholds) {
        int index = allThresholds.indexOf(this);
        return index < 0 ? 0 : index;
    }

    /**
     * Get color code based on severity position.
     */
    public String getColor(List<ThresholdConfig> allThresholds) {
        int severity = getSeverity(allThresholds);
        int maxSeverity = allThresholds.size() - 1;

        if (maxSeverity <= 0)
            return "&e";

        double ratio = (double) severity / maxSeverity;
        if (ratio >= 0.75)
            return "&4&l"; // Most severe
        if (ratio >= 0.5)
            return "&c"; // High
        if (ratio >= 0.25)
            return "&6"; // Medium
        return "&e"; // Low
    }

    @Override
    public String toString() {
        return "Threshold{" + name + ", tps=" + tps + ", actions=" + actions.size()
                + ", commands=" + commands.size() + "}";
    }
}
