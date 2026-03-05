import type { EvaluationInput } from '../../types/config.js';
import type { RuleResult, PatchProposal } from '../../types/rule-result.js';
import { buildRuleResult, buildPatch } from '../../types/rule-result.js';

export function evaluatePerformanceTuningRules(
    input: EvaluationInput,
    results: RuleResult[],
    proposals: PatchProposal[],
    _seen: Set<string>,
): void {
    const { tier, aggressiveness: level, profile } = input;

    // ── Frustum culling ─────────────────────────────────────────
    let recRadius: number, recInterval: number;
    switch (tier) {
        case 'LOW': recRadius = 28; recInterval = 20; break;
        case 'HIGH': recRadius = 48; recInterval = 40; break;
        default: recRadius = 40; recInterval = 30; break;
    }
    if (level === 'AGGRESSIVE') recRadius = Math.trunc(recRadius * 0.7);
    proposals.push(buildPatch('config.yml', 'modules.mob-ai.active-radius',
        '40', String(recRadius), 'LOW', 'LESSLAG_APPLY', 'perf-frustum-radius',
        `Tune AI culling radius for ${tier}`));
    proposals.push(buildPatch('config.yml', 'modules.mob-ai.update-interval',
        '30', String(recInterval), 'LOW', 'LESSLAG_APPLY', 'perf-frustum-interval',
        `Tune AI culling interval for ${tier}`));

    // ── Density optimizer ────────────────────────────────────────
    let cowLimit: number, sheepLimit: number, pigLimit: number, chickenLimit: number, villagerDensity: number;
    switch (profile) {
        case 'SKYBLOCK': cowLimit = 8; sheepLimit = 8; pigLimit = 8; chickenLimit = 12; villagerDensity = 15; break;
        case 'MINIGAME': cowLimit = 15; sheepLimit = 15; pigLimit = 15; chickenLimit = 20; villagerDensity = 25; break;
        case 'CREATIVE': cowLimit = 20; sheepLimit = 20; pigLimit = 20; chickenLimit = 25; villagerDensity = 30; break;
        default: cowLimit = 10; sheepLimit = 10; pigLimit = 10; chickenLimit = 15; villagerDensity = 20; break;
    }
    if (tier === 'LOW') {
        cowLimit = Math.trunc(cowLimit * 0.7); sheepLimit = Math.trunc(sheepLimit * 0.7);
        pigLimit = Math.trunc(pigLimit * 0.7); chickenLimit = Math.trunc(chickenLimit * 0.7);
        villagerDensity = Math.trunc(villagerDensity * 0.7);
    } else if (tier === 'HIGH') {
        cowLimit = Math.trunc(cowLimit * 1.3); sheepLimit = Math.trunc(sheepLimit * 1.3);
        pigLimit = Math.trunc(pigLimit * 1.3); chickenLimit = Math.trunc(chickenLimit * 1.3);
        villagerDensity = Math.trunc(villagerDensity * 1.3);
    }
    if (level === 'AGGRESSIVE') {
        cowLimit = Math.max(5, Math.trunc(cowLimit * 0.6));
        sheepLimit = Math.max(5, Math.trunc(sheepLimit * 0.6));
        pigLimit = Math.max(5, Math.trunc(pigLimit * 0.6));
        chickenLimit = Math.max(5, Math.trunc(chickenLimit * 0.6));
        villagerDensity = Math.max(8, Math.trunc(villagerDensity * 0.6));
    }

    results.push(buildRuleResult('perf-density-tuning', {
        group: 'performance', severity: 'INFO', confidence: 0.85,
        why: `Density optimizer limits tuned for ${profile} / ${tier}`,
        impact: 'Controls how many same-type entities per chunk before AI is disabled',
        tradeoff: 'Lower limits = better TPS but less natural mob behavior in farms',
        recommendation: 'Apply recommended density limits',
    }));
    proposals.push(buildPatch('config.yml', 'modules.density-optimizer.limits.COW', '10', String(cowLimit), 'LOW', 'LESSLAG_APPLY', 'perf-density-tuning', 'Density limit for cows'));
    proposals.push(buildPatch('config.yml', 'modules.density-optimizer.limits.SHEEP', '10', String(sheepLimit), 'LOW', 'LESSLAG_APPLY', 'perf-density-tuning', 'Density limit for sheep'));
    proposals.push(buildPatch('config.yml', 'modules.density-optimizer.limits.PIG', '10', String(pigLimit), 'LOW', 'LESSLAG_APPLY', 'perf-density-tuning', 'Density limit for pigs'));
    proposals.push(buildPatch('config.yml', 'modules.density-optimizer.limits.CHICKEN', '15', String(chickenLimit), 'LOW', 'LESSLAG_APPLY', 'perf-density-tuning', 'Density limit for chickens'));
    proposals.push(buildPatch('config.yml', 'modules.density-optimizer.limits.VILLAGER', '20', String(villagerDensity), 'LOW', 'LESSLAG_APPLY', 'perf-density-tuning', 'Density limit for villagers'));

    // ── Villager optimizer ───────────────────────────────────────
    let recRestoreDuration: number;
    switch (tier) {
        case 'LOW': recRestoreDuration = 15; break;
        case 'HIGH': recRestoreDuration = 45; break;
        default: recRestoreDuration = 30; break;
    }
    proposals.push(buildPatch('config.yml', 'modules.villager-optimizer.ai-restore-duration',
        '30', String(recRestoreDuration), 'LOW', 'LESSLAG_APPLY', 'perf-villager',
        `Tune villager AI restore duration for ${tier}`));

    // ── TPS thresholds ───────────────────────────────────────────
    let recMinor: number, recModerate: number, recCritical: number;
    switch (tier) {
        case 'LOW': recMinor = 18.5; recModerate = 16.0; recCritical = 12.0; break;
        case 'HIGH': recMinor = 17.5; recModerate = 14.0; recCritical = 9.0; break;
        default: recMinor = 18.0; recModerate = 15.0; recCritical = 10.0; break;
    }
    proposals.push(buildPatch('config.yml', 'automation.thresholds.minor.tps', '18.0', String(recMinor), 'LOW', 'LESSLAG_APPLY', 'perf-thresholds', `Tune minor TPS threshold for ${tier}`));
    proposals.push(buildPatch('config.yml', 'automation.thresholds.moderate.tps', '15.0', String(recModerate), 'LOW', 'LESSLAG_APPLY', 'perf-thresholds', `Tune moderate TPS threshold for ${tier}`));
    proposals.push(buildPatch('config.yml', 'automation.thresholds.critical.tps', '10.0', String(recCritical), 'MEDIUM', 'LESSLAG_APPLY', 'perf-thresholds', `Tune critical TPS threshold for ${tier}`));

    // ── Workload budget ──────────────────────────────────────────
    let recBudget: number;
    switch (tier) {
        case 'LOW': recBudget = 1.0; break;
        case 'HIGH': recBudget = 3.0; break;
        default: recBudget = 2.0; break;
    }
    proposals.push(buildPatch('config.yml', 'workload-limit-ms', '2', String(recBudget), 'LOW', 'LESSLAG_APPLY', 'perf-workload', `Tune workload distributor budget for ${tier}`));
}
