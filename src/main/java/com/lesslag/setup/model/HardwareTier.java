package com.lesslag.setup.model;

/** Hardware tier scoring for preset matrix. */
public enum HardwareTier {
    LOW("Low-End", "Limited CPU/RAM, shared hosting or budget VPS"),
    MID("Mid-Range", "Dedicated or higher-tier VPS, 4+ threads"),
    HIGH("High-End", "Dedicated hardware, 8+ threads, 16GB+ RAM");

    private final String displayName;
    private final String description;

    HardwareTier(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }

    public static HardwareTier fromString(String input) {
        if (input == null) return null;
        try {
            return valueOf(input.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
