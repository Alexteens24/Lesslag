import type { EvaluationInput } from '../../types/config.js';
import type { RuleResult, PatchProposal } from '../../types/rule-result.js';
import { buildRuleResult, buildPatch } from '../../types/rule-result.js';

function getString(configs: EvaluationInput['configs'], file: string, key: string, def: string): string {
    const fileConfig = configs[file];
    if (!fileConfig) return def;
    const val = fileConfig[key];
    return val != null ? String(val) : def;
}

function getInt(configs: EvaluationInput['configs'], file: string, key: string, def: number): number {
    const fileConfig = configs[file];
    if (!fileConfig) return def;
    const val = fileConfig[key];
    if (val == null) return def;
    const n = typeof val === 'number' ? val : parseInt(String(val).trim(), 10);
    return isNaN(n) ? def : n;
}

// Paper Chan cheat sheet: monster limit → recommended mob-spawn-range
function recommendedMobSpawnRange(monsterLimit: number): number {
    if (monsterLimit >= 70) return 8;
    if (monsterLimit >= 56) return 7;
    if (monsterLimit >= 42) return 6;
    if (monsterLimit >= 28) return 5;
    if (monsterLimit >= 14) return 4;
    return 3;
}

export function evaluateConsistencyRules(
    input: EvaluationInput,
    results: RuleResult[],
    proposals: PatchProposal[],
    _seen: Set<string>,
): void {
    const { configs, tier, aggressiveness: level } = input;

    // ── view / sim distance ────────────────────────────────────
    const viewDist = getInt(configs, 'server.properties', 'view-distance', 10);
    let simDist = getInt(configs, 'spigot.yml', 'world-settings.default.simulation-distance', -1);
    if (simDist < 0) simDist = getInt(configs, 'server.properties', 'simulation-distance', 10);

    let recView: number, recSim: number;
    switch (tier) {
        case 'LOW':
            recView = level === 'AGGRESSIVE' ? 6 : 8;
            recSim = level === 'AGGRESSIVE' ? 5 : 6;
            break;
        case 'HIGH':
            recView = level === 'SAFE' ? 12 : 10;
            recSim = level === 'SAFE' ? 10 : 8;
            break;
        default:
            recView = level === 'AGGRESSIVE' ? 7 : 10;
            recSim = level === 'AGGRESSIVE' ? 6 : 8;
            break;
    }
    recView = Math.max(5, recView);
    recSim = Math.max(5, recSim);

    if (viewDist < simDist) {
        results.push(buildRuleResult('consistency-view-sim', {
            group: 'consistency', severity: 'WARNING', confidence: 0.95,
            why: `View distance (${viewDist}) is less than simulation distance (${simDist})`,
            impact: "Players see chunks that aren't fully simulated, causing visual glitches",
            tradeoff: 'Increasing view distance uses more bandwidth; decreasing sim distance saves CPU',
            recommendation: `Set view-distance=${recView} and simulation-distance=${recSim}`,
            impactedKeys: ['server.properties:view-distance', 'server.properties:simulation-distance'],
        }));
    }
    if (viewDist < 5) {
        results.push(buildRuleResult('consistency-view-too-low', {
            group: 'consistency', severity: 'WARNING', confidence: 0.95,
            why: `View distance is ${viewDist} — below the recommended minimum of 5`,
            impact: 'Values below 5 cause significant gameplay issues (mob spawning, structure generation, rendering)',
            tradeoff: 'Lower values save bandwidth and CPU but degrade the player experience',
            recommendation: `Set view-distance to at least 5 (recommended: ${recView})`,
            manualSteps: `In server.properties, set view-distance=${Math.max(5, recView)}`,
            impactedKeys: ['server.properties:view-distance'],
        }));
    }
    if (simDist < 5) {
        results.push(buildRuleResult('consistency-sim-too-low', {
            group: 'consistency', severity: 'WARNING', confidence: 0.95,
            why: `Simulation distance is ${simDist} — below the recommended minimum of 5`,
            impact: 'Values below 5 break mob farms, prevent spawning of some structures, and reduce game mechanics range',
            tradeoff: 'Lower values save CPU but degrade gameplay quality significantly',
            recommendation: `Set simulation-distance to at least 5 (recommended: ${recSim})`,
            manualSteps: `In server.properties, set simulation-distance=${Math.max(5, recSim)}`,
            impactedKeys: ['server.properties:simulation-distance'],
        }));
    }
    if (viewDist !== recView) {
        proposals.push(buildPatch('server.properties', 'view-distance',
            String(viewDist), String(recView), 'LOW', 'RECOMMEND', 'consistency-view-sim',
            `Adjust view distance for ${tier} hardware`));
    }
    if (simDist !== recSim) {
        proposals.push(buildPatch('server.properties', 'simulation-distance',
            String(simDist), String(recSim), 'LOW', 'RECOMMEND', 'consistency-view-sim',
            `Adjust simulation distance for ${tier} hardware`));
    }

    // ── spawn limits ───────────────────────────────────────────
    const monsterSpawn = getInt(configs, 'bukkit.yml', 'spawn-limits.monsters', 70);
    const animalSpawn = getInt(configs, 'bukkit.yml', 'spawn-limits.animals', 10);
    const ambientSpawn = getInt(configs, 'bukkit.yml', 'spawn-limits.ambient', 15);

    let recMonster: number, recAnimal: number, recAmbient: number;
    switch (tier) {
        case 'LOW': recMonster = 28; recAnimal = 5; recAmbient = 0; break;
        case 'HIGH': recMonster = 70; recAnimal = 10; recAmbient = 5; break;
        default: recMonster = 35; recAnimal = 8; recAmbient = 1; break;
    }
    if (level === 'AGGRESSIVE') {
        recMonster = Math.max(21, Math.trunc(recMonster * 0.6));
        recAnimal = Math.max(3, Math.trunc(recAnimal * 0.6));
    }
    if (monsterSpawn > recMonster * 1.3) {
        results.push(buildRuleResult('consistency-spawn-limits', {
            group: 'consistency', severity: 'INFO', confidence: 0.85,
            why: `Monster spawn limit (${monsterSpawn}) is high for ${tier} hardware`,
            impact: "More hostile mobs = more AI ticking, pathfinding, and combat processing. Paper Chan: reducing to ~35 is a safe starting point, ~50% reduction barely noticeable",
            tradeoff: 'Lower spawn limits mean fewer mob encounters but better TPS. Use spawn-limits as the PRIMARY control (not ticks-per)',
            recommendation: `Set spawn-limits.monsters=${recMonster}`,
            manualSteps: `In bukkit.yml, set spawn-limits.monsters: ${recMonster}`,
            impactedKeys: ['bukkit.yml:spawn-limits.monsters'],
        }));
        proposals.push(buildPatch('bukkit.yml', 'spawn-limits.monsters',
            String(monsterSpawn), String(recMonster), 'MEDIUM', 'RECOMMEND', 'consistency-spawn-limits',
            'Reduce monster spawn limit (Paper Chan: 35 is a safe starting point)'));
    }
    if (animalSpawn > recAnimal * 1.5 && tier !== 'HIGH') {
        proposals.push(buildPatch('bukkit.yml', 'spawn-limits.animals',
            String(animalSpawn), String(recAnimal), 'LOW', 'RECOMMEND', 'consistency-spawn-limits',
            'Reduce animal spawn limit for better performance'));
    }
    if (ambientSpawn > recAmbient && tier !== 'HIGH') {
        proposals.push(buildPatch('bukkit.yml', 'spawn-limits.ambient',
            String(ambientSpawn), String(recAmbient), 'LOW', 'RECOMMEND', 'consistency-spawn-ambient',
            'Reduce ambient spawns — only bats, safe to set 0 (Paper Chan recommended)'));
    }

    // ── mob-spawn-range ────────────────────────────────────────
    const spawnRange = getInt(configs, 'spigot.yml', 'world-settings.default.mob-spawn-range', 8);
    const maxRange = Math.max(3, simDist - 1);
    const optRange = recommendedMobSpawnRange(monsterSpawn);
    const recRange = Math.max(3, Math.min(optRange, maxRange));

    if (spawnRange > maxRange) {
        results.push(buildRuleResult('consistency-mob-spawn-range', {
            group: 'consistency', severity: 'WARNING', confidence: 0.9,
            why: `mob-spawn-range (${spawnRange}) exceeds simulation-distance - 1 (${maxRange})`,
            impact: "Mobs can spawn in chunks that aren't fully simulated, wasting the mob cap",
            tradeoff: 'mob-spawn-range should never exceed sim-dist - 1',
            recommendation: `Set mob-spawn-range: ${recRange} (for ${monsterSpawn} monsters with sim-dist ${simDist})`,
            manualSteps: `In spigot.yml, set world-settings.default.mob-spawn-range: ${recRange}`,
            impactedKeys: ['spigot.yml:world-settings.default.mob-spawn-range'],
        }));
        proposals.push(buildPatch('spigot.yml', 'world-settings.default.mob-spawn-range',
            String(spawnRange), String(recRange), 'MEDIUM', 'RECOMMEND', 'consistency-mob-spawn-range',
            'Align mob-spawn-range with sim-dist (Paper Chan cheat sheet)'));
    } else if (spawnRange !== recRange) {
        results.push(buildRuleResult('consistency-mob-spawn-range-tune', {
            group: 'consistency', severity: 'INFO', confidence: 0.8,
            why: `mob-spawn-range is ${spawnRange}, optimal is ${recRange} for ${monsterSpawn} monsters / sim-dist ${simDist}`,
            impact: 'Sub-optimal range can reduce mob density or waste mob cap slots',
            tradeoff: 'Paper Chan cheat sheet correlates monster limit to ideal spawn range',
            recommendation: `Set mob-spawn-range: ${recRange}`,
            manualSteps: `In spigot.yml, set world-settings.default.mob-spawn-range: ${recRange}`,
            impactedKeys: ['spigot.yml:world-settings.default.mob-spawn-range'],
        }));
        proposals.push(buildPatch('spigot.yml', 'world-settings.default.mob-spawn-range',
            String(spawnRange), String(recRange), 'LOW', 'RECOMMEND', 'consistency-mob-spawn-range-tune',
            'Tune mob-spawn-range per Paper Chan cheat sheet'));
    }

    // ── merge radius ───────────────────────────────────────────
    const itemMerge = getInt(configs, 'spigot.yml', 'world-settings.default.merge-radius.item', -1);
    const expMerge = getInt(configs, 'spigot.yml', 'world-settings.default.merge-radius.exp', -1);
    if (itemMerge > 0 || expMerge > 0) {
        results.push(buildRuleResult('consistency-merge-radius', {
            group: 'consistency', severity: 'INFO', confidence: 0.8,
            why: `merge-radius is set to item:${itemMerge} exp:${expMerge} — Paper Chan recommends keeping vanilla (-1)`,
            impact: 'Increasing merge radius barely improves performance and causes visual jitter. Reducing spawn-limits is far more effective',
            tradeoff: 'Set to -1 for vanilla behaviour; reducing spawn-limits is the proper fix',
            recommendation: 'Set merge-radius.item: -1 and merge-radius.exp: -1',
            manualSteps: 'In spigot.yml, set world-settings.default.merge-radius.item: -1 and exp: -1',
            impactedKeys: ['spigot.yml:world-settings.default.merge-radius.item'],
        }));
        if (itemMerge > 0) {
            proposals.push(buildPatch('spigot.yml', 'world-settings.default.merge-radius.item',
                String(itemMerge), '-1', 'LOW', 'RECOMMEND', 'consistency-merge-radius',
                'Keep vanilla merge radius — reduce spawn-limits instead (Paper Chan recommended)'));
        }
    }

    // ── entity tracking ranges ────────────────────────────────
    const playerTracking = getInt(configs, 'spigot.yml', 'world-settings.default.entity-tracking-range.players', 128);
    if (playerTracking < 48) {
        results.push(buildRuleResult('consistency-entity-tracking', {
            group: 'consistency', severity: 'INFO', confidence: 0.8,
            why: `Player entity-tracking-range is ${playerTracking} — below recommended 128 for vanilla parity`,
            impact: 'Low tracking ranges make players invisible at shorter distances',
            tradeoff: 'Higher tracking ranges use more bandwidth but improve gameplay',
            recommendation: 'Set entity-tracking-range.players: 128, monsters: 96, animals: 96 for vanilla parity',
            manualSteps: 'In spigot.yml, set world-settings.default.entity-tracking-range:\n  players: 128\n  animals: 96\n  monsters: 96\n  misc: 96\n  display: 128\n  other: 64',
            impactedKeys: ['spigot.yml:world-settings.default.entity-tracking-range.players'],
        }));
    }

    // ── ticks-per ─────────────────────────────────────────────
    const animalTicks = getInt(configs, 'bukkit.yml', 'ticks-per.animal-spawns', 400);
    if (animalTicks < 400) {
        results.push(buildRuleResult('consistency-ticks-per-animals', {
            group: 'consistency', severity: 'INFO', confidence: 0.7,
            why: `ticks-per.animal-spawns is ${animalTicks} — lower than recommended 400`,
            impact: 'Animals are attempted to spawn more frequently than needed, wasting CPU',
            tradeoff: 'Paper Chan: use spawn-limits as primary control, ticks-per as secondary',
            recommendation: 'Set ticks-per.animal-spawns: 400',
            manualSteps: 'In bukkit.yml, set ticks-per.animal-spawns: 400',
            impactedKeys: ['bukkit.yml:ticks-per.animal-spawns'],
        }));
        proposals.push(buildPatch('bukkit.yml', 'ticks-per.animal-spawns',
            String(animalTicks), '400', 'LOW', 'RECOMMEND', 'consistency-ticks-per-animals',
            'Increase animal spawn tick interval to 400 (Paper Chan recommended)'));
    }

    // ── chunk entity coherence ────────────────────────────────
    let recPerChunk: number;
    switch (tier) {
        case 'LOW': recPerChunk = 30; break;
        case 'HIGH': recPerChunk = 60; break;
        default: recPerChunk = 45; break;
    }
    proposals.push(buildPatch('config.yml', 'modules.entities.chunk-limiter.max-entities-per-chunk',
        '50', String(recPerChunk), 'LOW', 'LESSLAG_APPLY', 'consistency-chunk-entity',
        `Set chunk entity limit matching ${tier} tier`));
}
