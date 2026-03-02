package com.lesslag.setup.rules;

import com.lesslag.setup.detect.ConfigAdapter;
import com.lesslag.setup.detect.PlatformDetector;
import com.lesslag.setup.detect.PluginScanner;
import com.lesslag.setup.model.*;

import java.util.List;

/**
 * Consistency rules ensure related settings are coherent.
 * e.g. view-distance ≥ simulation-distance, mob-spawn-range ≤ sim-dist - 1,
 * spawn limits consistent with spawn ranges, merge-radius at vanilla defaults.
 * Values sourced from Paper Chan's optimisation guide:
 *   https://paper-chan.moe/paper-optimization/
 */
public class ConsistencyRules implements Rule {

    @Override public String getId() { return "consistency"; }
    @Override public String getGroup() { return "consistency"; }
    @Override public int getPriority() { return 20; }

    @Override
    public void evaluate(PlatformDetector platform, ConfigAdapter configs, PluginScanner plugins,
                          HardwareAssessment hardware, GameProfile profile, HardwareTier tier,
                          AggressivenessLevel level, List<RuleResult> results, List<PatchProposal> proposals) {

        checkViewSimDistance(configs, tier, level, results, proposals);
        checkSpawnDensityCoherence(configs, tier, level, results, proposals);
        checkMobSpawnRange(configs, tier, level, results, proposals);
        checkMergeRadius(configs, results, proposals);
        checkEntityTrackingRanges(configs, results, proposals);
        checkTicksPer(configs, results, proposals);
        checkChunkEntityCoherence(configs, tier, results, proposals);
    }

    /**
     * View distance should be ≥ simulation distance.
     * Paper Chan: don't go below 5 for either. sim-dist must be ≤ view-dist.
     */
    private void checkViewSimDistance(ConfigAdapter configs, HardwareTier tier,
                                      AggressivenessLevel level,
                                      List<RuleResult> results, List<PatchProposal> proposals) {
        int viewDist = configs.getInt("server.properties", "view-distance", 10);
        int simDist = configs.getInt("spigot.yml", "world-settings.default.simulation-distance", -1);
        if (simDist < 0) {
            simDist = configs.getInt("server.properties", "simulation-distance", 10);
        }

        // Recommended values by tier  (Paper Chan: never below 5)
        int recView, recSim;
        switch (tier) {
            case LOW:
                recView = level == AggressivenessLevel.AGGRESSIVE ? 6 : 8;
                recSim = level == AggressivenessLevel.AGGRESSIVE ? 5 : 6;
                break;
            case HIGH:
                recView = level == AggressivenessLevel.SAFE ? 12 : 10;
                recSim = level == AggressivenessLevel.SAFE ? 10 : 8;
                break;
            default: // MID
                recView = level == AggressivenessLevel.AGGRESSIVE ? 7 : 10;
                recSim = level == AggressivenessLevel.AGGRESSIVE ? 6 : 8;
                break;
        }
        recView = Math.max(5, recView);
        recSim = Math.max(5, recSim);

        if (viewDist < simDist) {
            results.add(RuleResult.builder("consistency-view-sim")
                .group("consistency").severity(Severity.WARNING).confidence(0.95)
                .why("View distance (" + viewDist + ") is less than simulation distance (" + simDist + ")")
                .impact("Players see chunks that aren't fully simulated, causing visual glitches")
                .tradeoff("Increasing view distance uses more bandwidth; decreasing sim distance saves CPU")
                .recommendation("Set view-distance=" + recView + " and simulation-distance=" + recSim)
                .impactedKey("server.properties:view-distance")
                .impactedKey("server.properties:simulation-distance")
                .build());
        }

        // Paper Chan: do not go below 5 for either distance
        if (viewDist < 5) {
            results.add(RuleResult.builder("consistency-view-too-low")
                .group("consistency").severity(Severity.WARNING).confidence(0.95)
                .why("View distance is " + viewDist + " — below the recommended minimum of 5")
                .impact("Values below 5 cause significant gameplay issues (mob spawning, " +
                        "structure generation, rendering)")
                .tradeoff("Lower values save bandwidth and CPU but degrade the player experience")
                .recommendation("Set view-distance to at least 5 (recommended: " + recView + ")")
                .manualSteps("In server.properties, set view-distance=" + Math.max(5, recView))
                .impactedKey("server.properties:view-distance")
                .build());
        }
        if (simDist < 5) {
            results.add(RuleResult.builder("consistency-sim-too-low")
                .group("consistency").severity(Severity.WARNING).confidence(0.95)
                .why("Simulation distance is " + simDist + " — below the recommended minimum of 5")
                .impact("Values below 5 break mob farms, prevent spawning of some structures, " +
                        "and reduce game mechanics range")
                .tradeoff("Lower values save CPU but degrade gameplay quality significantly")
                .recommendation("Set simulation-distance to at least 5 (recommended: " + recSim + ")")
                .manualSteps("In server.properties, set simulation-distance=" + Math.max(5, recSim))
                .impactedKey("server.properties:simulation-distance")
                .build());
        }

        if (viewDist != recView) {
            proposals.add(new PatchProposal("server.properties", "view-distance",
                String.valueOf(viewDist), String.valueOf(recView),
                RiskTag.LOW, ApplyScope.RECOMMEND, "consistency-view-sim",
                "Adjust view distance for " + tier.getDisplayName() + " hardware"));
        }
        if (simDist != recSim) {
            proposals.add(new PatchProposal("server.properties", "simulation-distance",
                String.valueOf(simDist), String.valueOf(recSim),
                RiskTag.LOW, ApplyScope.RECOMMEND, "consistency-view-sim",
                "Adjust simulation distance for " + tier.getDisplayName() + " hardware"));
        }
    }

    /**
     * Spawn limits should be consistent with hardware tier.
     * Paper Chan: monsters default 70, reducing to 35 is a safe starting point (~50% barely noticeable).
     * ambient: 0 is safe (only bats, useless).
     */
    private void checkSpawnDensityCoherence(ConfigAdapter configs, HardwareTier tier,
                                             AggressivenessLevel level,
                                             List<RuleResult> results, List<PatchProposal> proposals) {
        int monsterSpawn = configs.getInt("bukkit.yml", "spawn-limits.monsters", 70);
        int animalSpawn = configs.getInt("bukkit.yml", "spawn-limits.animals", 10);
        int ambientSpawn = configs.getInt("bukkit.yml", "spawn-limits.ambient", 15);

        // Paper Chan recommended values based on tier
        int recMonster, recAnimal, recAmbient;
        switch (tier) {
            case LOW:  recMonster = 28; recAnimal = 5;  recAmbient = 0; break;
            case HIGH: recMonster = 70; recAnimal = 10; recAmbient = 5; break;
            default:   recMonster = 35; recAnimal = 8;  recAmbient = 1; break;
        }
        if (level == AggressivenessLevel.AGGRESSIVE) {
            recMonster = Math.max(21, (int) (recMonster * 0.6));
            recAnimal = Math.max(3, (int) (recAnimal * 0.6));
        }

        if (monsterSpawn > recMonster * 1.3) {
            results.add(RuleResult.builder("consistency-spawn-limits")
                .group("consistency").severity(Severity.INFO).confidence(0.85)
                .why("Monster spawn limit (" + monsterSpawn + ") is high for " + tier.getDisplayName() + " hardware")
                .impact("More hostile mobs = more AI ticking, pathfinding, and combat processing. " +
                        "Paper Chan: reducing to ~35 is a safe starting point, ~50% reduction barely noticeable")
                .tradeoff("Lower spawn limits mean fewer mob encounters but better TPS. " +
                        "Use spawn-limits as the PRIMARY control (not ticks-per)")
                .recommendation("Set spawn-limits.monsters=" + recMonster)
                .manualSteps("In bukkit.yml, set spawn-limits.monsters: " + recMonster)
                .impactedKey("bukkit.yml:spawn-limits.monsters")
                .build());

            proposals.add(new PatchProposal("bukkit.yml", "spawn-limits.monsters",
                String.valueOf(monsterSpawn), String.valueOf(recMonster),
                RiskTag.MEDIUM, ApplyScope.RECOMMEND, "consistency-spawn-limits",
                "Reduce monster spawn limit (Paper Chan: 35 is a safe starting point)"));
        }

        if (animalSpawn > recAnimal * 1.5 && tier != HardwareTier.HIGH) {
            proposals.add(new PatchProposal("bukkit.yml", "spawn-limits.animals",
                String.valueOf(animalSpawn), String.valueOf(recAnimal),
                RiskTag.LOW, ApplyScope.RECOMMEND, "consistency-spawn-limits",
                "Reduce animal spawn limit for better performance"));
        }

        // Paper Chan: ambient 0 is safe (only bats)
        if (ambientSpawn > recAmbient && tier != HardwareTier.HIGH) {
            proposals.add(new PatchProposal("bukkit.yml", "spawn-limits.ambient",
                String.valueOf(ambientSpawn), String.valueOf(recAmbient),
                RiskTag.LOW, ApplyScope.RECOMMEND, "consistency-spawn-ambient",
                "Reduce ambient spawns — only bats, safe to set 0 (Paper Chan recommended)"));
        }
    }

    /**
     * mob-spawn-range must be ≤ simulation-distance - 1, minimum 3.
     * Paper Chan cheat sheet:
     *   70 monsters → 8 range, 49 → 6-7, 35 → 5-6, 28 → 5, 21 → 4-5.
     */
    private void checkMobSpawnRange(ConfigAdapter configs, HardwareTier tier,
                                     AggressivenessLevel level,
                                     List<RuleResult> results, List<PatchProposal> proposals) {
        int simDist = configs.getInt("server.properties", "simulation-distance", 10);
        int monsterLimit = configs.getInt("bukkit.yml", "spawn-limits.monsters", 70);
        int spawnRange = configs.getInt("spigot.yml",
            "world-settings.default.mob-spawn-range", 8);

        int maxRange = Math.max(3, simDist - 1);
        // Paper Chan cheat sheet: optimal range based on monster limit
        int optRange = recommendedMobSpawnRange(monsterLimit);
        int recRange = Math.max(3, Math.min(optRange, maxRange));

        if (spawnRange > maxRange) {
            results.add(RuleResult.builder("consistency-mob-spawn-range")
                .group("consistency").severity(Severity.WARNING).confidence(0.9)
                .why("mob-spawn-range (" + spawnRange + ") exceeds simulation-distance - 1 (" + maxRange + ")")
                .impact("Mobs can spawn in chunks that aren't fully simulated, wasting the mob cap")
                .tradeoff("mob-spawn-range should never exceed sim-dist - 1")
                .recommendation("Set mob-spawn-range: " + recRange +
                        " (for " + monsterLimit + " monsters with sim-dist " + simDist + ")")
                .manualSteps("In spigot.yml, set world-settings.default.mob-spawn-range: " + recRange)
                .impactedKey("spigot.yml:world-settings.default.mob-spawn-range")
                .build());

            proposals.add(new PatchProposal("spigot.yml",
                "world-settings.default.mob-spawn-range",
                String.valueOf(spawnRange), String.valueOf(recRange),
                RiskTag.MEDIUM, ApplyScope.RECOMMEND, "consistency-mob-spawn-range",
                "Align mob-spawn-range with sim-dist (Paper Chan cheat sheet)"));
        } else if (spawnRange != recRange) {
            results.add(RuleResult.builder("consistency-mob-spawn-range-tune")
                .group("consistency").severity(Severity.INFO).confidence(0.8)
                .why("mob-spawn-range is " + spawnRange + ", optimal is " + recRange + " for " +
                        monsterLimit + " monsters / sim-dist " + simDist)
                .impact("Sub-optimal range can reduce mob density or waste mob cap slots")
                .tradeoff("Paper Chan cheat sheet correlates monster limit to ideal spawn range")
                .recommendation("Set mob-spawn-range: " + recRange)
                .manualSteps("In spigot.yml, set world-settings.default.mob-spawn-range: " + recRange)
                .impactedKey("spigot.yml:world-settings.default.mob-spawn-range")
                .build());

            proposals.add(new PatchProposal("spigot.yml",
                "world-settings.default.mob-spawn-range",
                String.valueOf(spawnRange), String.valueOf(recRange),
                RiskTag.LOW, ApplyScope.RECOMMEND, "consistency-mob-spawn-range-tune",
                "Tune mob-spawn-range per Paper Chan cheat sheet"));
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

    /**
     * merge-radius: Paper Chan recommends keeping vanilla (-1).
     * Increasing merge radius only saves a fraction of what reducing spawn-limits does.
     */
    private void checkMergeRadius(ConfigAdapter configs,
                                   List<RuleResult> results, List<PatchProposal> proposals) {
        int itemMerge = configs.getInt("spigot.yml",
            "world-settings.default.merge-radius.item", -1);
        int expMerge = configs.getInt("spigot.yml",
            "world-settings.default.merge-radius.exp", -1);

        if (itemMerge > 0 || expMerge > 0) {
            results.add(RuleResult.builder("consistency-merge-radius")
                .group("consistency").severity(Severity.INFO).confidence(0.8)
                .why("merge-radius is set to item:" + itemMerge + " exp:" + expMerge +
                        " — Paper Chan recommends keeping vanilla (-1)")
                .impact("Increasing merge radius barely improves performance and causes visual jitter. " +
                        "Reducing spawn-limits is far more effective")
                .tradeoff("Set to -1 for vanilla behaviour; reducing spawn-limits is the proper fix")
                .recommendation("Set merge-radius.item: -1 and merge-radius.exp: -1")
                .manualSteps("In spigot.yml, set world-settings.default.merge-radius.item: -1 and exp: -1")
                .impactedKey("spigot.yml:world-settings.default.merge-radius.item")
                .build());

            if (itemMerge > 0) {
                proposals.add(new PatchProposal("spigot.yml",
                    "world-settings.default.merge-radius.item",
                    String.valueOf(itemMerge), "-1",
                    RiskTag.LOW, ApplyScope.RECOMMEND, "consistency-merge-radius",
                    "Keep vanilla merge radius — reduce spawn-limits instead (Paper Chan recommended)"));
            }
        }
    }

    /**
     * entity-tracking-range: Paper Chan recommended values for vanilla parity.
     */
    private void checkEntityTrackingRanges(ConfigAdapter configs,
                                            List<RuleResult> results, List<PatchProposal> proposals) {
        int playerTracking = configs.getInt("spigot.yml",
            "world-settings.default.entity-tracking-range.players", 128);

        // Paper Chan: players: 128, animals: 96, monsters: 96, misc: 96, display: 128, other: 64
        if (playerTracking < 48) {
            results.add(RuleResult.builder("consistency-entity-tracking")
                .group("consistency").severity(Severity.INFO).confidence(0.8)
                .why("Player entity-tracking-range is " + playerTracking +
                        " — below recommended 128 for vanilla parity")
                .impact("Low tracking ranges make players invisible at shorter distances")
                .tradeoff("Higher tracking ranges use more bandwidth but improve gameplay")
                .recommendation("Set entity-tracking-range.players: 128, monsters: 96, " +
                        "animals: 96 for vanilla parity")
                .manualSteps("In spigot.yml, set world-settings.default.entity-tracking-range:\n" +
                        "  players: 128\n  animals: 96\n  monsters: 96\n" +
                        "  misc: 96\n  display: 128\n  other: 64")
                .impactedKey("spigot.yml:world-settings.default.entity-tracking-range.players")
                .build());
        }
    }

    /**
     * ticks-per: Paper Chan: animal-spawns: 400, autosave: 6000.
     * ticks-per is the SECONDARY control after spawn-limits.
     */
    private void checkTicksPer(ConfigAdapter configs,
                                List<RuleResult> results, List<PatchProposal> proposals) {
        int animalTicks = configs.getInt("bukkit.yml", "ticks-per.animal-spawns", 400);
        // autoSave read intentionally omitted — not yet used in rules

        if (animalTicks < 400) {
            results.add(RuleResult.builder("consistency-ticks-per-animals")
                .group("consistency").severity(Severity.INFO).confidence(0.7)
                .why("ticks-per.animal-spawns is " + animalTicks + " — lower than recommended 400")
                .impact("Animals are attempted to spawn more frequently than needed, wasting CPU")
                .tradeoff("Paper Chan: use spawn-limits as primary control, ticks-per as secondary")
                .recommendation("Set ticks-per.animal-spawns: 400")
                .manualSteps("In bukkit.yml, set ticks-per.animal-spawns: 400")
                .impactedKey("bukkit.yml:ticks-per.animal-spawns")
                .build());

            proposals.add(new PatchProposal("bukkit.yml", "ticks-per.animal-spawns",
                String.valueOf(animalTicks), "400",
                RiskTag.LOW, ApplyScope.RECOMMEND, "consistency-ticks-per-animals",
                "Increase animal spawn tick interval to 400 (Paper Chan recommended)"));
        }
    }

    /**
     * LessLag chunk-entity limits should align with server spawn limits.
     */
    private void checkChunkEntityCoherence(ConfigAdapter configs, HardwareTier tier,
                                            List<RuleResult> results, List<PatchProposal> proposals) {
        // Recommend LessLag entity limits based on tier
        int recPerChunk;
        switch (tier) {
            case LOW:  recPerChunk = 30; break;
            case HIGH: recPerChunk = 60; break;
            default:   recPerChunk = 45; break;
        }

        proposals.add(new PatchProposal("config.yml",
            "modules.entities.chunk-limiter.max-entities-per-chunk",
            "50", String.valueOf(recPerChunk),
            RiskTag.LOW, ApplyScope.LESSLAG_APPLY, "consistency-chunk-entity",
            "Set chunk entity limit matching " + tier.getDisplayName() + " tier"));
    }
}
