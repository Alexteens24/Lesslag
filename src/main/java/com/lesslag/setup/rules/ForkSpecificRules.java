package com.lesslag.setup.rules;

import com.lesslag.setup.detect.ConfigAdapter;
import com.lesslag.setup.detect.PlatformDetector;
import com.lesslag.setup.detect.PluginScanner;
import com.lesslag.setup.model.*;

import java.util.List;

/**
 * Fork-specific rules for Paper, Purpur, Pufferfish, and Leaf.
 * Paper-specific recommendations sourced from Paper Chan's optimisation guide:
 *   https://paper-chan.moe/paper-optimization/
 */
public class ForkSpecificRules implements Rule {

    @Override public String getId() { return "fork-specific"; }
    @Override public String getGroup() { return "fork-specific"; }
    @Override public int getPriority() { return 40; }

    @Override
    public void evaluate(PlatformDetector platform, ConfigAdapter configs, PluginScanner plugins,
                          HardwareAssessment hardware, GameProfile profile, HardwareTier tier,
                          AggressivenessLevel level, List<RuleResult> results, List<PatchProposal> proposals) {

        if (platform.isPurpur()) {
            evaluatePurpur(configs, tier, level, results, proposals);
        }
        if (platform.isPufferfish()) {
            evaluatePufferfish(configs, tier, level, results, proposals);
        }
        if (platform.isLeaf()) {
            evaluateLeaf(configs, tier, level, results, proposals);
        }
        if (platform.isLuminol()) {
            evaluateLuminol(results);
        }

        // Paper-specific (applies to all Paper-based forks)
        if (platform.isPaper()) {
            evaluatePaperWorldDefaults(configs, tier, level, profile, results, proposals);
            evaluatePaperGlobal(configs, tier, level, results, proposals);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  Paper — paper-world-defaults.yml  (sourced from paper-chan.moe)
    // ──────────────────────────────────────────────────────────────────

    private void evaluatePaperWorldDefaults(ConfigAdapter configs, HardwareTier tier,
                                             AggressivenessLevel level, GameProfile profile,
                                             List<RuleResult> results, List<PatchProposal> proposals) {
        if (!configs.isPresent("config/paper-world-defaults.yml")) return;

        checkRedstoneImplementation(configs, results, proposals);
        checkPerPlayerMobSpawns(configs, results, proposals);
        checkPreventMovingUnloadedChunks(configs, results, proposals);
        checkMaxEntityCollisions(configs, tier, level, results, proposals);
        checkFixClimbingBypassCramming(configs, results, proposals);
        checkOptimizeExplosions(configs, profile, results, proposals);
        checkTreasureMaps(configs, results, proposals);
        checkFeatureSeeds(configs, results, proposals);
        checkDelayChunkUnloads(configs, results, proposals);
        checkMaxAutoSaveChunks(configs, results, proposals);
        checkAltItemDespawnRate(configs, results, proposals);
        checkEntityPerChunkSaveLimit(configs, results, proposals);
        checkDespawnTime(configs, results, proposals);
        checkDespawnRanges(configs, tier, results, proposals);
        checkVillagerTickRates(configs, tier, level, results, proposals);
        checkArmorStands(configs, results, proposals);
        checkTrackingRangeY(configs, results, proposals);
    }

    /* Redstone: ALTERNATE_CURRENT is recommended, more efficient than VANILLA */
    private void checkRedstoneImplementation(ConfigAdapter configs,
                                              List<RuleResult> results, List<PatchProposal> proposals) {
        String impl = configs.getString("config/paper-world-defaults.yml",
            "misc.redstone-implementation", "VANILLA");

        if (!"ALTERNATE_CURRENT".equalsIgnoreCase(impl)) {
            results.add(RuleResult.builder("paper-redstone-impl")
                .group("fork-specific").severity(Severity.INFO).confidence(0.9)
                .why("Redstone implementation is '" + impl + "' — ALTERNATE_CURRENT is more efficient")
                .impact("ALTERNATE_CURRENT is significantly faster with possible minor behaviour changes")
                .tradeoff("Some complex redstone contraptions may behave slightly differently; test first")
                .recommendation("Set redstone-implementation: ALTERNATE_CURRENT")
                .manualSteps("In config/paper-world-defaults.yml, set misc.redstone-implementation: ALTERNATE_CURRENT")
                .impactedKey("config/paper-world-defaults.yml:misc.redstone-implementation")
                .build());

            proposals.add(new PatchProposal("config/paper-world-defaults.yml",
                "misc.redstone-implementation",
                impl, "ALTERNATE_CURRENT",
                RiskTag.MEDIUM, ApplyScope.RECOMMEND, "paper-redstone-impl",
                "Use ALTERNATE_CURRENT redstone for better performance (Paper Chan recommended)"));
        }
    }

    /* per-player-mob-spawns: true ensures even mob distribution */
    private void checkPerPlayerMobSpawns(ConfigAdapter configs,
                                          List<RuleResult> results, List<PatchProposal> proposals) {
        boolean enabled = configs.getBoolean("config/paper-world-defaults.yml",
            "entities.spawning.per-player-mob-spawns", true);

        if (!enabled) {
            results.add(RuleResult.builder("paper-per-player-mob-spawns")
                .group("fork-specific").severity(Severity.WARNING).confidence(0.95)
                .why("per-player-mob-spawns is disabled — mob spawning uses shared global cap")
                .impact("Without this, mob spawning is uneven and farms near players with many " +
                        "spawnable chunks get unfair advantage")
                .tradeoff("Beneficial for the majority of servers; very few reasons to disable")
                .recommendation("Enable per-player-mob-spawns: true")
                .manualSteps("In config/paper-world-defaults.yml, set entities.spawning.per-player-mob-spawns: true")
                .impactedKey("config/paper-world-defaults.yml:entities.spawning.per-player-mob-spawns")
                .build());

            proposals.add(new PatchProposal("config/paper-world-defaults.yml",
                "entities.spawning.per-player-mob-spawns",
                "false", "true",
                RiskTag.LOW, ApplyScope.RECOMMEND, "paper-per-player-mob-spawns",
                "Enable per-player mob spawns for fairer distribution (Paper Chan recommended)"));
        }
    }

    /* Prevents sync-chunk-load when player enters unloaded chunk */
    private void checkPreventMovingUnloadedChunks(ConfigAdapter configs,
                                                    List<RuleResult> results, List<PatchProposal> proposals) {
        boolean enabled = configs.getBoolean("config/paper-world-defaults.yml",
            "chunks.prevent-moving-into-unloaded-chunks", true);

        if (!enabled) {
            results.add(RuleResult.builder("paper-prevent-unloaded-chunks")
                .group("fork-specific").severity(Severity.INFO).confidence(0.85)
                .why("Players can move into unloaded chunks, triggering sync chunk loads that tank TPS")
                .impact("Sync-chunk loading is a major cause of lag spikes during fast travel")
                .tradeoff("Players may briefly rubber-band at chunk borders — generally unnoticeable")
                .recommendation("Enable prevent-moving-into-unloaded-chunks: true")
                .manualSteps("In config/paper-world-defaults.yml, set chunks.prevent-moving-into-unloaded-chunks: true")
                .impactedKey("config/paper-world-defaults.yml:chunks.prevent-moving-into-unloaded-chunks")
                .build());

            proposals.add(new PatchProposal("config/paper-world-defaults.yml",
                "chunks.prevent-moving-into-unloaded-chunks",
                "false", "true",
                RiskTag.LOW, ApplyScope.RECOMMEND, "paper-prevent-unloaded-chunks",
                "Prevent sync-load lag spikes from entering unloaded chunks (Paper Chan recommended)"));
        }
    }

    /* max-entity-collisions: 8 default.  Do NOT set below 3 */
    private void checkMaxEntityCollisions(ConfigAdapter configs, HardwareTier tier,
                                           AggressivenessLevel level,
                                           List<RuleResult> results, List<PatchProposal> proposals) {
        int current = configs.getInt("config/paper-world-defaults.yml",
            "collisions.max-entity-collisions", 8);

        int rec = 8;
        if (tier == HardwareTier.LOW && level == AggressivenessLevel.AGGRESSIVE) rec = 4;
        else if (tier == HardwareTier.LOW) rec = 6;
        rec = Math.max(3, rec); // never below 3 per Paper Chan

        if (current < 3) {
            results.add(RuleResult.builder("paper-entity-collisions-unsafe")
                .group("fork-specific").severity(Severity.WARNING).confidence(0.95)
                .why("max-entity-collisions is " + current + " — below safe minimum of 3")
                .impact("Values below 3 break game mechanics that rely on entity collisions")
                .tradeoff("Raising to at least 3 restores Vanilla collision behaviour")
                .recommendation("Set max-entity-collisions to at least 3 (recommended: " + rec + ")")
                .manualSteps("In config/paper-world-defaults.yml, set collisions.max-entity-collisions: " + rec)
                .impactedKey("config/paper-world-defaults.yml:collisions.max-entity-collisions")
                .build());

            proposals.add(new PatchProposal("config/paper-world-defaults.yml",
                "collisions.max-entity-collisions",
                String.valueOf(current), String.valueOf(rec),
                RiskTag.MEDIUM, ApplyScope.RECOMMEND, "paper-entity-collisions-unsafe",
                "Raise entity collisions to safe minimum (Paper Chan: never below 3)"));
        }
    }

    /* fix-climbing-bypassing-cramming-rule: true */
    private void checkFixClimbingBypassCramming(ConfigAdapter configs,
                                                  List<RuleResult> results, List<PatchProposal> proposals) {
        boolean fixed = configs.getBoolean("config/paper-world-defaults.yml",
            "collisions.fix-climbing-bypassing-cramming-rule", false);

        if (!fixed) {
            proposals.add(new PatchProposal("config/paper-world-defaults.yml",
                "collisions.fix-climbing-bypassing-cramming-rule",
                "false", "true",
                RiskTag.LOW, ApplyScope.RECOMMEND, "paper-fix-climbing-cramming",
                "Fix climbing mobs bypassing cramming rules (Paper Chan recommended)"));
        }
    }

    /* optimize-explosions: false by default, true for TNT-heavy servers */
    private void checkOptimizeExplosions(ConfigAdapter configs, GameProfile profile,
                                          List<RuleResult> results, List<PatchProposal> proposals) {
        boolean optimized = configs.getBoolean("config/paper-world-defaults.yml",
            "environment.optimize-explosions", false);

        if (!optimized && (profile == GameProfile.CREATIVE || profile == GameProfile.MINIGAME)) {
            proposals.add(new PatchProposal("config/paper-world-defaults.yml",
                "environment.optimize-explosions",
                "false", "true",
                RiskTag.LOW, ApplyScope.RECOMMEND, "paper-optimize-explosions",
                "Optimize explosion calculations for " + profile.getDisplayName() + " servers"));
        }
    }

    /* treasure-maps: reduce search impact */
    private void checkTreasureMaps(ConfigAdapter configs,
                                    List<RuleResult> results, List<PatchProposal> proposals) {
        boolean villagerTradeFindDiscovered = configs.getBoolean("config/paper-world-defaults.yml",
            "environment.treasure-maps.find-already-discovered.villager-trade", false);

        if (!villagerTradeFindDiscovered) {
            results.add(RuleResult.builder("paper-treasure-maps")
                .group("fork-specific").severity(Severity.INFO).confidence(0.85)
                .why("Treasure map searches up to ~1100 blocks for undiscovered treasures — resource intensive")
                .impact("Large lag spikes when villagers generate treasure maps; can stall the server")
                .tradeoff("Maps may point to already-discovered treasures instead of new ones")
                .recommendation("Set find-already-discovered.villager-trade: true")
                .manualSteps("In config/paper-world-defaults.yml, set " +
                        "environment.treasure-maps.find-already-discovered.villager-trade: true")
                .impactedKey("config/paper-world-defaults.yml:" +
                        "environment.treasure-maps.find-already-discovered.villager-trade")
                .build());

            proposals.add(new PatchProposal("config/paper-world-defaults.yml",
                "environment.treasure-maps.find-already-discovered.villager-trade",
                "false", "true",
                RiskTag.LOW, ApplyScope.RECOMMEND, "paper-treasure-maps",
                "Reduce treasure map lag by allowing already-discovered results (Paper Chan recommended)"));
        }
    }

    /* feature-seeds: randomise to hinder seed-cracking (new worlds only) */
    private void checkFeatureSeeds(ConfigAdapter configs,
                                    List<RuleResult> results, List<PatchProposal> proposals) {
        boolean randomSeeds = configs.getBoolean("config/paper-world-defaults.yml",
            "feature-seeds.generate-random-seeds-for-all", false);

        if (!randomSeeds) {
            results.add(RuleResult.builder("paper-feature-seeds")
                .group("fork-specific").severity(Severity.INFO).confidence(0.75)
                .why("Feature seeds are not randomised — seed-cracking tools can find your world seed")
                .impact("Players can use tools like SeedcrackerX to discover world seed and locate structures")
                .tradeoff("Enable ONLY for new worlds; enabling on existing worlds can cause cut-off " +
                        "structures and break /locate command")
                .recommendation("Enable for NEW worlds. Also manually set structure seeds in spigot.yml")
                .manualSteps("In config/paper-world-defaults.yml, set " +
                        "feature-seeds.generate-random-seeds-for-all: true (new worlds only!)")
                .impactedKey("config/paper-world-defaults.yml:feature-seeds.generate-random-seeds-for-all")
                .build());
        }
    }

    /* delay-chunk-unloads-by: 10s default is optimal */
    private void checkDelayChunkUnloads(ConfigAdapter configs,
                                         List<RuleResult> results, List<PatchProposal> proposals) {
        String delay = configs.getString("config/paper-world-defaults.yml",
            "chunks.delay-chunk-unloads-by", "10s");

        if ("0s".equals(delay) || "0".equals(delay) || "1s".equals(delay)) {
            results.add(RuleResult.builder("paper-chunk-unload-delay")
                .group("fork-specific").severity(Severity.INFO).confidence(0.8)
                .why("Chunk unload delay is very low (" + delay + ") — causes excessive re-loading")
                .impact("Server wastes resources re-loading chunks that were just unloaded")
                .tradeoff("10s default provides a good balance between memory usage and avoiding re-loads")
                .recommendation("Set delay-chunk-unloads-by: 10s (the default)")
                .manualSteps("In config/paper-world-defaults.yml, set chunks.delay-chunk-unloads-by: 10s")
                .impactedKey("config/paper-world-defaults.yml:chunks.delay-chunk-unloads-by")
                .build());

            proposals.add(new PatchProposal("config/paper-world-defaults.yml",
                "chunks.delay-chunk-unloads-by",
                delay, "10s",
                RiskTag.LOW, ApplyScope.RECOMMEND, "paper-chunk-unload-delay",
                "Restore chunk unload delay to 10s to avoid wasteful re-loading"));
        }
    }

    /* max-auto-save-chunks-per-tick: 24 default is usually optimal */
    private void checkMaxAutoSaveChunks(ConfigAdapter configs,
                                         List<RuleResult> results, List<PatchProposal> proposals) {
        int current = configs.getInt("config/paper-world-defaults.yml",
            "chunks.max-auto-save-chunks-per-tick", 24);

        if (current != 24 && current > 0) {
            results.add(RuleResult.builder("paper-auto-save-chunks")
                .group("fork-specific").severity(Severity.INFO).confidence(0.7)
                .why("max-auto-save-chunks-per-tick is " + current + " (default: 24)")
                .impact("Incorrect values can cause performance loss or data loss")
                .tradeoff("The default value of 24 is most optimal for the majority of servers")
                .recommendation("Keep at 24 unless you fully understand the chunk save pipeline")
                .manualSteps("In config/paper-world-defaults.yml, set chunks.max-auto-save-chunks-per-tick: 24")
                .impactedKey("config/paper-world-defaults.yml:chunks.max-auto-save-chunks-per-tick")
                .build());
        }
    }

    /* alt-item-despawn-rate: enable with junk items for faster cleanup */
    private void checkAltItemDespawnRate(ConfigAdapter configs,
                                          List<RuleResult> results, List<PatchProposal> proposals) {
        boolean enabled = configs.getBoolean("config/paper-world-defaults.yml",
            "entities.spawning.alt-item-despawn-rate.enabled", false);

        if (!enabled) {
            results.add(RuleResult.builder("paper-alt-item-despawn")
                .group("fork-specific").severity(Severity.INFO).confidence(0.85)
                .why("alt-item-despawn-rate is disabled — junk items persist for full 5-minute despawn timer")
                .impact("Cobblestone, rotten flesh, and other junk from farms pile up, wasting entity slots")
                .tradeoff("Junk items despawn faster; valuable items keep full 5-minute timer")
                .recommendation("Enable with recommended junk items: cobblestone: 600, netherrack: 600, " +
                        "rotten_flesh: 900, cactus: 900, egg: 900, etc.")
                .manualSteps("In config/paper-world-defaults.yml:\n" +
                        "entities.spawning.alt-item-despawn-rate:\n" +
                        "  enabled: true\n" +
                        "  items:\n" +
                        "    cobblestone: 600\n" +
                        "    cobbled_deepslate: 600\n" +
                        "    netherrack: 600\n" +
                        "    rotten_flesh: 900\n" +
                        "    leather: 900\n" +
                        "    bone: 1200\n" +
                        "    bone_meal: 1200\n" +
                        "    cactus: 900\n" +
                        "    egg: 900\n" +
                        "    feather: 900\n" +
                        "    gunpowder: 1200\n" +
                        "    arrow: 900\n" +
                        "    blaze_rod: 1200\n" +
                        "    string: 1200\n" +
                        "    ink_sac: 900\n" +
                        "    slime_ball: 1200\n" +
                        "    phantom_membrane: 900")
                .impactedKey("config/paper-world-defaults.yml:entities.spawning.alt-item-despawn-rate.enabled")
                .build());

            proposals.add(new PatchProposal("config/paper-world-defaults.yml",
                "entities.spawning.alt-item-despawn-rate.enabled",
                "false", "true",
                RiskTag.LOW, ApplyScope.RECOMMEND, "paper-alt-item-despawn",
                "Enable alt-item-despawn to clean up junk items faster (Paper Chan recommended)"));
        }
    }

    /* entity-per-chunk-save-limit: prevent chunk-load stalls from projectiles */
    private void checkEntityPerChunkSaveLimit(ConfigAdapter configs,
                                               List<RuleResult> results, List<PatchProposal> proposals) {
        int arrowLimit = configs.getInt("config/paper-world-defaults.yml",
            "chunks.entity-per-chunk-save-limit.arrow", -1);

        if (arrowLimit < 0) {
            results.add(RuleResult.builder("paper-entity-chunk-save-limit")
                .group("fork-specific").severity(Severity.INFO).confidence(0.9)
                .why("entity-per-chunk-save-limit is not configured — chunks with many projectiles can stall on load")
                .impact("Players can fire many projectiles into a chunk, causing server stalls when that chunk loads")
                .tradeoff("Limits how many of each projectile entity are saved per chunk; excess are discarded on save")
                .recommendation("Set limits for projectile entities to prevent chunk-load stalls")
                .manualSteps("In config/paper-world-defaults.yml, set:\n" +
                        "chunks.entity-per-chunk-save-limit:\n" +
                        "  experience_orb: 50\n" +
                        "  snowball: 20\n" +
                        "  ender_pearl: 20\n" +
                        "  arrow: 20\n" +
                        "  fireball: 10\n" +
                        "  small_fireball: 10\n" +
                        "  dragon_fireball: 5\n" +
                        "  egg: 20\n" +
                        "  area_effect_cloud: 10\n" +
                        "  llama_spit: 5\n" +
                        "  shulker_bullet: 8\n" +
                        "  spectral_arrow: 5\n" +
                        "  experience_bottle: 5\n" +
                        "  trident: 10\n" +
                        "  wither_skull: 10\n" +
                        "  splash_potion: 10")
                .impactedKey("config/paper-world-defaults.yml:chunks.entity-per-chunk-save-limit.arrow")
                .build());
        }
    }

    /* despawn-time: auto-despawn lingering projectiles */
    private void checkDespawnTime(ConfigAdapter configs,
                                   List<RuleResult> results, List<PatchProposal> proposals) {
        int arrowDespawn = configs.getInt("config/paper-world-defaults.yml",
            "entities.spawning.despawn-time.arrow", -1);

        if (arrowDespawn < 0) {
            results.add(RuleResult.builder("paper-despawn-time")
                .group("fork-specific").severity(Severity.INFO).confidence(0.8)
                .why("despawn-time is not set for projectile entities — they persist indefinitely")
                .impact("Lingering projectiles accumulate over time, especially from mob farms or combat")
                .tradeoff("Projectiles will automatically despawn after the configured time")
                .recommendation("Set reasonable despawn times for projectiles and throwables")
                .manualSteps("In config/paper-world-defaults.yml, set:\n" +
                        "entities.spawning.despawn-time:\n" +
                        "  llama_spit: 1200\n" +
                        "  snowball: 1200\n" +
                        "  fireball: 1200\n" +
                        "  dragon_fireball: 1200\n" +
                        "  small_fireball: 1200\n" +
                        "  arrow: 3000\n" +
                        "  shulker_bullet: 3000\n" +
                        "  wither_skull: 3000\n" +
                        "  trident: 3000")
                .impactedKey("config/paper-world-defaults.yml:entities.spawning.despawn-time.arrow")
                .build());
        }
    }

    /* despawn-ranges: hard.horizontal should be (sim-dist - 1) * 16 */
    private void checkDespawnRanges(ConfigAdapter configs, HardwareTier tier,
                                     List<RuleResult> results, List<PatchProposal> proposals) {
        int simDist = configs.getInt("server.properties", "simulation-distance", 10);

        if (simDist < 10) {
            int recHardHorizontal = (simDist - 1) * 16;

            results.add(RuleResult.builder("paper-despawn-ranges")
                .group("fork-specific").severity(Severity.WARNING).confidence(0.9)
                .why("Simulation distance is " + simDist + " (below default 10) — " +
                     "despawn-ranges.hard.horizontal should be adjusted")
                .impact("Without adjustment, entities at the border of simulation distance won't despawn properly, " +
                        "wasting the mob cap. Hard horizontal should be " + recHardHorizontal + " blocks")
                .tradeoff("Keep vertical at default 128 so AFK spots for farms still work like vanilla")
                .recommendation("Set despawn-ranges.monster.hard.horizontal: " + recHardHorizontal)
                .manualSteps("In config/paper-world-defaults.yml:\n" +
                        "entities.spawning.despawn-ranges:\n" +
                        "  monster:\n" +
                        "    hard:\n" +
                        "      horizontal: " + recHardHorizontal + "\n" +
                        "      vertical: default\n" +
                        "    soft: default")
                .impactedKey("config/paper-world-defaults.yml:entities.spawning.despawn-ranges.monster.hard")
                .build());

            proposals.add(new PatchProposal("config/paper-world-defaults.yml",
                "entities.spawning.despawn-ranges.monster.hard.horizontal",
                "default", String.valueOf(recHardHorizontal),
                RiskTag.MEDIUM, ApplyScope.RECOMMEND, "paper-despawn-ranges",
                "Align monster hard despawn range with sim-dist " + simDist + " (Paper Chan recommended)"));
        }
    }

    /* Villager tick-rate tuning: secondarypoisensor and validatenearbypoi */
    private void checkVillagerTickRates(ConfigAdapter configs, HardwareTier tier,
                                         AggressivenessLevel level,
                                         List<RuleResult> results, List<PatchProposal> proposals) {
        int secondaryPoi = configs.getInt("config/paper-world-defaults.yml",
            "tick-rates.sensor.villager.secondarypoisensor", 40);

        // Paper Chan: can increase to 240 for performance-constrained servers
        if (tier == HardwareTier.LOW || level == AggressivenessLevel.AGGRESSIVE) {
            int recSecondary = 240;
            int recValidate = 120;

            if (secondaryPoi < recSecondary) {
                results.add(RuleResult.builder("paper-villager-tick-rates")
                    .group("fork-specific").severity(Severity.INFO).confidence(0.8)
                    .why("Villager POI sensor rates are at default — can be raised to reduce tick cost")
                    .impact("Villagers check for workstations and secondary POIs less frequently, saving CPU")
                    .tradeoff("Villagers may take slightly longer to find workstations or update behaviour")
                    .recommendation("Set secondarypoisensor: " + recSecondary +
                            " and validatenearbypoi: " + recValidate)
                    .manualSteps("In config/paper-world-defaults.yml:\n" +
                            "tick-rates:\n" +
                            "  sensor.villager.secondarypoisensor: " + recSecondary + "\n" +
                            "  behavior.villager.validatenearbypoi: " + recValidate)
                    .impactedKey("config/paper-world-defaults.yml:tick-rates.sensor.villager.secondarypoisensor")
                    .build());

                proposals.add(new PatchProposal("config/paper-world-defaults.yml",
                    "tick-rates.sensor.villager.secondarypoisensor",
                    String.valueOf(secondaryPoi), String.valueOf(recSecondary),
                    RiskTag.LOW, ApplyScope.RECOMMEND, "paper-villager-tick-rates",
                    "Increase villager POI sensor interval to reduce CPU usage (Paper Chan recommended)"));
            }
        }
    }

    /* armor-stands: keep tick: true and do-collision-entity-lookups: true */
    private void checkArmorStands(ConfigAdapter configs,
                                   List<RuleResult> results, List<PatchProposal> proposals) {
        boolean tick = configs.getBoolean("config/paper-world-defaults.yml",
            "entities.armor-stands.tick", true);
        boolean collision = configs.getBoolean("config/paper-world-defaults.yml",
            "entities.armor-stands.do-collision-entity-lookups", true);

        if (!tick || !collision) {
            results.add(RuleResult.builder("paper-armor-stands")
                .group("fork-specific").severity(Severity.WARNING).confidence(0.9)
                .why("Armor stand ticking or collision lookups are disabled")
                .impact("Disabling these breaks: armor stand plugins, automatic ice makers, " +
                        "and removes armor stand lag machine protection")
                .tradeoff("Enabling costs minimal performance; disabling saves little but breaks much")
                .recommendation("Keep entities.armor-stands.tick: true and do-collision-entity-lookups: true")
                .manualSteps("In config/paper-world-defaults.yml:\n" +
                        "entities.armor-stands:\n  tick: true\n  do-collision-entity-lookups: true")
                .impactedKey("config/paper-world-defaults.yml:entities.armor-stands.tick")
                .build());
        }
    }

    /* tracking-range-y: enable for vertical entity visibility control */
    private void checkTrackingRangeY(ConfigAdapter configs,
                                      List<RuleResult> results, List<PatchProposal> proposals) {
        boolean enabled = configs.getBoolean("config/paper-world-defaults.yml",
            "entities.tracking-range-y.enabled", false);

        if (!enabled) {
            proposals.add(new PatchProposal("config/paper-world-defaults.yml",
                "entities.tracking-range-y.enabled",
                "false", "true",
                RiskTag.LOW, ApplyScope.RECOMMEND, "paper-tracking-range-y",
                "Enable vertical tracking range for finer entity visibility control (Paper feature)"));
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  Paper — paper-global.yml  (sourced from paper-chan.moe)
    // ──────────────────────────────────────────────────────────────────

    private void evaluatePaperGlobal(ConfigAdapter configs, HardwareTier tier,
                                      AggressivenessLevel level,
                                      List<RuleResult> results, List<PatchProposal> proposals) {
        if (!configs.isPresent("config/paper-global.yml")) return;

        checkChunkSystem(configs, results, proposals);
        checkBookValidation(configs, results, proposals);
    }

    /* chunk-system: defaults are optimal, warn against manual overrides */
    private void checkChunkSystem(ConfigAdapter configs,
                                   List<RuleResult> results, List<PatchProposal> proposals) {
        int workerThreads = configs.getInt("config/paper-global.yml",
            "chunk-system.worker-threads", -1);
        int ioThreads = configs.getInt("config/paper-global.yml",
            "chunk-system.io-threads", -1);

        if (workerThreads > 0 || ioThreads > 0) {
            results.add(RuleResult.builder("paper-chunk-system-overridden")
                .group("fork-specific").severity(Severity.WARNING).confidence(0.8)
                .why("Chunk system threads have been manually overridden from defaults")
                .impact("Manual values may negatively impact performance. " +
                        "Default (-1 = auto) is most optimal for the majority of servers")
                .tradeoff("Setting back to -1 lets Paper auto-detect the optimal thread counts")
                .recommendation("Set worker-threads: -1 and io-threads: -1 (auto-detect)")
                .manualSteps("In config/paper-global.yml:\nchunk-system:\n  worker-threads: -1\n  io-threads: -1")
                .impactedKey("config/paper-global.yml:chunk-system.worker-threads")
                .build());
        }
    }

    /* Book validation: reduce page-max to prevent bookban */
    private void checkBookValidation(ConfigAdapter configs,
                                      List<RuleResult> results, List<PatchProposal> proposals) {
        int pageMax = configs.getInt("config/paper-global.yml",
            "item-validation.book-size.page-max", 2560);

        if (pageMax > 1280) {
            results.add(RuleResult.builder("paper-book-validation")
                .group("fork-specific").severity(Severity.INFO).confidence(0.8)
                .why("Book page-max is " + pageMax + " bytes — can be reduced to prevent book bans")
                .impact("Large books can be used for griefing (bookban exploit)")
                .tradeoff("Smaller page-max limits what players can write in books; 640-1280 is safe")
                .recommendation("Reduce page-max to 1280 or lower")
                .manualSteps("In config/paper-global.yml, set item-validation.book-size.page-max: 1280")
                .impactedKey("config/paper-global.yml:item-validation.book-size.page-max")
                .build());

            proposals.add(new PatchProposal("config/paper-global.yml",
                "item-validation.book-size.page-max",
                String.valueOf(pageMax), "1280",
                RiskTag.LOW, ApplyScope.RECOMMEND, "paper-book-validation",
                "Reduce book page size to mitigate bookban exploit (Paper Chan recommended)"));
        }

        boolean resolveSelectors = configs.getBoolean("config/paper-global.yml",
            "item-validation.resolve-selectors-in-books", false);
        if (resolveSelectors) {
            proposals.add(new PatchProposal("config/paper-global.yml",
                "item-validation.resolve-selectors-in-books",
                "true", "false",
                RiskTag.LOW, ApplyScope.RECOMMEND, "paper-book-selectors",
                "Disable selectors in books for security"));
        }
    }

    private void evaluatePurpur(ConfigAdapter configs, HardwareTier tier, AggressivenessLevel level,
                                 List<RuleResult> results, List<PatchProposal> proposals) {
        if (!configs.isPresent("purpur.yml")) return;

        // Purpur villager lobotomize
        boolean lobotomize = configs.getBoolean("purpur.yml",
            "world-settings.default.mobs.villager.lobotomize.enabled", false);

        if (!lobotomize && (tier == HardwareTier.LOW || level == AggressivenessLevel.AGGRESSIVE)) {
            results.add(RuleResult.builder("purpur-villager-lobotomize")
                .group("fork-specific").severity(Severity.INFO).confidence(0.85)
                .why("Purpur's villager lobotomize feature is disabled")
                .impact("Trading halls with many villagers cause significant lag from AI ticking. " +
                        "Paper Chan recommends VillagerLobotimizer or Purpur's built-in lobotomize")
                .tradeoff("Lobotomised villagers lose some AI but trades still work normally")
                .recommendation("Enable lobotomize for villagers in trading halls")
                .manualSteps("In purpur.yml, set world-settings.default.mobs.villager.lobotomize.enabled: true")
                .impactedKey("purpur.yml:world-settings.default.mobs.villager.lobotomize.enabled")
                .build());

            proposals.add(new PatchProposal("purpur.yml",
                "world-settings.default.mobs.villager.lobotomize.enabled",
                "false", "true",
                RiskTag.MEDIUM, ApplyScope.RECOMMEND, "purpur-villager-lobotomize",
                "Enable Purpur villager lobotomization for better performance"));
        }

        // Purpur entity teleport-if-outside-border
        results.add(RuleResult.builder("purpur-teleport-outside-border")
            .group("fork-specific").severity(Severity.INFO).confidence(0.7)
            .why("Purpur can teleport entities inside world border automatically")
            .impact("Prevents entity accumulation outside world border")
            .tradeoff("Minimal — generally safe to enable")
            .recommendation("Enable if not already set")
            .manualSteps("In purpur.yml, check world-settings.default.gameplay-mechanics.entity-teleport-to-spawn-if-outside-border")
            .build());
    }

    private void evaluatePufferfish(ConfigAdapter configs, HardwareTier tier, AggressivenessLevel level,
                                     List<RuleResult> results, List<PatchProposal> proposals) {
        if (!configs.isPresent("pufferfish.yml")) return;

        // DAB settings
        boolean dabEnabled = configs.getBoolean("pufferfish.yml", "dab.enabled", true);
        int dabRange = configs.getInt("pufferfish.yml", "dab.start-distance", 12);

        int recRange;
        switch (tier) {
            case LOW:  recRange = 8; break;
            case HIGH: recRange = 16; break;
            default:   recRange = 12; break;
        }

        if (dabEnabled && dabRange != recRange) {
            results.add(RuleResult.builder("pufferfish-dab-range")
                .group("fork-specific").severity(Severity.INFO).confidence(0.8)
                .why("Pufferfish DAB start distance is " + dabRange + ", recommended " + recRange + " for " + tier.getDisplayName())
                .impact("Controls at what distance entity AI begins to be skipped")
                .tradeoff("Lower = more aggressive AI skipping, higher = more natural mob behavior")
                .recommendation("Set dab.start-distance=" + recRange)
                .manualSteps("In pufferfish.yml, set dab.start-distance: " + recRange)
                .impactedKey("pufferfish.yml:dab.start-distance")
                .build());

            proposals.add(new PatchProposal("pufferfish.yml", "dab.start-distance",
                String.valueOf(dabRange), String.valueOf(recRange),
                RiskTag.LOW, ApplyScope.RECOMMEND, "pufferfish-dab-range",
                "Tune Pufferfish DAB range for " + tier.getDisplayName()));
        }
    }

    private void evaluateLeaf(ConfigAdapter configs, HardwareTier tier, AggressivenessLevel level,
                               List<RuleResult> results, List<PatchProposal> proposals) {
        if (!configs.isPresent("leaves.yml")) return;

        results.add(RuleResult.builder("leaf-optimizations")
            .group("fork-specific").severity(Severity.INFO).confidence(0.75)
            .why("Leaf server detected — additional optimizations available")
            .impact("Leaf includes extra performance patches beyond Paper/Purpur")
            .tradeoff("Some Leaf optimizations may change vanilla behavior")
            .recommendation("Review Leaf-specific settings in leaves.yml for your use case")
            .manualSteps("Check leaves.yml for performance settings like async pathfinding and entity optimizations")
            .build());
    }

    private void evaluateLuminol(List<RuleResult> results) {
        results.add(RuleResult.builder("luminol-detected")
            .group("fork-specific").severity(Severity.INFO).confidence(0.9)
            .why("Luminol server detected — this is a Folia-based fork with additional Paper features")
            .impact("Luminol inherits Folia's threaded region scheduler; plugins must be Folia-compatible")
            .tradeoff("Not all Paper plugins are compatible with Folia's regionized threading model")
            .recommendation("Verify all plugins are Folia-compatible. Luminol also supports extra features — check its documentation")
            .manualSteps("See https://github.com/LuminolMC/Luminol for Luminol-specific configuration options")
            .build());
    }
}
