package com.lesslag.setup.model;

/** Game profile axis for preset selection. */
public enum GameProfile {
    SMP("Survival Multiplayer", "General survival gameplay with varied activities"),
    SKYBLOCK("Skyblock", "Island-based survival with heavy farming/automation"),
    MINIGAME("Minigame", "Fast player rotation, short-lived worlds"),
    CREATIVE("Creative", "Building-focused with large render requirements");

    private final String displayName;
    private final String description;

    GameProfile(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }

    /**
     * Parse from user input, case-insensitive.
     * @return null if unrecognised
     */
    public static GameProfile fromString(String input) {
        if (input == null) return null;
        try {
            return valueOf(input.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
