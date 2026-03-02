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
 * Tests for PresetMatrix generation — comprehensive coverage of all axes,
 * constraints, and the upgraded fork-aware, player-scaled system.
 */
public class PresetMatrixTest {

    // ── Generate covers all axis combinations ─────────────

    @Test
    public void testGenerateReturnsNonNullForAllCombinations() {
        String[] essentialKeys = {
            "server.view-distance", "server.simulation-distance",
            "bukkit.spawn-limits.monsters", "bukkit.spawn-limits.animals",
            "bukkit.spawn-limits.water-animals", "bukkit.spawn-limits.water-ambient",
            "bukkit.spawn-limits.ambient",
            "bukkit.ticks-per.monster-spawns", "bukkit.ticks-per.animal-spawns",
            "spigot.mob-spawn-range", "spigot.nerf-spawner-mobs",
            "spigot.entity-tracking-range.players", "spigot.merge-radius.item",
            "workload-limit-ms",
            "modules.redstone.max-activations-per-chunk",
            "modules.entities.chunk-limiter.max-entities-per-chunk",
            "modules.entities.limits.per-world-limit.monster",
            "modules.mob-ai.active-radius",
            "modules.density-optimizer.limits.COW",
            "modules.density-optimizer.limits.CHICKEN",
            "modules.density-optimizer.limits.VILLAGER",
            "modules.breeding-limiter.max-animals-per-chunk",
            "modules.villager-optimizer.ai-restore-duration",
            "automation.thresholds.minor.tps",
            "automation.thresholds.moderate.tps",
            "automation.thresholds.critical.tps",
            "modules.chunks.view-distance.min",
        };

        for (GameProfile profile : GameProfile.values()) {
            for (HardwareTier tier : HardwareTier.values()) {
                for (AggressivenessLevel level : AggressivenessLevel.values()) {
                    PresetProfile preset = PresetMatrix.generate(profile, tier, level);
                    String tag = profile + "/" + tier + "/" + level;
                    assertNotNull(preset, "Preset null for " + tag);
                    assertFalse(preset.getSettings().isEmpty(), "Empty for " + tag);
                    assertNotNull(preset.getDescription());
                    assertNotNull(preset.getLabel());

                    for (String key : essentialKeys) {
                        assertTrue(preset.getSettings().containsKey(key),
                                "Missing " + key + " for " + tag);
                    }
                }
            }
        }
    }

    // ── Constraints ───────────────────────────────────────

    @Test
    public void testViewDistAlwaysGreaterOrEqualToSimDist() {
        for (GameProfile profile : GameProfile.values()) {
            for (HardwareTier tier : HardwareTier.values()) {
                for (AggressivenessLevel level : AggressivenessLevel.values()) {
                    PresetProfile p = PresetMatrix.generate(profile, tier, level);
                    int view = intSetting(p, "server.view-distance");
                    int sim = intSetting(p, "server.simulation-distance");
                    assertTrue(view >= sim, "view-dist (" + view + ") < sim-dist (" + sim + ")");
                    assertTrue(view >= 5, "view-dist below minimum");
                    assertTrue(sim >= 5, "sim-dist below minimum");
                }
            }
        }
    }

    @Test
    public void testMobSpawnRangeBoundedBySimDist() {
        for (HardwareTier tier : HardwareTier.values()) {
            PresetProfile p = PresetMatrix.generate(GameProfile.SMP, tier, AggressivenessLevel.BALANCED);
            int sim = intSetting(p, "server.simulation-distance");
            int range = intSetting(p, "spigot.mob-spawn-range");
            assertTrue(range <= sim - 1, "mob-spawn-range must be <= sim-dist-1");
            assertTrue(range >= 3, "mob-spawn-range must be >= 3");
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
                "LOW tier entity limit (" + lowEntities + ") should be < HIGH (" + highEntities + ")");
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
        PresetProfile low = PresetMatrix.generate(GameProfile.SMP, HardwareTier.LOW, AggressivenessLevel.BALANCED);
        PresetProfile high = PresetMatrix.generate(GameProfile.SMP, HardwareTier.HIGH, AggressivenessLevel.BALANCED);

        double lowMinor = doubleSetting(low, "automation.thresholds.minor.tps");
        double highMinor = doubleSetting(high, "automation.thresholds.minor.tps");
        assertTrue(lowMinor > highMinor,
                "LOW minor TPS (" + lowMinor + ") should be > HIGH (" + highMinor + ")");
    }

    // ── Aggressiveness: AGGRESSIVE < SAFE ─────────────────

    @Test
    public void testAggressiveHasTighterLimitsThanSafe() {
        PresetProfile safe = PresetMatrix.generate(GameProfile.SMP, HardwareTier.MID, AggressivenessLevel.SAFE);
        PresetProfile agg = PresetMatrix.generate(GameProfile.SMP, HardwareTier.MID, AggressivenessLevel.AGGRESSIVE);

        assertTrue(intSetting(agg, "modules.entities.chunk-limiter.max-entities-per-chunk")
                < intSetting(safe, "modules.entities.chunk-limiter.max-entities-per-chunk"));
        assertTrue(intSetting(agg, "modules.redstone.max-activations-per-chunk")
                < intSetting(safe, "modules.redstone.max-activations-per-chunk"));
        assertTrue(intSetting(agg, "bukkit.spawn-limits.monsters")
                < intSetting(safe, "bukkit.spawn-limits.monsters"));
    }

    // ── Profile-specific adjustments ──────────────────────

    @Test
    public void testSkyblockReducesEntityLimits() {
        PresetProfile smp = PresetMatrix.generate(GameProfile.SMP, HardwareTier.MID, AggressivenessLevel.BALANCED);
        PresetProfile sky = PresetMatrix.generate(GameProfile.SKYBLOCK, HardwareTier.MID, AggressivenessLevel.BALANCED);

        assertTrue(intSetting(sky, "modules.entities.chunk-limiter.max-entities-per-chunk")
                <= intSetting(smp, "modules.entities.chunk-limiter.max-entities-per-chunk"));
        // SKYBLOCK enables nerf-spawner-mobs
        assertEquals("true", sky.getSettings().get("spigot.nerf-spawner-mobs"));
        assertEquals("false", smp.getSettings().get("spigot.nerf-spawner-mobs"));
    }

    @Test
    public void testCreativeBoostsRedstoneLimits() {
        PresetProfile smp = PresetMatrix.generate(GameProfile.SMP, HardwareTier.MID, AggressivenessLevel.BALANCED);
        PresetProfile cre = PresetMatrix.generate(GameProfile.CREATIVE, HardwareTier.MID, AggressivenessLevel.BALANCED);

        assertTrue(intSetting(cre, "modules.redstone.max-activations-per-chunk")
                > intSetting(smp, "modules.redstone.max-activations-per-chunk"));
    }

    // ── Player count scaling ──────────────────────────────

    @Test
    public void testHighPlayerCountReducesSpawnLimits() {
        PresetProfile low = PresetMatrix.generate(GameProfile.SMP, HardwareTier.MID, AggressivenessLevel.BALANCED, 20);
        PresetProfile high = PresetMatrix.generate(GameProfile.SMP, HardwareTier.MID, AggressivenessLevel.BALANCED, 100);

        assertTrue(intSetting(high, "bukkit.spawn-limits.monsters")
                <= intSetting(low, "bukkit.spawn-limits.monsters"),
                "Higher player count should reduce or keep spawn limits");
    }

    @Test
    public void testPlayerCountInDescription() {
        PresetProfile p = PresetMatrix.generate(GameProfile.SMP, HardwareTier.MID, AggressivenessLevel.BALANCED, 80);
        assertTrue(p.getDescription().contains("80"), "Description should mention player count");
    }

    // ── Load modifier ─────────────────────────────────────

    @Test
    public void testLoadModifierDowngradesHighWith80Players() {
        assertEquals(HardwareTier.MID, PresetMatrix.applyLoadModifier(HardwareTier.HIGH, 80));
    }

    @Test
    public void testLoadModifierDowngradesMidWith50Players() {
        assertEquals(HardwareTier.LOW, PresetMatrix.applyLoadModifier(HardwareTier.MID, 50));
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
        assertTrue(label.contains("Survival Multiplayer"), "Label should contain profile");
        assertTrue(label.contains("Mid-Range"), "Label should contain tier");
        assertTrue(label.contains("Balanced"), "Label should contain aggressiveness");
    }

    // ── Structured description ────────────────────────────

    @Test
    public void testDescriptionHasSectionHeaders() {
        PresetProfile p = PresetMatrix.generate(GameProfile.SMP, HardwareTier.MID, AggressivenessLevel.BALANCED);
        String desc = p.getDescription();
        assertTrue(desc.contains("── Server"), "Description should have Server section");
        assertTrue(desc.contains("── Bukkit"), "Description should have Bukkit section");
        assertTrue(desc.contains("── Spigot"), "Description should have Spigot section");
        assertTrue(desc.contains("── LessLag"), "Description should have LessLag section");
    }

    // ── Settings count ────────────────────────────────────

    @Test
    public void testGeneratesAtLeast30Settings() {
        PresetProfile p = PresetMatrix.generate(GameProfile.SMP, HardwareTier.MID, AggressivenessLevel.BALANCED);
        assertTrue(p.getSettings().size() >= 30,
                "Should generate at least 30 settings, got " + p.getSettings().size());
    }

    // ── Settings immutability ─────────────────────────────

    @Test
    public void testSettingsMapIsImmutable() {
        PresetProfile preset = PresetMatrix.generate(GameProfile.SMP, HardwareTier.MID, AggressivenessLevel.SAFE);
        assertThrows(UnsupportedOperationException.class,
                () -> preset.getSettings().put("new-key", "value"));
    }

    // ── Density profile specialization ────────────────────

    @Test
    public void testDensityLimitsVaryByProfile() {
        PresetProfile smp = PresetMatrix.generate(GameProfile.SMP, HardwareTier.MID, AggressivenessLevel.BALANCED);
        PresetProfile sky = PresetMatrix.generate(GameProfile.SKYBLOCK, HardwareTier.MID, AggressivenessLevel.BALANCED);

        int smpCow = intSetting(smp, "modules.density-optimizer.limits.COW");
        int skyCow = intSetting(sky, "modules.density-optimizer.limits.COW");
        assertTrue(skyCow < smpCow,
                "SKYBLOCK COW density (" + skyCow + ") should be < SMP (" + smpCow + ")");
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
