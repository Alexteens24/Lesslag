package com.lesslag.setup.rules;

import com.lesslag.setup.detect.ConfigAdapter;
import com.lesslag.setup.detect.PlatformDetector;
import com.lesslag.setup.detect.PluginScanner;
import com.lesslag.setup.model.*;

import java.util.List;
import java.util.Map;

/**
 * Conflict rules detect plugin overlap and problematic plugins.
 * Paper Chan strongly recommends against: mob stackers, ClearLag, ETF,
 * silktouch spawners, and anti-Fabric plugins.
 * Source: https://paper-chan.moe/paper-optimization/
 */
public class ConflictRules implements Rule {

    @Override public String getId() { return "conflict"; }
    @Override public String getGroup() { return "conflict"; }
    @Override public int getPriority() { return 30; }

    @Override
    public void evaluate(PlatformDetector platform, ConfigAdapter configs, PluginScanner plugins,
                          HardwareAssessment hardware, GameProfile profile, HardwareTier tier,
                          AggressivenessLevel level, List<RuleResult> results, List<PatchProposal> proposals) {

        Map<String, String> conflicts = plugins.getDetectedConflicts();
        if (conflicts.isEmpty()) return;

        for (Map.Entry<String, String> entry : conflicts.entrySet()) {
            String pluginName = entry.getKey();
            String description = entry.getValue();

            handleConflict(pluginName, description, results, proposals);
        }
    }

    private void handleConflict(String pluginName, String description,
                                 List<RuleResult> results, List<PatchProposal> proposals) {
        String lowerName = pluginName.toLowerCase();

        if (lowerName.contains("clearlag") || lowerName.contains("lagg") || lowerName.contains("entitytrackerfixer")) {
            results.add(RuleResult.builder("conflict-clearlag")
                .group("conflict").severity(Severity.WARNING).confidence(0.95)
                .why(pluginName + " is installed — Paper Chan strongly recommends against this type of plugin")
                .impact("Entity-clearing plugins mask the root cause of lag instead of fixing it. " +
                        "ClearLag/ETF cause permanent entity brain damage, break mob AI, " +
                        "and remove named/tamed mobs. Fix the root cause instead")
                .tradeoff("Remove the plugin and address the actual cause of entity accumulation " +
                        "using spawn-limits, alt-item-despawn-rate, and entity-per-chunk-save-limit")
                .recommendation("Remove " + pluginName + " entirely. Use LessLag + proper config tuning instead")
                .manualSteps("Remove " + pluginName + ". In bukkit.yml, tune spawn-limits. " +
                        "In paper-world-defaults.yml, enable alt-item-despawn-rate")
                .impactedKey("compatibility.plugins.clearlag")
                .build());

            proposals.add(new PatchProposal("config.yml",
                "compatibility.plugins.clearlag", "true", "true",
                RiskTag.LOW, ApplyScope.LESSLAG_APPLY, "conflict-clearlag",
                "Enable LessLag ClearLag compatibility mode (already default)"));

        } else if (lowerName.contains("pufferfish")) {
            results.add(RuleResult.builder("conflict-pufferfish-dab")
                .group("conflict").severity(Severity.INFO).confidence(0.85)
                .why("Pufferfish DAB (Distance-based AI Batching) overlaps with LessLag's frustum culling")
                .impact("Both systems try to optimize mob AI, potentially conflicting")
                .tradeoff("LessLag's frustum culler offers FOV-based culling; DAB uses distance-only")
                .recommendation("Let LessLag handle AI optimization and disable DAB, or vice versa")
                .impactedKey("compatibility.plugins.pufferfish-dab")
                .build());

            proposals.add(new PatchProposal("config.yml",
                "compatibility.plugins.pufferfish-dab", "true", "true",
                RiskTag.LOW, ApplyScope.LESSLAG_APPLY, "conflict-pufferfish-dab",
                "Enable Pufferfish DAB compatibility mode"));

        } else if (lowerName.contains("farmcontrol") || lowerName.contains("mobfarmmanager")) {
            results.add(RuleResult.builder("conflict-farm-" + lowerName)
                .group("conflict").severity(Severity.WARNING).confidence(0.85)
                .why(pluginName + " manages farm limits alongside LessLag's breeding limiter and density optimizer")
                .impact("Duplicate farm management can cause unexpected entity removal")
                .tradeoff("Choose one farm management solution for predictable behavior")
                .recommendation("Disable " + pluginName + "'s farm limits or disable LessLag's density-optimizer/breeding-limiter")
                .manualSteps("Check " + pluginName + " config to disable overlapping features")
                .build());

        } else if (lowerName.contains("stackmob") || lowerName.contains("wildstacker")
                    || lowerName.contains("rosestacker") || lowerName.contains("mobstacker")
                    || lowerName.contains("ultimatestacker")) {
            results.add(RuleResult.builder("conflict-stacker-" + lowerName)
                .group("conflict").severity(Severity.WARNING).confidence(0.9)
                .why(pluginName + " is a mob stacking plugin — Paper Chan says this is an inherently flawed idea")
                .impact("Mob stackers never let the server reach the mob cap because stacked mobs count as 1, " +
                        "so the server continuously tries to spawn new mobs. This INCREASES lag instead " +
                        "of reducing it. Also causes issues with LessLag's entity counting")
                .tradeoff("Remove the stacker and reduce spawn-limits in bukkit.yml instead. " +
                        "This is the proper way to control mob counts")
                .recommendation("Remove " + pluginName + " and set spawn-limits.monsters to 35 in bukkit.yml")
                .manualSteps("Remove " + pluginName + ". In bukkit.yml, reduce spawn-limits.monsters")
                .build());

        } else if (lowerName.contains("silkspawner") || lowerName.contains("minerspawner")
                    || lowerName.contains("spawnersilk") || lowerName.contains("pickupspawner")) {
            results.add(RuleResult.builder("conflict-silktouch-spawner")
                .group("conflict").severity(Severity.WARNING).confidence(0.85)
                .why(pluginName + " allows players to move spawners — Paper Chan: these are built-in lag machines")
                .impact("Players can create massive spawner farms that generate huge entity counts " +
                        "and overwhelm entity ticking. If using, set nerf-spawner-mobs: true in spigot.yml")
                .tradeoff("If you must keep this plugin, enable nerf-spawner-mobs in spigot.yml " +
                        "and use LessLag's density-optimizer to limit farm output")
                .recommendation("Remove the plugin or set nerf-spawner-mobs: true in spigot.yml")
                .manualSteps("In spigot.yml, set world-settings.default.nerf-spawner-mobs: true")
                .build());

        } else if (lowerName.contains("antifabric") || lowerName.contains("nofabric")
                    || lowerName.contains("fabricblock")) {
            results.add(RuleResult.builder("conflict-antifabric")
                .group("conflict").severity(Severity.INFO).confidence(0.8)
                .why(pluginName + " is an anti-Fabric plugin — Paper Chan recommends removing these")
                .impact("Anti-Fabric plugins only block legitimate users like Fabric mod users. " +
                        "Cheat clients bypass these detections trivially")
                .tradeoff("Remove the plugin; it provides no real security benefit")
                .recommendation("Remove " + pluginName + " — use a proper anti-cheat instead")
                .build());

        } else {
            // Generic conflict
            results.add(RuleResult.builder("conflict-" + lowerName)
                .group("conflict").severity(Severity.INFO).confidence(0.7)
                .why(pluginName + " detected: " + description)
                .impact("Potential feature overlap with LessLag")
                .tradeoff("Review both configurations to avoid duplicate processing")
                .recommendation("Check " + pluginName + " settings for overlapping features")
                .build());
        }
    }
}
