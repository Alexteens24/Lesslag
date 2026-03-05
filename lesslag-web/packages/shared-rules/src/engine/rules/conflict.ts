import type { EvaluationInput } from '../../types/config.js';
import type { RuleResult, PatchProposal } from '../../types/rule-result.js';
import { buildRuleResult } from '../../types/rule-result.js';

export function evaluateConflictRules(
    input: EvaluationInput,
    results: RuleResult[],
    _proposals: PatchProposal[],
    _seen: Set<string>,
): void {
    for (const plugin of input.plugins) {
        const lower = plugin.toLowerCase();

        if (lower.includes('clearlag') || lower.includes('lagg') || lower.includes('entitytrackerfixer')) {
            results.push(buildRuleResult('conflict-clearlag', {
                group: 'conflict', severity: 'WARNING', confidence: 0.95,
                why: `${plugin} is installed — Paper Chan strongly recommends against this type of plugin`,
                impact: 'Entity-clearing plugins mask the root cause of lag instead of fixing it. ClearLag/ETF cause permanent entity brain damage, break mob AI, and remove named/tamed mobs. Fix the root cause instead',
                tradeoff: 'Remove the plugin and address the actual cause of entity accumulation using spawn-limits, alt-item-despawn-rate, and entity-per-chunk-save-limit',
                recommendation: `Remove ${plugin} entirely. Use LessLag + proper config tuning instead`,
                manualSteps: `Remove ${plugin}. In bukkit.yml, tune spawn-limits. In paper-world-defaults.yml, enable alt-item-despawn-rate`,
                impactedKeys: ['compatibility.plugins.clearlag'],
            }));
        } else if (lower.includes('pufferfish')) {
            results.push(buildRuleResult('conflict-pufferfish-dab', {
                group: 'conflict', severity: 'INFO', confidence: 0.85,
                why: "Pufferfish DAB (Distance-based AI Batching) overlaps with LessLag's frustum culling",
                impact: 'Both systems try to optimize mob AI, potentially conflicting',
                tradeoff: "LessLag's frustum culler offers FOV-based culling; DAB uses distance-only",
                recommendation: 'Let LessLag handle AI optimization and disable DAB, or vice versa',
                impactedKeys: ['compatibility.plugins.pufferfish-dab'],
            }));
        } else if (lower.includes('farmcontrol') || lower.includes('mobfarmmanager')) {
            results.push(buildRuleResult(`conflict-farm-${lower}`, {
                group: 'conflict', severity: 'WARNING', confidence: 0.85,
                why: `${plugin} manages farm limits alongside LessLag's breeding limiter and density optimizer`,
                impact: 'Duplicate farm management can cause unexpected entity removal',
                tradeoff: 'Choose one farm management solution for predictable behavior',
                recommendation: `Disable ${plugin}'s farm limits or disable LessLag's density-optimizer/breeding-limiter`,
                manualSteps: `Check ${plugin} config to disable overlapping features`,
            }));
        } else if (['stackmob', 'wildstacker', 'rosestacker', 'mobstacker', 'ultimatestacker'].some(s => lower.includes(s))) {
            results.push(buildRuleResult(`conflict-stacker-${lower}`, {
                group: 'conflict', severity: 'WARNING', confidence: 0.9,
                why: `${plugin} is a mob stacking plugin — Paper Chan says this is an inherently flawed idea`,
                impact: "Mob stackers never let the server reach the mob cap because stacked mobs count as 1, so the server continuously tries to spawn new mobs. This INCREASES lag instead of reducing it. Also causes issues with LessLag's entity counting",
                tradeoff: 'Remove the stacker and reduce spawn-limits in bukkit.yml instead. This is the proper way to control mob counts',
                recommendation: `Remove ${plugin} and set spawn-limits.monsters to 35 in bukkit.yml`,
                manualSteps: `Remove ${plugin}. In bukkit.yml, reduce spawn-limits.monsters`,
            }));
        } else if (['silkspawner', 'minerspawner', 'spawnersilk', 'pickupspawner'].some(s => lower.includes(s))) {
            results.push(buildRuleResult('conflict-silktouch-spawner', {
                group: 'conflict', severity: 'WARNING', confidence: 0.85,
                why: `${plugin} allows players to move spawners — Paper Chan: these are built-in lag machines`,
                impact: "Players can create massive spawner farms that generate huge entity counts and overwhelm entity ticking. If using, set nerf-spawner-mobs: true in spigot.yml",
                tradeoff: "If you must keep this plugin, enable nerf-spawner-mobs in spigot.yml and use LessLag's density-optimizer to limit farm output",
                recommendation: 'Remove the plugin or set nerf-spawner-mobs: true in spigot.yml',
                manualSteps: 'In spigot.yml, set world-settings.default.nerf-spawner-mobs: true',
            }));
        } else if (['antifabric', 'nofabric', 'fabricblock'].some(s => lower.includes(s))) {
            results.push(buildRuleResult('conflict-antifabric', {
                group: 'conflict', severity: 'INFO', confidence: 0.8,
                why: `${plugin} is an anti-Fabric plugin — Paper Chan recommends removing these`,
                impact: 'Anti-Fabric plugins only block legitimate users like Fabric mod users. Cheat clients bypass these detections trivially',
                tradeoff: 'Remove the plugin; it provides no real security benefit',
                recommendation: `Remove ${plugin} — use a proper anti-cheat instead`,
            }));
        }
    }
}
