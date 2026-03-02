package com.lesslag.setup.preset;

import com.lesslag.setup.model.AggressivenessLevel;
import com.lesslag.setup.model.GameProfile;
import com.lesslag.setup.model.HardwareTier;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Comprehensive preset generator — produces optimised server + LessLag
 * config values from a 3-axis matrix (profile × tier × aggressiveness),
 * optional player count.
 *
 * Settings are sourced from Paper Chan's optimisation guide, Minecraft
 * performance best practices, and LessLag-specific module tuning.
 *
 * @see <a href="https://paper-chan.moe/paper-optimization/">Paper Chan Guide</a>
 */
public class PresetMatrix {

    // ─── Scaling helpers ────────────────────────────────────

    private static double pick(double low, double mid, double high, HardwareTier tier) {
        switch (tier) {
            case LOW: return low;
            case HIGH: return high;
            default: return mid;
        }
    }

    private static int pickInt(int low, int mid, int high, HardwareTier tier) {
        switch (tier) {
            case LOW: return low;
            case HIGH: return high;
            default: return mid;
        }
    }

    /** Continuous player-count scaling factor (1.0 at ≤20, ~0.4 floor at 200+). */
    private static double playerScale(int count) {
        if (count <= 20) return 1.0;
        if (count <= 50) return 1.0 - (count - 20) * 0.003;
        if (count <= 100) return 0.91 - (count - 50) * 0.004;
        if (count <= 150) return 0.71 - (count - 100) * 0.003;
        return Math.max(0.4, 0.56 - (count - 150) * 0.002);
    }

    /** Profile-specific entity multiplier. */
    private static double profileEntityFactor(GameProfile profile) {
        switch (profile) {
            case SKYBLOCK: return 0.7;
            case MINIGAME: return 0.6;
            case CREATIVE: return 1.1;
            default: return 1.0;
        }
    }

    private static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    /** Paper Chan cheat sheet: monster limit → recommended mob-spawn-range. */
    private static int recommendedMobSpawnRange(int monsterLimit) {
        if (monsterLimit >= 70) return 8;
        if (monsterLimit >= 56) return 7;
        if (monsterLimit >= 42) return 6;
        if (monsterLimit >= 28) return 5;
        if (monsterLimit >= 14) return 4;
        return 3;
    }

    // ─── Public ─────────────────────────────────────────────

    /**
     * Apply a player-count load modifier that may shift the effective tier.
     */
    public static HardwareTier applyLoadModifier(HardwareTier baseTier, int playerCount) {
        if (playerCount >= 80 && baseTier == HardwareTier.HIGH) return HardwareTier.MID;
        if (playerCount >= 50 && baseTier == HardwareTier.MID) return HardwareTier.LOW;
        if (playerCount >= 100 && baseTier == HardwareTier.MID) return HardwareTier.LOW;
        return baseTier;
    }

    /**
     * Generate a comprehensive preset profile.
     */
    public static PresetProfile generate(GameProfile profile, HardwareTier tier, AggressivenessLevel level) {
        return generate(profile, tier, level, 20);
    }

    /**
     * Generate a comprehensive preset profile with player-count scaling.
     */
    public static PresetProfile generate(GameProfile profile, HardwareTier tier,
                                          AggressivenessLevel level, int playerCount) {
        HardwareTier effectiveTier = applyLoadModifier(tier, playerCount);
        double scale = playerScale(playerCount);

        Map<String, String> settings = new LinkedHashMap<>();
        StringBuilder desc = new StringBuilder();

        desc.append(profile.getDisplayName()).append(" · ")
            .append(effectiveTier.getDisplayName()).append(" · ")
            .append(level.getDisplayName()).append("\n");
        if (playerCount > 1) {
            desc.append(String.format("Target: ~%d players (scale %d%%)\n",
                playerCount, (int) (scale * 100)));
        }
        desc.append("\n");

        // ── Server Core ──
        int viewBase = pickInt(8, 10, 12, effectiveTier);
        if (level == AggressivenessLevel.AGGRESSIVE) viewBase = Math.max(6, viewBase - 2);
        else if (level == AggressivenessLevel.SAFE && effectiveTier == HardwareTier.HIGH)
            viewBase = Math.min(16, viewBase + 2);
        if (profile == GameProfile.CREATIVE) viewBase = Math.min(16, viewBase + 2);

        int viewPenalty = Math.max(0, (playerCount - 30) / 40);
        int viewDist = clamp(viewBase - viewPenalty, 5, 16);

        int simBase = pickInt(6, 8, 10, effectiveTier);
        if (level == AggressivenessLevel.AGGRESSIVE) simBase = Math.max(5, simBase - 1);
        if (profile == GameProfile.MINIGAME) simBase = Math.max(5, simBase - 1);

        int simPenalty = Math.max(0, (playerCount - 50) / 50);
        int simDist = clamp(simBase - simPenalty, 5, 12);
        viewDist = Math.max(viewDist, simDist);

        settings.put("server.view-distance", String.valueOf(viewDist));
        settings.put("server.simulation-distance", String.valueOf(simDist));

        desc.append("── Server ──────────────────────────────\n");
        desc.append("  view-distance: ").append(viewDist)
            .append("    simulation-distance: ").append(simDist).append("\n");

        // ── Bukkit ──
        int monsters = pickInt(28, 35, 50, effectiveTier);
        int animals = pickInt(5, 8, 10, effectiveTier);
        int waterAnimal = pickInt(2, 3, 5, effectiveTier);
        int waterAmbi = pickInt(1, 2, 3, effectiveTier);
        int ambient = pickInt(0, 1, 3, effectiveTier);

        if (profile == GameProfile.SKYBLOCK) {
            monsters = (int) (monsters * 0.8);
            animals = (int) (animals * 0.7);
        } else if (profile == GameProfile.MINIGAME) {
            monsters = (int) (monsters * 0.7);
            animals = Math.max(3, (int) (animals * 0.5));
            waterAnimal = 1; waterAmbi = 0; ambient = 0;
        }

        if (level == AggressivenessLevel.AGGRESSIVE) {
            monsters = Math.max(14, (int) (monsters * 0.6));
            animals = Math.max(3, (int) (animals * 0.6));
            waterAnimal = Math.max(1, (int) (waterAnimal * 0.5));
            ambient = 0;
        }

        if (scale < 1.0) {
            monsters = Math.max(14, (int) (monsters * Math.max(0.7, scale)));
            animals = Math.max(3, (int) (animals * Math.max(0.75, scale)));
        }

        settings.put("bukkit.spawn-limits.monsters", String.valueOf(monsters));
        settings.put("bukkit.spawn-limits.animals", String.valueOf(animals));
        settings.put("bukkit.spawn-limits.water-animals", String.valueOf(waterAnimal));
        settings.put("bukkit.spawn-limits.water-ambient", String.valueOf(waterAmbi));
        settings.put("bukkit.spawn-limits.ambient", String.valueOf(ambient));
        settings.put("bukkit.ticks-per.monster-spawns", "1");
        settings.put("bukkit.ticks-per.animal-spawns", "400");

        desc.append("── Bukkit ──────────────────────────────\n");
        desc.append("  spawn-limits: monsters=").append(monsters)
            .append(", animals=").append(animals)
            .append(", ambient=").append(ambient).append("\n");
        desc.append("  water: animals=").append(waterAnimal)
            .append(", ambient=").append(waterAmbi).append("\n");

        // ── Spigot ──
        int optRange = recommendedMobSpawnRange(monsters);
        int maxRange = Math.max(3, simDist - 1);
        int mobSpawnRange = Math.max(3, Math.min(optRange, maxRange));
        settings.put("spigot.mob-spawn-range", String.valueOf(mobSpawnRange));

        settings.put("spigot.entity-tracking-range.players", "128");
        settings.put("spigot.entity-tracking-range.animals", "96");
        settings.put("spigot.entity-tracking-range.monsters", "96");
        settings.put("spigot.entity-tracking-range.misc", "96");
        settings.put("spigot.entity-tracking-range.display", "128");
        settings.put("spigot.entity-tracking-range.other", "64");
        settings.put("spigot.merge-radius.item", "-1");
        settings.put("spigot.merge-radius.exp", "-1");

        boolean nerfSpawners = profile == GameProfile.SKYBLOCK;
        settings.put("spigot.nerf-spawner-mobs", String.valueOf(nerfSpawners));

        desc.append("── Spigot ──────────────────────────────\n");
        desc.append("  mob-spawn-range: ").append(mobSpawnRange)
            .append("    nerf-spawner-mobs: ").append(nerfSpawners).append("\n");

        // ── LessLag ──
        double workloadMs = pick(1.0, 2.0, 3.0, effectiveTier);
        if (level == AggressivenessLevel.AGGRESSIVE) workloadMs = Math.max(0.5, workloadMs * 0.7);
        settings.put("workload-limit-ms", String.valueOf(workloadMs));

        int redstoneMax = (int) pick(150, 250, 350, effectiveTier);
        if (level == AggressivenessLevel.AGGRESSIVE) redstoneMax = (int) (redstoneMax * 0.6);
        if (profile == GameProfile.CREATIVE) redstoneMax = (int) (redstoneMax * 1.3);
        settings.put("modules.redstone.max-activations-per-chunk", String.valueOf(redstoneMax));
        settings.put("modules.redstone.cooldown-seconds",
            effectiveTier == HardwareTier.LOW ? "15" : "10");

        int chunkLimit = (int) pick(30, 50, 70, effectiveTier);
        if (level == AggressivenessLevel.AGGRESSIVE) chunkLimit = (int) (chunkLimit * 0.6);
        if (profile == GameProfile.SKYBLOCK) chunkLimit = (int) (chunkLimit * 0.8);
        if (scale < 1.0) chunkLimit = Math.max(20, (int) (chunkLimit * scale));
        settings.put("modules.entities.chunk-limiter.max-entities-per-chunk", String.valueOf(chunkLimit));

        int monsterWorld = (int) (pick(1200, 2000, 3000, effectiveTier) * profileEntityFactor(profile));
        int animalWorld = (int) (pick(600, 1000, 1500, effectiveTier) * profileEntityFactor(profile));
        if (level == AggressivenessLevel.AGGRESSIVE) {
            monsterWorld = (int) (monsterWorld * 0.6);
            animalWorld = (int) (animalWorld * 0.6);
        }
        if (scale < 1.0) {
            monsterWorld = Math.max(600, (int) (monsterWorld * scale));
            animalWorld = Math.max(300, (int) (animalWorld * scale));
        }
        settings.put("modules.entities.limits.per-world-limit.monster", String.valueOf(monsterWorld));
        settings.put("modules.entities.limits.per-world-limit.animal", String.valueOf(animalWorld));

        int aiRadius = (int) pick(28, 40, 52, effectiveTier);
        if (level == AggressivenessLevel.AGGRESSIVE) aiRadius = (int) (aiRadius * 0.7);
        if (profile == GameProfile.MINIGAME) aiRadius = (int) (aiRadius * 0.8);
        int aiInterval = pickInt(20, 30, 40, effectiveTier);
        settings.put("modules.mob-ai.active-radius", String.valueOf(aiRadius));
        settings.put("modules.mob-ai.update-interval", String.valueOf(aiInterval));

        // Density optimizer (profile-specific bases)
        int[] livestockBase, chickenBase, villagerBase;
        switch (profile) {
            case SKYBLOCK:  livestockBase = new int[]{5, 8, 11};  chickenBase = new int[]{8, 12, 16};  villagerBase = new int[]{10, 14, 20}; break;
            case MINIGAME:  livestockBase = new int[]{10, 15, 20}; chickenBase = new int[]{15, 20, 25}; villagerBase = new int[]{20, 28, 35}; break;
            case CREATIVE:  livestockBase = new int[]{12, 18, 24}; chickenBase = new int[]{15, 22, 30}; villagerBase = new int[]{18, 25, 32}; break;
            default:        livestockBase = new int[]{7, 10, 14};  chickenBase = new int[]{10, 15, 20};  villagerBase = new int[]{14, 20, 28}; break;
        }

        int cowLim = densityVal(livestockBase, effectiveTier, level, scale);
        int sheepLim = densityVal(livestockBase, effectiveTier, level, scale);
        int pigLim = densityVal(livestockBase, effectiveTier, level, scale);
        int chickLim = densityVal(chickenBase, effectiveTier, level, scale);
        int villLim = densityVal(villagerBase, effectiveTier, level, scale);
        settings.put("modules.density-optimizer.limits.COW", String.valueOf(cowLim));
        settings.put("modules.density-optimizer.limits.SHEEP", String.valueOf(sheepLim));
        settings.put("modules.density-optimizer.limits.PIG", String.valueOf(pigLim));
        settings.put("modules.density-optimizer.limits.CHICKEN", String.valueOf(chickLim));
        settings.put("modules.density-optimizer.limits.VILLAGER", String.valueOf(villLim));

        int breedingLimit = (int) pick(10, 20, 30, effectiveTier);
        if (level == AggressivenessLevel.AGGRESSIVE) breedingLimit = (int) (breedingLimit * 0.6);
        if (profile == GameProfile.SKYBLOCK) breedingLimit = (int) (breedingLimit * 0.7);
        settings.put("modules.breeding-limiter.max-animals-per-chunk", String.valueOf(breedingLimit));

        int restoreDuration = pickInt(15, 30, 45, effectiveTier);
        settings.put("modules.villager-optimizer.ai-restore-duration", String.valueOf(restoreDuration));

        double minorTps = pick(18.5, 18.0, 17.5, effectiveTier);
        double moderateTps = pick(16.0, 15.0, 14.0, effectiveTier);
        double criticalTps = pick(12.0, 10.0, 9.0, effectiveTier);
        settings.put("automation.thresholds.minor.tps", String.valueOf(minorTps));
        settings.put("automation.thresholds.moderate.tps", String.valueOf(moderateTps));
        settings.put("automation.thresholds.critical.tps", String.valueOf(criticalTps));

        settings.put("modules.chunks.view-distance.min", "5");
        settings.put("modules.chunks.simulation-distance.min", "5");

        desc.append("── LessLag ─────────────────────────────\n");
        desc.append("  workload: ").append(workloadMs).append("ms    ai-radius: ").append(aiRadius)
            .append("    redstone: ").append(redstoneMax).append("/chunk\n");
        desc.append("  density: COW=").append(cowLim).append(" SHEEP=").append(sheepLim)
            .append(" PIG=").append(pigLim).append(" CHICKEN=").append(chickLim)
            .append(" VILLAGER=").append(villLim).append("\n");
        desc.append("  entity-limit: ").append(chunkLimit).append("/chunk    breeding: ")
            .append(breedingLimit).append("/chunk\n");
        desc.append("  thresholds: minor=").append(minorTps).append(" moderate=").append(moderateTps)
            .append(" critical=").append(criticalTps).append("\n");

        desc.append("\n").append(settings.size()).append(" settings generated\n");

        return new PresetProfile(profile, effectiveTier, level, settings, desc.toString());
    }

    // ─── Private helpers ────────────────────────────────────

    private static int densityVal(int[] base, HardwareTier tier, AggressivenessLevel level, double scale) {
        int v = pickInt(base[0], base[1], base[2], tier);
        if (level == AggressivenessLevel.AGGRESSIVE) v = Math.max(3, (int) (v * 0.6));
        if (scale < 1.0) v = Math.max(3, (int) (v * Math.max(0.7, scale)));
        return v;
    }
}
