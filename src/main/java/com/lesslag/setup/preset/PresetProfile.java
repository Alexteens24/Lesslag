package com.lesslag.setup.preset;

import com.lesslag.setup.model.AggressivenessLevel;
import com.lesslag.setup.model.GameProfile;
import com.lesslag.setup.model.HardwareTier;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A preset profile of recommended LessLag settings.
 * Generated from the PresetMatrix based on profile × tier × aggressiveness.
 */
public class PresetProfile {

    private final GameProfile gameProfile;
    private final HardwareTier hardwareTier;
    private final AggressivenessLevel aggressiveness;

    /** Config key → recommended value. All values are strings for YML serializability. */
    private final Map<String, String> settings;

    /** Human-readable notes about this preset. */
    private final String description;

    public PresetProfile(GameProfile profile, HardwareTier tier, AggressivenessLevel level,
                          Map<String, String> settings, String description) {
        this.gameProfile = profile;
        this.hardwareTier = tier;
        this.aggressiveness = level;
        this.settings = Collections.unmodifiableMap(new LinkedHashMap<>(settings));
        this.description = description;
    }

    public GameProfile getGameProfile() { return gameProfile; }
    public HardwareTier getHardwareTier() { return hardwareTier; }
    public AggressivenessLevel getAggressiveness() { return aggressiveness; }
    public Map<String, String> getSettings() { return settings; }
    public String getDescription() { return description; }

    /** Short label like "SMP / Mid / Balanced". */
    public String getLabel() {
        return gameProfile.getDisplayName() + " / " + hardwareTier.getDisplayName()
            + " / " + aggressiveness.getDisplayName();
    }
}
