package com.lesslag.setup.rules;

import com.lesslag.setup.detect.ConfigAdapter;
import com.lesslag.setup.detect.PlatformDetector;
import com.lesslag.setup.detect.PluginScanner;
import com.lesslag.setup.model.*;

import java.util.List;

/**
 * Performance tuning rules: LessLag module settings tuned for profile + tier.
 * Lower priority than safety/consistency — these are QoL / optimization.
 */
public class PerformanceTuningRules implements Rule {

    @Override public String getId() { return "perf-tuning"; }
    @Override public String getGroup() { return "performance"; }
    @Override public int getPriority() { return 50; }

    @Override
    public void evaluate(PlatformDetector platform, ConfigAdapter configs, PluginScanner plugins,
                          HardwareAssessment hardware, GameProfile profile, HardwareTier tier,
                          AggressivenessLevel level, List<RuleResult> results, List<PatchProposal> proposals) {

        tuneFrustumCulling(tier, level, profile, results, proposals);
        tuneDensityOptimizer(tier, level, profile, results, proposals);
        tuneVillagerOptimizer(tier, level, profile, results, proposals);
        tuneTPSThresholds(tier, level, results, proposals);
        tuneWorkloadBudget(tier, level, results, proposals);
    }

    private void tuneFrustumCulling(HardwareTier tier, AggressivenessLevel level,
                                     GameProfile profile,
                                     List<RuleResult> results, List<PatchProposal> proposals) {
        int recRadius;
        int recInterval;
        switch (tier) {
            case LOW:
                recRadius = 28;
                recInterval = 20;
                break;
            case HIGH:
                recRadius = 48;
                recInterval = 40;
                break;
            default:
                recRadius = 40;
                recInterval = 30;
                break;
        }
        if (level == AggressivenessLevel.AGGRESSIVE) {
            recRadius = (int) (recRadius * 0.7);
        }

        proposals.add(new PatchProposal("config.yml", "modules.mob-ai.active-radius",
            "40", String.valueOf(recRadius),
            RiskTag.LOW, ApplyScope.LESSLAG_APPLY, "perf-frustum-radius",
            "Tune AI culling radius for " + tier.getDisplayName()));

        proposals.add(new PatchProposal("config.yml", "modules.mob-ai.update-interval",
            "30", String.valueOf(recInterval),
            RiskTag.LOW, ApplyScope.LESSLAG_APPLY, "perf-frustum-interval",
            "Tune AI culling interval for " + tier.getDisplayName()));
    }

    private void tuneDensityOptimizer(HardwareTier tier, AggressivenessLevel level,
                                       GameProfile profile,
                                       List<RuleResult> results, List<PatchProposal> proposals) {
        // Density limits by profile
        int cowLimit, sheepLimit, pigLimit, chickenLimit, villagerLimit;
        switch (profile) {
            case SKYBLOCK:
                cowLimit = 8; sheepLimit = 8; pigLimit = 8; chickenLimit = 12; villagerLimit = 15;
                break;
            case MINIGAME:
                cowLimit = 15; sheepLimit = 15; pigLimit = 15; chickenLimit = 20; villagerLimit = 25;
                break;
            case CREATIVE:
                cowLimit = 20; sheepLimit = 20; pigLimit = 20; chickenLimit = 25; villagerLimit = 30;
                break;
            default: // SMP
                cowLimit = 10; sheepLimit = 10; pigLimit = 10; chickenLimit = 15; villagerLimit = 20;
                break;
        }

        // Scale by tier
        if (tier == HardwareTier.LOW) {
            cowLimit = (int) (cowLimit * 0.7);
            sheepLimit = (int) (sheepLimit * 0.7);
            pigLimit = (int) (pigLimit * 0.7);
            chickenLimit = (int) (chickenLimit * 0.7);
            villagerLimit = (int) (villagerLimit * 0.7);
        } else if (tier == HardwareTier.HIGH) {
            cowLimit = (int) (cowLimit * 1.3);
            sheepLimit = (int) (sheepLimit * 1.3);
            pigLimit = (int) (pigLimit * 1.3);
            chickenLimit = (int) (chickenLimit * 1.3);
            villagerLimit = (int) (villagerLimit * 1.3);
        }

        if (level == AggressivenessLevel.AGGRESSIVE) {
            cowLimit = Math.max(5, (int) (cowLimit * 0.6));
            sheepLimit = Math.max(5, (int) (sheepLimit * 0.6));
            pigLimit = Math.max(5, (int) (pigLimit * 0.6));
            chickenLimit = Math.max(5, (int) (chickenLimit * 0.6));
            villagerLimit = Math.max(8, (int) (villagerLimit * 0.6));
        }

        results.add(RuleResult.builder("perf-density-tuning")
            .group("performance").severity(Severity.INFO).confidence(0.85)
            .why("Density optimizer limits tuned for " + profile.getDisplayName() + " / " + tier.getDisplayName())
            .impact("Controls how many same-type entities per chunk before AI is disabled")
            .tradeoff("Lower limits = better TPS but less natural mob behavior in farms")
            .recommendation("Apply recommended density limits")
            .build());

        proposals.add(new PatchProposal("config.yml", "modules.density-optimizer.limits.COW",
            "10", String.valueOf(cowLimit), RiskTag.LOW, ApplyScope.LESSLAG_APPLY,
            "perf-density-tuning", "Density limit for cows"));
        proposals.add(new PatchProposal("config.yml", "modules.density-optimizer.limits.SHEEP",
            "10", String.valueOf(sheepLimit), RiskTag.LOW, ApplyScope.LESSLAG_APPLY,
            "perf-density-tuning", "Density limit for sheep"));
        proposals.add(new PatchProposal("config.yml", "modules.density-optimizer.limits.PIG",
            "10", String.valueOf(pigLimit), RiskTag.LOW, ApplyScope.LESSLAG_APPLY,
            "perf-density-tuning", "Density limit for pigs"));
        proposals.add(new PatchProposal("config.yml", "modules.density-optimizer.limits.CHICKEN",
            "15", String.valueOf(chickenLimit), RiskTag.LOW, ApplyScope.LESSLAG_APPLY,
            "perf-density-tuning", "Density limit for chickens"));
        proposals.add(new PatchProposal("config.yml", "modules.density-optimizer.limits.VILLAGER",
            "20", String.valueOf(villagerLimit), RiskTag.LOW, ApplyScope.LESSLAG_APPLY,
            "perf-density-tuning", "Density limit for villagers"));
    }

    private void tuneVillagerOptimizer(HardwareTier tier, AggressivenessLevel level,
                                        GameProfile profile,
                                        List<RuleResult> results, List<PatchProposal> proposals) {
        int recRestoreDuration;
        switch (tier) {
            case LOW:  recRestoreDuration = 15; break;
            case HIGH: recRestoreDuration = 45; break;
            default:   recRestoreDuration = 30; break;
        }

        proposals.add(new PatchProposal("config.yml", "modules.villager-optimizer.ai-restore-duration",
            "30", String.valueOf(recRestoreDuration),
            RiskTag.LOW, ApplyScope.LESSLAG_APPLY, "perf-villager",
            "Tune villager AI restore duration for " + tier.getDisplayName()));
    }

    private void tuneTPSThresholds(HardwareTier tier, AggressivenessLevel level,
                                    List<RuleResult> results, List<PatchProposal> proposals) {
        // On low-end, trigger actions earlier
        double recMinor, recModerate, recCritical;
        switch (tier) {
            case LOW:
                recMinor = 18.5; recModerate = 16.0; recCritical = 12.0;
                break;
            case HIGH:
                recMinor = 17.5; recModerate = 14.0; recCritical = 9.0;
                break;
            default:
                recMinor = 18.0; recModerate = 15.0; recCritical = 10.0;
                break;
        }

        proposals.add(new PatchProposal("config.yml", "automation.thresholds.minor.tps",
            "18.0", String.valueOf(recMinor),
            RiskTag.LOW, ApplyScope.LESSLAG_APPLY, "perf-thresholds",
            "Tune minor TPS threshold for " + tier.getDisplayName()));
        proposals.add(new PatchProposal("config.yml", "automation.thresholds.moderate.tps",
            "15.0", String.valueOf(recModerate),
            RiskTag.LOW, ApplyScope.LESSLAG_APPLY, "perf-thresholds",
            "Tune moderate TPS threshold for " + tier.getDisplayName()));
        proposals.add(new PatchProposal("config.yml", "automation.thresholds.critical.tps",
            "10.0", String.valueOf(recCritical),
            RiskTag.MEDIUM, ApplyScope.LESSLAG_APPLY, "perf-thresholds",
            "Tune critical TPS threshold for " + tier.getDisplayName()));
    }

    private void tuneWorkloadBudget(HardwareTier tier, AggressivenessLevel level,
                                     List<RuleResult> results, List<PatchProposal> proposals) {
        double recBudget;
        switch (tier) {
            case LOW:  recBudget = 1.0; break;
            case HIGH: recBudget = 3.0; break;
            default:   recBudget = 2.0; break;
        }

        proposals.add(new PatchProposal("config.yml", "workload-limit-ms",
            "2", String.valueOf(recBudget),
            RiskTag.LOW, ApplyScope.LESSLAG_APPLY, "perf-workload",
            "Tune workload distributor budget for " + tier.getDisplayName()));
    }
}
