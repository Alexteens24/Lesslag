package com.lesslag.setup;

import com.lesslag.setup.model.AggressivenessLevel;
import com.lesslag.setup.model.GameProfile;
import com.lesslag.setup.model.HardwareTier;
import com.lesslag.setup.preset.PresetMatrix;
import com.lesslag.setup.preset.PresetProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PresetMatrix generation, load modifiers, and axis coverage.
 */
public class PresetMatrixTest {

    // ── Generate covers all axis combinations ─────────────

    @Test
    public void testGenerateReturnsNonNullForAllCombinations() {
        for (GameProfile profile : GameProfile.values()) {
            for (HardwareTier tier : HardwareTier.values()) {
                for (AggressivenessLevel level : AggressivenessLevel.values()) {
                    PresetProfile preset = PresetMatrix.generate(profile, tier, level);
                    assertNotNull(preset, "Preset should never be null for "
                            + profile + "/" + tier + "/" + level);
                    assertFalse(preset.getSettings().isEmpty(),
                            "Settings should not be empty for " + profile + "/" + tier + "/" + level);
                    assertNotNull(preset.getDescription());
                    assertNotNull(preset.getLabel());
                }
            }
        }
    }

    // ── Tier ordering: LOW < MID < HIGH ───────────────────

    @Test
    public void testLowTierHasStricterEntityLimitsThanHigh() {
        PresetProfile low = PresetMatrix.generate(GameProfile.SMP, HardwareTier.LOW, AggressivenessLevel.BALANCED);
        PresetProfile high = PresetMatrix.generate(GameProfile.SMP, HardwareTier.HIGH, AggressivenessLevel.BALANCED);

        int lowEntities = intSetting(low, "modules.entities.chunk-limiter.max-entities-per-chunk");
        int highEntities = intSetting(high, "modules.entities.chunk-limiter.max-entities-per-chunk");
        assertTrue(lowEntities < highEntities,
                "LOW tier entity limit (" + lowEntities + ") should be < HIGH tier (" + highEntities + ")");
    }

    @Test
    public void testLowTierHasLowerRedstoneLimit() {
        PresetProfile low = PresetMatrix.generate(GameProfile.SMP, HardwareTier.LOW, AggressivenessLevel.BALANCED);
        PresetProfile high = PresetMatrix.generate(GameProfile.SMP, HardwareTier.HIGH, AggressivenessLevel.BALANCED);

        int lowRedstone = intSetting(low, "modules.redstone.max-activations-per-chunk");
        int highRedstone = intSetting(high, "modules.redstone.max-activations-per-chunk");
        assertTrue(lowRedstone < highRedstone,
                "LOW redstone (" + lowRedstone + ") should be < HIGH (" + highRedstone + ")");
    }

    @Test
    public void testLowTierHasHigherTpsThresholds() {
        // LOW tier should trigger protections earlier (higher TPS threshold)
        PresetProfile low = PresetMatrix.generate(GameProfile.SMP, HardwareTier.LOW, AggressivenessLevel.BALANCED);
        PresetProfile high = PresetMatrix.generate(GameProfile.SMP, HardwareTier.HIGH, AggressivenessLevel.BALANCED);

        double lowMinor = doubleSetting(low, "automation.thresholds.minor.tps");
        double highMinor = doubleSetting(high, "automation.thresholds.minor.tps");
        assertTrue(lowMinor > highMinor,
                "LOW minor TPS threshold (" + lowMinor + ") should be > HIGH (" + highMinor + ")");
    }

    // ── Aggressiveness: AGGRESSIVE should be tighter ──────

    @Test
    public void testAggressiveHasTighterLimitsThanSafe() {
        PresetProfile safe = PresetMatrix.generate(GameProfile.SMP, HardwareTier.MID, AggressivenessLevel.SAFE);
        PresetProfile aggressive = PresetMatrix.generate(GameProfile.SMP, HardwareTier.MID, AggressivenessLevel.AGGRESSIVE);

        int safeEntities = intSetting(safe, "modules.entities.chunk-limiter.max-entities-per-chunk");
        int aggrEntities = intSetting(aggressive, "modules.entities.chunk-limiter.max-entities-per-chunk");
        assertTrue(aggrEntities < safeEntities,
                "AGGRESSIVE entity limit (" + aggrEntities + ") should be < SAFE (" + safeEntities + ")");

        int safeRedstone = intSetting(safe, "modules.redstone.max-activations-per-chunk");
        int aggrRedstone = intSetting(aggressive, "modules.redstone.max-activations-per-chunk");
        assertTrue(aggrRedstone < safeRedstone,
                "AGGRESSIVE redstone (" + aggrRedstone + ") should be < SAFE (" + safeRedstone + ")");
    }

    // ── Profile-specific adjustments ─────────────────────

    @Test
    public void testSkyblockReducesEntityLimits() {
        PresetProfile smp = PresetMatrix.generate(GameProfile.SMP, HardwareTier.MID, AggressivenessLevel.BALANCED);
        PresetProfile skyblock = PresetMatrix.generate(GameProfile.SKYBLOCK, HardwareTier.MID, AggressivenessLevel.BALANCED);

        int smpChunk = intSetting(smp, "modules.entities.chunk-limiter.max-entities-per-chunk");
        int skyChunk = intSetting(skyblock, "modules.entities.chunk-limiter.max-entities-per-chunk");
        assertTrue(skyChunk <= smpChunk,
                "SKYBLOCK chunk limit (" + skyChunk + ") should be <= SMP (" + smpChunk + ")");
    }

    @Test
    public void testCreativeBoostsRedstoneLimits() {
        PresetProfile smp = PresetMatrix.generate(GameProfile.SMP, HardwareTier.MID, AggressivenessLevel.BALANCED);
        PresetProfile creative = PresetMatrix.generate(GameProfile.CREATIVE, HardwareTier.MID, AggressivenessLevel.BALANCED);

        int smpRedstone = intSetting(smp, "modules.redstone.max-activations-per-chunk");
        int creativeRedstone = intSetting(creative, "modules.redstone.max-activations-per-chunk");
        assertTrue(creativeRedstone > smpRedstone,
                "CREATIVE redstone (" + creativeRedstone + ") should be > SMP (" + smpRedstone + ")");
    }

    // ── Load modifier ─────────────────────────────────────

    @Test
    public void testLoadModifierDowngradesHighWith80Players() {
        HardwareTier result = PresetMatrix.applyLoadModifier(HardwareTier.HIGH, 80);
        assertEquals(HardwareTier.MID, result,
                "80+ players on HIGH should downgrade to MID");
    }

    @Test
    public void testLoadModifierDowngradesMidWith50Players() {
        HardwareTier result = PresetMatrix.applyLoadModifier(HardwareTier.MID, 50);
        assertEquals(HardwareTier.LOW, result,
                "50+ players on MID should downgrade to LOW");
    }

    @Test
    public void testLoadModifierNoChangeForLowPlayers() {
        assertEquals(HardwareTier.HIGH, PresetMatrix.applyLoadModifier(HardwareTier.HIGH, 30));
        assertEquals(HardwareTier.MID, PresetMatrix.applyLoadModifier(HardwareTier.MID, 20));
        assertEquals(HardwareTier.LOW, PresetMatrix.applyLoadModifier(HardwareTier.LOW, 100));
    }

    // ── Label format ──────────────────────────────────────

    @Test
    public void testPresetLabelContainsAllAxes() {
        PresetProfile preset = PresetMatrix.generate(GameProfile.SMP, HardwareTier.MID, AggressivenessLevel.BALANCED);
        String label = preset.getLabel();
        assertTrue(label.contains("Survival Multiplayer"), "Label should contain profile name");
        assertTrue(label.contains("Mid-Range"), "Label should contain tier name");
        assertTrue(label.contains("Balanced"), "Label should contain aggressiveness name");
    }

    // ── Required keys are always present ──────────────────

    @Test
    public void testEssentialKeysAlwaysPresentForAllTiers() {
        for (HardwareTier tier : HardwareTier.values()) {
            PresetProfile preset = PresetMatrix.generate(GameProfile.SMP, tier, AggressivenessLevel.BALANCED);
            Map<String, String> settings = preset.getSettings();

            assertTrue(settings.containsKey("workload-limit-ms"),
                    "Missing workload-limit-ms for " + tier);
            assertTrue(settings.containsKey("modules.redstone.max-activations-per-chunk"),
                    "Missing redstone limit for " + tier);
            assertTrue(settings.containsKey("modules.entities.chunk-limiter.max-entities-per-chunk"),
                    "Missing entity limit for " + tier);
            assertTrue(settings.containsKey("automation.thresholds.minor.tps"),
                    "Missing minor TPS for " + tier);
            assertTrue(settings.containsKey("automation.thresholds.moderate.tps"),
                    "Missing moderate TPS for " + tier);
            assertTrue(settings.containsKey("automation.thresholds.critical.tps"),
                    "Missing critical TPS for " + tier);
        }
    }

    // ── Settings are unmodifiable ─────────────────────────

    @Test
    public void testSettingsMapIsImmutable() {
        PresetProfile preset = PresetMatrix.generate(GameProfile.SMP, HardwareTier.MID, AggressivenessLevel.SAFE);
        assertThrows(UnsupportedOperationException.class,
                () -> preset.getSettings().put("new-key", "value"),
                "Settings map should be unmodifiable");
    }

    // ── Helpers ───────────────────────────────────────────

    private int intSetting(PresetProfile p, String key) {
        String val = p.getSettings().get(key);
        assertNotNull(val, "Setting " + key + " is missing from preset");
        return Integer.parseInt(val);
    }

    private double doubleSetting(PresetProfile p, String key) {
        String val = p.getSettings().get(key);
        assertNotNull(val, "Setting " + key + " is missing from preset");
        return Double.parseDouble(val);
    }
}
