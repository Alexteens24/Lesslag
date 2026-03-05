import type { EvaluationInput, ConfigMap } from '../../types/config.js';
import type { HardwareTier, AggressivenessLevel, GameProfile } from '../../types/enums.js';
import type { RuleResult, PatchProposal } from '../../types/rule-result.js';
import { buildRuleResult, buildPatch } from '../../types/rule-result.js';

function getString(configs: ConfigMap, file: string, key: string, def: string): string {
    const fileConfig = configs[file];
    if (!fileConfig) return def;
    const val = fileConfig[key];
    return val != null ? String(val) : def;
}

function getInt(configs: ConfigMap, file: string, key: string, def: number): number {
    const fileConfig = configs[file];
    if (!fileConfig) return def;
    const val = fileConfig[key];
    if (val == null) return def;
    const n = typeof val === 'number' ? val : parseInt(String(val).trim(), 10);
    return isNaN(n) ? def : n;
}

function getBool(configs: ConfigMap, file: string, key: string, def: boolean): boolean {
    const fileConfig = configs[file];
    if (!fileConfig) return def;
    const val = fileConfig[key];
    if (val == null) return def;
    if (typeof val === 'boolean') return val;
    return String(val).trim().toLowerCase() === 'true';
}

function isPresent(configs: ConfigMap, file: string): boolean {
    return configs[file] != null;
}

// ── Paper world defaults ─────────────────────────────────────

function evaluatePaperWorldDefaults(
    configs: ConfigMap, tier: HardwareTier, level: AggressivenessLevel, profile: GameProfile,
    results: RuleResult[], proposals: PatchProposal[],
): void {
    const f = 'config/paper-world-defaults.yml';
    if (!isPresent(configs, f)) return;

    // redstone-implementation
    const impl = getString(configs, f, 'misc.redstone-implementation', 'VANILLA');
    if (impl.toUpperCase() !== 'ALTERNATE_CURRENT') {
        results.push(buildRuleResult('paper-redstone-impl', {
            group: 'fork-specific', severity: 'INFO', confidence: 0.9,
            why: `Redstone implementation is '${impl}' — ALTERNATE_CURRENT is more efficient`,
            impact: 'ALTERNATE_CURRENT is significantly faster with possible minor behaviour changes',
            tradeoff: 'Some complex redstone contraptions may behave slightly differently; test first',
            recommendation: 'Set redstone-implementation: ALTERNATE_CURRENT',
            manualSteps: 'In config/paper-world-defaults.yml, set misc.redstone-implementation: ALTERNATE_CURRENT',
            impactedKeys: [`${f}:misc.redstone-implementation`],
        }));
        proposals.push(buildPatch(f, 'misc.redstone-implementation', impl, 'ALTERNATE_CURRENT',
            'MEDIUM', 'RECOMMEND', 'paper-redstone-impl',
            'Use ALTERNATE_CURRENT redstone for better performance (Paper Chan recommended)'));
    }

    // per-player-mob-spawns
    const perPlayer = getBool(configs, f, 'entities.spawning.per-player-mob-spawns', true);
    if (!perPlayer) {
        results.push(buildRuleResult('paper-per-player-mob-spawns', {
            group: 'fork-specific', severity: 'WARNING', confidence: 0.95,
            why: 'per-player-mob-spawns is disabled — mob spawning uses shared global cap',
            impact: 'Without this, mob spawning is uneven and farms near players with many spawnable chunks get unfair advantage',
            tradeoff: 'Beneficial for the majority of servers; very few reasons to disable',
            recommendation: 'Enable per-player-mob-spawns: true',
            manualSteps: 'In config/paper-world-defaults.yml, set entities.spawning.per-player-mob-spawns: true',
            impactedKeys: [`${f}:entities.spawning.per-player-mob-spawns`],
        }));
        proposals.push(buildPatch(f, 'entities.spawning.per-player-mob-spawns', 'false', 'true',
            'LOW', 'RECOMMEND', 'paper-per-player-mob-spawns',
            'Enable per-player mob spawns for fairer distribution (Paper Chan recommended)'));
    }

    // prevent-moving-into-unloaded-chunks
    const preventUnloaded = getBool(configs, f, 'chunks.prevent-moving-into-unloaded-chunks', true);
    if (!preventUnloaded) {
        results.push(buildRuleResult('paper-prevent-unloaded-chunks', {
            group: 'fork-specific', severity: 'INFO', confidence: 0.85,
            why: 'Players can move into unloaded chunks, triggering sync chunk loads that tank TPS',
            impact: 'Sync-chunk loading is a major cause of lag spikes during fast travel',
            tradeoff: 'Players may briefly rubber-band at chunk borders — generally unnoticeable',
            recommendation: 'Enable prevent-moving-into-unloaded-chunks: true',
            impactedKeys: [`${f}:chunks.prevent-moving-into-unloaded-chunks`],
        }));
        proposals.push(buildPatch(f, 'chunks.prevent-moving-into-unloaded-chunks', 'false', 'true',
            'LOW', 'RECOMMEND', 'paper-prevent-unloaded-chunks',
            'Prevent sync-load lag spikes from entering unloaded chunks (Paper Chan recommended)'));
    }

    // max-entity-collisions
    const collisions = getInt(configs, f, 'collisions.max-entity-collisions', 8);
    let recCollisions = 8;
    if (tier === 'LOW' && level === 'AGGRESSIVE') recCollisions = 4;
    else if (tier === 'LOW') recCollisions = 6;
    recCollisions = Math.max(3, recCollisions);
    if (collisions < 3) {
        results.push(buildRuleResult('paper-entity-collisions-unsafe', {
            group: 'fork-specific', severity: 'WARNING', confidence: 0.95,
            why: `max-entity-collisions is ${collisions} — below safe minimum of 3`,
            impact: 'Values below 3 break game mechanics that rely on entity collisions',
            tradeoff: 'Raising to at least 3 restores Vanilla collision behaviour',
            recommendation: `Set max-entity-collisions to at least 3 (recommended: ${recCollisions})`,
            impactedKeys: [`${f}:collisions.max-entity-collisions`],
        }));
        proposals.push(buildPatch(f, 'collisions.max-entity-collisions',
            String(collisions), String(recCollisions), 'MEDIUM', 'RECOMMEND', 'paper-entity-collisions-unsafe',
            'Raise entity collisions to safe minimum (Paper Chan: never below 3)'));
    }

    // fix-climbing-bypassing-cramming-rule
    const fixClimbing = getBool(configs, f, 'collisions.fix-climbing-bypassing-cramming-rule', false);
    if (!fixClimbing) {
        proposals.push(buildPatch(f, 'collisions.fix-climbing-bypassing-cramming-rule', 'false', 'true',
            'LOW', 'RECOMMEND', 'paper-fix-climbing-cramming',
            'Fix climbing mobs bypassing cramming rules (Paper Chan recommended)'));
    }

    // optimize-explosions
    const optimizedExplosions = getBool(configs, f, 'environment.optimize-explosions', false);
    if (!optimizedExplosions && (profile === 'CREATIVE' || profile === 'MINIGAME')) {
        proposals.push(buildPatch(f, 'environment.optimize-explosions', 'false', 'true',
            'LOW', 'RECOMMEND', 'paper-optimize-explosions',
            `Optimize explosion calculations for ${profile} servers`));
    }

    // treasure-maps
    const treasureMaps = getBool(configs, f, 'environment.treasure-maps.find-already-discovered.villager-trade', false);
    if (!treasureMaps) {
        results.push(buildRuleResult('paper-treasure-maps', {
            group: 'fork-specific', severity: 'INFO', confidence: 0.85,
            why: 'Treasure map searches up to ~1100 blocks for undiscovered treasures — resource intensive',
            impact: 'Large lag spikes when villagers generate treasure maps; can stall the server',
            tradeoff: 'Maps may point to already-discovered treasures instead of new ones',
            recommendation: 'Set find-already-discovered.villager-trade: true',
            impactedKeys: [`${f}:environment.treasure-maps.find-already-discovered.villager-trade`],
        }));
        proposals.push(buildPatch(f, 'environment.treasure-maps.find-already-discovered.villager-trade',
            'false', 'true', 'LOW', 'RECOMMEND', 'paper-treasure-maps',
            'Reduce treasure map lag by allowing already-discovered results (Paper Chan recommended)'));
    }

    // feature-seeds
    const randomSeeds = getBool(configs, f, 'feature-seeds.generate-random-seeds-for-all', false);
    if (!randomSeeds) {
        results.push(buildRuleResult('paper-feature-seeds', {
            group: 'fork-specific', severity: 'INFO', confidence: 0.75,
            why: 'Feature seeds are not randomised — seed-cracking tools can find your world seed',
            impact: 'Players can use tools like SeedcrackerX to discover world seed and locate structures',
            tradeoff: 'Enable ONLY for new worlds; enabling on existing worlds can cause cut-off structures and break /locate command',
            recommendation: 'Enable for NEW worlds. Also manually set structure seeds in spigot.yml',
            impactedKeys: [`${f}:feature-seeds.generate-random-seeds-for-all`],
        }));
    }

    // delay-chunk-unloads-by
    const delay = getString(configs, f, 'chunks.delay-chunk-unloads-by', '10s');
    if (delay === '0s' || delay === '0' || delay === '1s') {
        results.push(buildRuleResult('paper-chunk-unload-delay', {
            group: 'fork-specific', severity: 'INFO', confidence: 0.8,
            why: `Chunk unload delay is very low (${delay}) — causes excessive re-loading`,
            impact: 'Server wastes resources re-loading chunks that were just unloaded',
            tradeoff: '10s default provides a good balance between memory usage and avoiding re-loads',
            recommendation: 'Set delay-chunk-unloads-by: 10s (the default)',
            impactedKeys: [`${f}:chunks.delay-chunk-unloads-by`],
        }));
        proposals.push(buildPatch(f, 'chunks.delay-chunk-unloads-by', delay, '10s',
            'LOW', 'RECOMMEND', 'paper-chunk-unload-delay',
            'Restore chunk unload delay to 10s to avoid wasteful re-loading'));
    }

    // max-auto-save-chunks-per-tick
    const autoSave = getInt(configs, f, 'chunks.max-auto-save-chunks-per-tick', 24);
    if (autoSave !== 24 && autoSave > 0) {
        results.push(buildRuleResult('paper-auto-save-chunks', {
            group: 'fork-specific', severity: 'INFO', confidence: 0.7,
            why: `max-auto-save-chunks-per-tick is ${autoSave} (default: 24)`,
            impact: 'Incorrect values can cause performance loss or data loss',
            tradeoff: 'The default value of 24 is most optimal for the majority of servers',
            recommendation: 'Keep at 24 unless you fully understand the chunk save pipeline',
            impactedKeys: [`${f}:chunks.max-auto-save-chunks-per-tick`],
        }));
    }

    // alt-item-despawn-rate
    const altDespawn = getBool(configs, f, 'entities.spawning.alt-item-despawn-rate.enabled', false);
    if (!altDespawn) {
        results.push(buildRuleResult('paper-alt-item-despawn', {
            group: 'fork-specific', severity: 'INFO', confidence: 0.85,
            why: 'alt-item-despawn-rate is disabled — junk items persist for full 5-minute despawn timer',
            impact: 'Cobblestone, rotten flesh, and other junk from farms pile up, wasting entity slots',
            tradeoff: 'Junk items despawn faster; valuable items keep full 5-minute timer',
            recommendation: 'Enable with recommended junk items: cobblestone: 600, netherrack: 600, rotten_flesh: 900, cactus: 900, egg: 900, etc.',
            impactedKeys: [`${f}:entities.spawning.alt-item-despawn-rate.enabled`],
        }));
        proposals.push(buildPatch(f, 'entities.spawning.alt-item-despawn-rate.enabled', 'false', 'true',
            'LOW', 'RECOMMEND', 'paper-alt-item-despawn',
            'Enable alt-item-despawn to clean up junk items faster (Paper Chan recommended)'));
    }

    // entity-per-chunk-save-limit
    const arrowLimit = getInt(configs, f, 'chunks.entity-per-chunk-save-limit.arrow', -1);
    if (arrowLimit < 0) {
        results.push(buildRuleResult('paper-entity-chunk-save-limit', {
            group: 'fork-specific', severity: 'INFO', confidence: 0.9,
            why: 'entity-per-chunk-save-limit is not configured — chunks with many projectiles can stall on load',
            impact: 'Players can fire many projectiles into a chunk, causing server stalls when that chunk loads',
            tradeoff: 'Limits how many of each projectile entity are saved per chunk; excess are discarded on save',
            recommendation: 'Set limits for projectile entities to prevent chunk-load stalls',
            impactedKeys: [`${f}:chunks.entity-per-chunk-save-limit.arrow`],
        }));
    }

    // despawn-time
    const arrowDespawn = getInt(configs, f, 'entities.spawning.despawn-time.arrow', -1);
    if (arrowDespawn < 0) {
        results.push(buildRuleResult('paper-despawn-time', {
            group: 'fork-specific', severity: 'INFO', confidence: 0.8,
            why: 'despawn-time is not set for projectile entities — they persist indefinitely',
            impact: 'Lingering projectiles accumulate over time, especially from mob farms or combat',
            tradeoff: 'Projectiles will automatically despawn after the configured time',
            recommendation: 'Set reasonable despawn times for projectiles and throwables',
            impactedKeys: [`${f}:entities.spawning.despawn-time.arrow`],
        }));
    }

    // despawn-ranges when simDist < 10
    const simDist = getInt(configs, 'server.properties', 'simulation-distance', 10);
    if (simDist < 10) {
        const recHardHorizontal = (simDist - 1) * 16;
        results.push(buildRuleResult('paper-despawn-ranges', {
            group: 'fork-specific', severity: 'WARNING', confidence: 0.9,
            why: `Simulation distance is ${simDist} (below default 10) — despawn-ranges.hard.horizontal should be adjusted`,
            impact: `Without adjustment, entities at the border of simulation distance won't despawn properly, wasting the mob cap. Hard horizontal should be ${recHardHorizontal} blocks`,
            tradeoff: 'Keep vertical at default 128 so AFK spots for farms still work like vanilla',
            recommendation: `Set despawn-ranges.monster.hard.horizontal: ${recHardHorizontal}`,
            impactedKeys: [`${f}:entities.spawning.despawn-ranges.monster.hard`],
        }));
        proposals.push(buildPatch(f, 'entities.spawning.despawn-ranges.monster.hard.horizontal',
            'default', String(recHardHorizontal), 'MEDIUM', 'RECOMMEND', 'paper-despawn-ranges',
            `Align monster hard despawn range with sim-dist ${simDist} (Paper Chan recommended)`));
    }

    // villager tick-rates
    if (tier === 'LOW' || level === 'AGGRESSIVE') {
        const secondaryPoi = getInt(configs, f, 'tick-rates.sensor.villager.secondarypoisensor', 40);
        const recSecondary = 240;
        const recValidate = 120;
        if (secondaryPoi < recSecondary) {
            results.push(buildRuleResult('paper-villager-tick-rates', {
                group: 'fork-specific', severity: 'INFO', confidence: 0.8,
                why: 'Villager POI sensor rates are at default — can be raised to reduce tick cost',
                impact: 'Villagers check for workstations and secondary POIs less frequently, saving CPU',
                tradeoff: 'Villagers may take slightly longer to find workstations or update behaviour',
                recommendation: `Set secondarypoisensor: ${recSecondary} and validatenearbypoi: ${recValidate}`,
                impactedKeys: [`${f}:tick-rates.sensor.villager.secondarypoisensor`],
            }));
            proposals.push(buildPatch(f, 'tick-rates.sensor.villager.secondarypoisensor',
                String(secondaryPoi), String(recSecondary), 'LOW', 'RECOMMEND', 'paper-villager-tick-rates',
                'Increase villager POI sensor interval to reduce CPU usage (Paper Chan recommended)'));
        }
    }

    // armor-stands
    const armorTick = getBool(configs, f, 'entities.armor-stands.tick', true);
    const armorCollision = getBool(configs, f, 'entities.armor-stands.do-collision-entity-lookups', true);
    if (!armorTick || !armorCollision) {
        results.push(buildRuleResult('paper-armor-stands', {
            group: 'fork-specific', severity: 'WARNING', confidence: 0.9,
            why: 'Armor stand ticking or collision lookups are disabled',
            impact: 'Disabling these breaks: armor stand plugins, automatic ice makers, and removes armor stand lag machine protection',
            tradeoff: 'Enabling costs minimal performance; disabling saves little but breaks much',
            recommendation: 'Keep entities.armor-stands.tick: true and do-collision-entity-lookups: true',
            impactedKeys: [`${f}:entities.armor-stands.tick`],
        }));
    }

    // tracking-range-y
    const trackingY = getBool(configs, f, 'entities.tracking-range-y.enabled', false);
    if (!trackingY) {
        proposals.push(buildPatch(f, 'entities.tracking-range-y.enabled', 'false', 'true',
            'LOW', 'RECOMMEND', 'paper-tracking-range-y',
            'Enable vertical tracking range for finer entity visibility control (Paper feature)'));
    }
}

// ── Paper global ─────────────────────────────────────────────

function evaluatePaperGlobal(configs: ConfigMap, results: RuleResult[], proposals: PatchProposal[]): void {
    const f = 'config/paper-global.yml';
    if (!isPresent(configs, f)) return;

    const workerThreads = getInt(configs, f, 'chunk-system.worker-threads', -1);
    const ioThreads = getInt(configs, f, 'chunk-system.io-threads', -1);
    if (workerThreads > 0 || ioThreads > 0) {
        results.push(buildRuleResult('paper-chunk-system-overridden', {
            group: 'fork-specific', severity: 'WARNING', confidence: 0.8,
            why: 'Chunk system threads have been manually overridden from defaults',
            impact: 'Manual values may negatively impact performance. Default (-1 = auto) is most optimal for the majority of servers',
            tradeoff: 'Setting back to -1 lets Paper auto-detect the optimal thread counts',
            recommendation: 'Set worker-threads: -1 and io-threads: -1 (auto-detect)',
            impactedKeys: [`${f}:chunk-system.worker-threads`],
        }));
    }

    const pageMax = getInt(configs, f, 'item-validation.book-size.page-max', 2560);
    if (pageMax > 1280) {
        results.push(buildRuleResult('paper-book-validation', {
            group: 'fork-specific', severity: 'INFO', confidence: 0.8,
            why: `Book page-max is ${pageMax} bytes — can be reduced to prevent book bans`,
            impact: 'Large books can be used for griefing (bookban exploit)',
            tradeoff: 'Smaller page-max limits what players can write in books; 640-1280 is safe',
            recommendation: 'Reduce page-max to 1280 or lower',
            impactedKeys: [`${f}:item-validation.book-size.page-max`],
        }));
        proposals.push(buildPatch(f, 'item-validation.book-size.page-max',
            String(pageMax), '1280', 'LOW', 'RECOMMEND', 'paper-book-validation',
            'Reduce book page size to mitigate bookban exploit (Paper Chan recommended)'));
    }

    const resolveSelectors = getBool(configs, f, 'item-validation.resolve-selectors-in-books', false);
    if (resolveSelectors) {
        proposals.push(buildPatch(f, 'item-validation.resolve-selectors-in-books', 'true', 'false',
            'LOW', 'RECOMMEND', 'paper-book-selectors', 'Disable selectors in books for security'));
    }
}

// ── Purpur ───────────────────────────────────────────────────

function evaluatePurpur(
    configs: ConfigMap, tier: HardwareTier, level: AggressivenessLevel,
    results: RuleResult[], proposals: PatchProposal[],
): void {
    if (!isPresent(configs, 'purpur.yml')) return;

    const lobotomize = getBool(configs, 'purpur.yml', 'world-settings.default.mobs.villager.lobotomize.enabled', false);
    if (!lobotomize && (tier === 'LOW' || level === 'AGGRESSIVE')) {
        results.push(buildRuleResult('purpur-villager-lobotomize', {
            group: 'fork-specific', severity: 'INFO', confidence: 0.85,
            why: "Purpur's villager lobotomize feature is disabled",
            impact: "Trading halls with many villagers cause significant lag from AI ticking. Paper Chan recommends VillagerLobotimizer or Purpur's built-in lobotomize",
            tradeoff: 'Lobotomised villagers lose some AI but trades still work normally',
            recommendation: 'Enable lobotomize for villagers in trading halls',
            impactedKeys: ['purpur.yml:world-settings.default.mobs.villager.lobotomize.enabled'],
        }));
        proposals.push(buildPatch('purpur.yml', 'world-settings.default.mobs.villager.lobotomize.enabled',
            'false', 'true', 'MEDIUM', 'RECOMMEND', 'purpur-villager-lobotomize',
            'Enable Purpur villager lobotomization for better performance'));
    }
}

// ── Pufferfish ───────────────────────────────────────────────

function evaluatePufferfish(
    configs: ConfigMap, tier: HardwareTier,
    results: RuleResult[], proposals: PatchProposal[],
): void {
    if (!isPresent(configs, 'pufferfish.yml')) return;

    const dabEnabled = getBool(configs, 'pufferfish.yml', 'dab.enabled', true);
    const dabRange = getInt(configs, 'pufferfish.yml', 'dab.start-distance', 12);
    let recRange: number;
    switch (tier) {
        case 'LOW': recRange = 8; break;
        case 'HIGH': recRange = 16; break;
        default: recRange = 12; break;
    }
    if (dabEnabled && dabRange !== recRange) {
        results.push(buildRuleResult('pufferfish-dab-range', {
            group: 'fork-specific', severity: 'INFO', confidence: 0.8,
            why: `Pufferfish DAB start distance is ${dabRange}, recommended ${recRange} for ${tier}`,
            impact: 'Controls at what distance entity AI begins to be skipped',
            tradeoff: 'Lower = more aggressive AI skipping, higher = more natural mob behavior',
            recommendation: `Set dab.start-distance=${recRange}`,
            impactedKeys: ['pufferfish.yml:dab.start-distance'],
        }));
        proposals.push(buildPatch('pufferfish.yml', 'dab.start-distance',
            String(dabRange), String(recRange), 'LOW', 'RECOMMEND', 'pufferfish-dab-range',
            `Tune Pufferfish DAB range for ${tier}`));
    }
}

// ── Leaf ─────────────────────────────────────────────────────

function evaluateLeaf(results: RuleResult[]): void {
    results.push(buildRuleResult('leaf-optimizations', {
        group: 'fork-specific', severity: 'INFO', confidence: 0.75,
        why: 'Leaf server detected — additional optimizations available',
        impact: 'Leaf includes extra performance patches beyond Paper/Purpur',
        tradeoff: 'Some Leaf optimizations may change vanilla behavior',
        recommendation: 'Review Leaf-specific settings in leaves.yml for your use case',
    }));
}

// ── Luminol ──────────────────────────────────────────────────

function evaluateLuminol(results: RuleResult[]): void {
    results.push(buildRuleResult('luminol-detected', {
        group: 'fork-specific', severity: 'INFO', confidence: 0.9,
        why: 'Luminol server detected — Folia-based fork with additional Paper features',
        impact: "Luminol inherits Folia's threaded region scheduler; plugins must be Folia-compatible",
        tradeoff: "Not all Paper plugins are compatible with Folia's regionized threading model",
        recommendation: 'Verify all plugins are Folia-compatible. See https://github.com/LuminolMC/Luminol for Luminol-specific options',
    }));
}

// ── Entry point ──────────────────────────────────────────────

export function evaluateForkSpecificRules(
    input: EvaluationInput,
    results: RuleResult[],
    proposals: PatchProposal[],
    _seen: Set<string>,
): void {
    const { platform, configs, tier, aggressiveness: level, profile } = input;

    if (platform.isPurpur) evaluatePurpur(configs, tier, level, results, proposals);
    if (platform.isPufferfish) evaluatePufferfish(configs, tier, results, proposals);
    if (platform.isLeaf) evaluateLeaf(results);
    if (platform.isLuminol) evaluateLuminol(results);
    if (platform.isPaper) {
        evaluatePaperWorldDefaults(configs, tier, level, profile, results, proposals);
        evaluatePaperGlobal(configs, results, proposals);
    }
}
