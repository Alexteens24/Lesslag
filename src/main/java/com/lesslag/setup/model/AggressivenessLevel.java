package com.lesslag.setup.model;

/** Aggressiveness level for recommendations. */
public enum AggressivenessLevel {
    SAFE("Safe", "Minimal gameplay impact, conservative settings"),
    BALANCED("Balanced", "Good balance between performance and gameplay"),
    AGGRESSIVE("Aggressive", "Maximum performance with explicit tradeoffs");

    private final String displayName;
    private final String description;

    AggressivenessLevel(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }

    public static AggressivenessLevel fromString(String input) {
        if (input == null) return null;
        try {
            return valueOf(input.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
