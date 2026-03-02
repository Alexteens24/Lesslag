package com.lesslag.setup.preset;

import com.lesslag.setup.model.AggressivenessLevel;
import com.lesslag.setup.model.GameProfile;
import com.lesslag.setup.model.HardwareTier;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generates PresetProfiles from the 3-axis matrix:
 *   Game profile × Hardware tier × Aggressiveness level.
 *
 * LessLag-specific config values, plus recommended server config values
 * sourced from Paper Chan's optimisation guide:
 *   https://paper-chan.moe/paper-optimization/
 *
 * Optional load modifier from player concurrency can shift tier down.
 */
public class PresetMatrix {

    /**
     * Apply a player-count load modifier that may shift the effective tier.
     *
     * @param baseTier       detected hardware tier
     * @param playerCount    current or expected player count
     * @return effective tier after load adjustment
     */
    public static HardwareTier applyLoadModifier(HardwareTier baseTier, int playerCount) {
        if (playerCount >= 80 && baseTier == HardwareTier.HIGH) {
            return HardwareTier.MID; // high player counts stress even good hardware
        }
        if (playerCount >= 50 && baseTier == HardwareTier.MID) {
            return HardwareTier.LOW;
        }
        if (playerCount >= 100 && baseTier == HardwareTier.MID) {
            return HardwareTier.LOW;
        }
        return baseTier;
    }

    /**
     * Generate a full preset profile for the given axes.
     */
    public static PresetProfile generate(GameProfile profile, HardwareTier tier, AggressivenessLevel level) {
        Map<String, String> settings = new LinkedHashMap<>();
        StringBuilder desc = new StringBuilder();

        desc.append("Preset: ").append(profile.getDisplayName())
            .append(" / ").append(tier.getDisplayName())
            .append(" / ").append(level.getDisplayName()).append("\n");

        // ── Core workload budget ──
        double workloadMs = baseValue(1.0, 2.0, 3.0, tier);
        if (level == AggressivenessLevel.AGGRESSIVE) workloadMs = Math.max(0.5, workloadMs * 0.7);
        settings.put("workload-limit-ms", String.valueOf(workloadMs));

        // ── Redstone ──
        int redstoneMax = (int) baseValue(150, 250, 350, tier);
        if (level == AggressivenessLevel.AGGRESSIVE) redstoneMax = (int) (redstoneMax * 0.6);
        if (profile == GameProfile.CREATIVE) redstoneMax = (int) (redstoneMax * 1.3);
        settings.put("modules.redstone.max-activations-per-chunk", String.valueOf(redstoneMax));
        settings.put("modules.redstone.cooldown-seconds", tier == HardwareTier.LOW ? "15" : "10");

        // ── Entity limits ──
        int chunkLimit = (int) baseValue(30, 50, 70, tier);
        if (level == AggressivenessLevel.AGGRESSIVE) chunkLimit = (int) (chunkLimit * 0.6);
        if (profile == GameProfile.SKYBLOCK) chunkLimit = (int) (chunkLimit * 0.8);
        settings.put("modules.entities.chunk-limiter.max-entities-per-chunk", String.valueOf(chunkLimit));

        int monsterPerWorld = (int) baseValue(1200, 2000, 3000, tier);
        int animalPerWorld = (int) baseValue(600, 1000, 1500, tier);
        if (level == AggressivenessLevel.AGGRESSIVE) {
            monsterPerWorld = (int) (monsterPerWorld * 0.6);
            animalPerWorld = (int) (animalPerWorld * 0.6);
        }
        settings.put("modules.entities.limits.per-world-limit.monster", String.valueOf(monsterPerWorld));
        settings.put("modules.entities.limits.per-world-limit.animal", String.valueOf(animalPerWorld));

        // ── Mob AI / Frustum Culling ──
        int aiRadius = (int) baseValue(28, 40, 52, tier);
        if (level == AggressivenessLevel.AGGRESSIVE) aiRadius = (int) (aiRadius * 0.7);
        if (profile == GameProfile.MINIGAME) aiRadius = (int) (aiRadius * 0.8);
        settings.put("modules.mob-ai.active-radius", String.valueOf(aiRadius));
        settings.put("modules.mob-ai.update-interval", String.valueOf(tier == HardwareTier.LOW ? 20 : 30));

        // ── Density optimizer ──
        int cowLimit = densityLimit(7, 10, 14, tier, level, profile);
        int sheepLimit = densityLimit(7, 10, 14, tier, level, profile);
        int pigLimit = densityLimit(7, 10, 14, tier, level, profile);
        int chickenLimit = densityLimit(10, 15, 20, tier, level, profile);
        int villagerLimit = densityLimit(14, 20, 28, tier, level, profile);
        settings.put("modules.density-optimizer.limits.COW", String.valueOf(cowLimit));
        settings.put("modules.density-optimizer.limits.SHEEP", String.valueOf(sheepLimit));
        settings.put("modules.density-optimizer.limits.PIG", String.valueOf(pigLimit));
        settings.put("modules.density-optimizer.limits.CHICKEN", String.valueOf(chickenLimit));
        settings.put("modules.density-optimizer.limits.VILLAGER", String.valueOf(villagerLimit));

        // ── Breeding limiter ──
        int breedingLimit = (int) baseValue(10, 20, 30, tier);
        if (level == AggressivenessLevel.AGGRESSIVE) breedingLimit = (int) (breedingLimit * 0.6);
        if (profile == GameProfile.SKYBLOCK) breedingLimit = (int) (breedingLimit * 0.7);
        settings.put("modules.breeding-limiter.max-animals-per-chunk", String.valueOf(breedingLimit));

        // ── Villager optimizer ──
        settings.put("modules.villager-optimizer.ai-restore-duration",
            String.valueOf(tier == HardwareTier.LOW ? 15 : tier == HardwareTier.HIGH ? 45 : 30));

        // ── TPS thresholds ──
        double minorTps = baseValue(18.5, 18.0, 17.5, tier);
        double moderateTps = baseValue(16.0, 15.0, 14.0, tier);
        double criticalTps = baseValue(12.0, 10.0, 9.0, tier);
        settings.put("automation.thresholds.minor.tps", String.valueOf(minorTps));
        settings.put("automation.thresholds.moderate.tps", String.valueOf(moderateTps));
        settings.put("automation.thresholds.critical.tps", String.valueOf(criticalTps));

        // ── Chunk management ──
        int viewMin = tier == HardwareTier.LOW ? 5 : 5; // Paper Chan: never below 5
        settings.put("modules.chunks.view-distance.min", String.valueOf(viewMin));
        settings.put("modules.chunks.simulation-distance.min", String.valueOf(viewMin));

        // ── Server config recommendations (Paper Chan) ──
        // These are included in the preset report as recommended values
        int recViewDist, recSimDist;
        switch (tier) {
            case LOW:  recViewDist = level == AggressivenessLevel.AGGRESSIVE ? 6 : 8;
                       recSimDist = level == AggressivenessLevel.AGGRESSIVE ? 5 : 6; break;
            case HIGH: recViewDist = 10; recSimDist = 10; break;
            default:   recViewDist = level == AggressivenessLevel.AGGRESSIVE ? 7 : 10;
                       recSimDist = level == AggressivenessLevel.AGGRESSIVE ? 6 : 8; break;
        }
        settings.put("server.view-distance", String.valueOf(Math.max(5, recViewDist)));
        settings.put("server.simulation-distance", String.valueOf(Math.max(5, recSimDist)));

        // Paper Chan: spawn-limits  (monsters: 35 safe starting point, ambient: 0 safe)
        int recMonsters, recAnimals, recAmbient;
        switch (tier) {
            case LOW:  recMonsters = 28; recAnimals = 5; recAmbient = 0; break;
            case HIGH: recMonsters = 70; recAnimals = 10; recAmbient = 5; break;
            default:   recMonsters = 35; recAnimals = 8; recAmbient = 1; break;
        }
        if (level == AggressivenessLevel.AGGRESSIVE) {
            recMonsters = Math.max(21, (int) (recMonsters * 0.6));
        }
        settings.put("bukkit.spawn-limits.monsters", String.valueOf(recMonsters));
        settings.put("bukkit.spawn-limits.animals", String.valueOf(recAnimals));
        settings.put("bukkit.spawn-limits.ambient", String.valueOf(recAmbient));

        // Paper Chan cheat sheet: mob-spawn-range based on monster limit
        int recMobSpawnRange = recommendedMobSpawnRange(recMonsters);
        recMobSpawnRange = Math.max(3, Math.min(recMobSpawnRange, Math.max(3, recSimDist - 1)));
        settings.put("spigot.mob-spawn-range", String.valueOf(recMobSpawnRange));

        // ── Description ──
        desc.append("Entity chunk limit: ").append(chunkLimit).append("\n");
        desc.append("AI culling radius: ").append(aiRadius).append("\n");
        desc.append("Redstone limit: ").append(redstoneMax).append("/chunk\n");
        desc.append("TPS thresholds: minor=").append(minorTps)
            .append(" moderate=").append(moderateTps)
            .append(" critical=").append(criticalTps).append("\n");
        desc.append("Recommended server config (Paper Chan guide):\n");
        desc.append("  view-distance: ").append(Math.max(5, recViewDist))
            .append(", sim-distance: ").append(Math.max(5, recSimDist)).append("\n");
        desc.append("  spawn-limits.monsters: ").append(recMonsters)
            .append(", mob-spawn-range: ").append(recMobSpawnRange).append("\n");

        return new PresetProfile(profile, tier, level, settings, desc.toString());
    }

    // ── Helpers ──

    private static double baseValue(double low, double mid, double high, HardwareTier tier) {
        switch (tier) {
            case LOW: return low;
            case HIGH: return high;
            default: return mid;
        }
    }

    /** Paper Chan cheat sheet: monster limit → recommended mob-spawn-range */
    private static int recommendedMobSpawnRange(int monsterLimit) {
        if (monsterLimit >= 70) return 8;
        if (monsterLimit >= 56) return 7;
        if (monsterLimit >= 42) return 6;
        if (monsterLimit >= 28) return 5;
        if (monsterLimit >= 14) return 4;
        return 3; // minimum
    }

    private static int densityLimit(int low, int mid, int high,
                                     HardwareTier tier, AggressivenessLevel level, GameProfile profile) {
        int base;
        switch (tier) {
            case LOW: base = low; break;
            case HIGH: base = high; break;
            default: base = mid; break;
        }
        if (level == AggressivenessLevel.AGGRESSIVE) base = Math.max(3, (int) (base * 0.6));
        if (profile == GameProfile.SKYBLOCK) base = Math.max(3, (int) (base * 0.8));
        return base;
    }
}
