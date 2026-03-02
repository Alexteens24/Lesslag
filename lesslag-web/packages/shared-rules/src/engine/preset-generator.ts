import type { GameProfile, HardwareTier, AggressivenessLevel, ServerFork } from '../types/enums.js';
import { GameProfileMeta, HardwareTierMeta, AggressivenessLevelMeta } from '../types/enums.js';
import type { PresetProfile } from '../types/config.js';

/**
 * Comprehensive preset generator — produces optimised server + LessLag
 * config values from a 3-axis matrix (profile × tier × aggressiveness),
 * optional player count, and detected server fork.
 *
 * Settings are sourced from Paper Chan's optimisation guide, Minecraft
 * performance best practices, and LessLag-specific module tuning.
 * Fork-specific recommendations (Paper, Purpur, Pufferfish) are
 * automatically included when the fork is specified.
 *
 * @see https://paper-chan.moe/paper-optimization/
 */

// ─── Scaling helpers ────────────────────────────────────────

/** Pick a value from a LOW / MID / HIGH triplet. */
function pick(low: number, mid: number, high: number, tier: HardwareTier): number {
  return tier === 'LOW' ? low : tier === 'HIGH' ? high : mid;
}

/** Continuous player-count scaling factor (1.0 at ≤20, ~0.4 floor at 200+). */
function playerScale(count: number): number {
  if (count <= 20) return 1.0;
  if (count <= 50) return 1.0 - (count - 20) * 0.003;     // 1.0 → 0.91
  if (count <= 100) return 0.91 - (count - 50) * 0.004;    // 0.91 → 0.71
  if (count <= 150) return 0.71 - (count - 100) * 0.003;   // 0.71 → 0.56
  return Math.max(0.4, 0.56 - (count - 150) * 0.002);      // floor 0.4
}

/** Profile-specific entity multiplier. */
function profileEntityFactor(profile: GameProfile): number {
  switch (profile) {
    case 'SKYBLOCK':  return 0.7;   // heavy automation, fewer natural mobs
    case 'MINIGAME':  return 0.6;   // short-lived, minimal entities
    case 'CREATIVE':  return 1.1;   // building focus, lax entity pressure
    default:          return 1.0;   // SMP baseline
  }
}

function clamp(val: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, val));
}

function round(val: number): number {
  return Math.trunc(val);
}

/** Determine if fork inherits Paper's config surface. */
function isPaperLike(fork: ServerFork): boolean {
  return fork === 'paper' || fork === 'purpur' || fork === 'pufferfish' || fork === 'leaf';
}

/** Paper Chan cheat sheet: monster limit → recommended mob-spawn-range. */
function recommendedMobSpawnRange(monsterLimit: number): number {
  if (monsterLimit >= 70) return 8;
  if (monsterLimit >= 56) return 7;
  if (monsterLimit >= 42) return 6;
  if (monsterLimit >= 28) return 5;
  if (monsterLimit >= 14) return 4;
  return 3;
}

// ─── Public helpers ─────────────────────────────────────────

/**
 * Apply a player-count load modifier that may shift the effective tier.
 * Extreme concurrency can overwhelm even high-end hardware.
 */
export function applyLoadModifier(baseTier: HardwareTier, playerCount: number): HardwareTier {
  if (playerCount >= 80 && baseTier === 'HIGH') return 'MID';
  if (playerCount >= 50 && baseTier === 'MID') return 'LOW';
  if (playerCount >= 100 && baseTier === 'MID') return 'LOW';
  return baseTier;
}

// ─── Section builders ───────────────────────────────────────

/**
 * Server core: view-distance & simulation-distance.
 * Returns computed distances for downstream use.
 */
function buildServerCore(
  s: Record<string, string>, d: string[],
  tier: HardwareTier, level: AggressivenessLevel, profile: GameProfile, players: number,
): { viewDist: number; simDist: number } {
  let viewBase = pick(8, 10, 12, tier);
  if (level === 'AGGRESSIVE') viewBase = Math.max(6, viewBase - 2);
  else if (level === 'SAFE' && tier === 'HIGH') viewBase = Math.min(16, viewBase + 2);
  if (profile === 'CREATIVE') viewBase = Math.min(16, viewBase + 2);

  // Gentle reduction for high player counts
  const viewPenalty = Math.max(0, round((players - 30) / 40));
  let viewDist = clamp(viewBase - viewPenalty, 5, 16);

  let simBase = pick(6, 8, 10, tier);
  if (level === 'AGGRESSIVE') simBase = Math.max(5, simBase - 1);
  if (profile === 'MINIGAME') simBase = Math.max(5, simBase - 1);

  const simPenalty = Math.max(0, round((players - 50) / 50));
  let simDist = clamp(simBase - simPenalty, 5, 12);

  // Invariant: view >= sim
  viewDist = Math.max(viewDist, simDist);

  s['server.view-distance'] = String(viewDist);
  s['server.simulation-distance'] = String(simDist);

  d.push('── Server ──────────────────────────────');
  d.push(`  view-distance: ${viewDist}    simulation-distance: ${simDist}`);

  return { viewDist, simDist };
}

/**
 * Bukkit spawn limits & ticks-per settings.
 * Returns computed monster limit for downstream mob-spawn-range calc.
 */
function buildBukkit(
  s: Record<string, string>, d: string[],
  tier: HardwareTier, level: AggressivenessLevel, profile: GameProfile, scale: number,
): number {
  let monsters    = pick(28, 35, 50, tier);
  let animals     = pick(5, 8, 10, tier);
  let waterAnimal = pick(2, 3, 5, tier);
  let waterAmbi   = pick(1, 2, 3, tier);
  let ambient     = pick(0, 1, 3, tier);

  // Profile specialisation
  if (profile === 'SKYBLOCK') {
    monsters = round(monsters * 0.8);
    animals  = round(animals * 0.7);
  } else if (profile === 'MINIGAME') {
    monsters    = round(monsters * 0.7);
    animals     = Math.max(3, round(animals * 0.5));
    waterAnimal = 1; waterAmbi = 0; ambient = 0;
  }

  // Aggressiveness
  if (level === 'AGGRESSIVE') {
    monsters    = Math.max(14, round(monsters * 0.6));
    animals     = Math.max(3, round(animals * 0.6));
    waterAnimal = Math.max(1, round(waterAnimal * 0.5));
    ambient     = 0;
  }

  // Player-count scaling (moderate — spawn-limits are per-player on Paper)
  if (scale < 1.0) {
    monsters = Math.max(14, round(monsters * Math.max(0.7, scale)));
    animals  = Math.max(3, round(animals * Math.max(0.75, scale)));
  }

  s['bukkit.spawn-limits.monsters']      = String(monsters);
  s['bukkit.spawn-limits.animals']       = String(animals);
  s['bukkit.spawn-limits.water-animals'] = String(waterAnimal);
  s['bukkit.spawn-limits.water-ambient'] = String(waterAmbi);
  s['bukkit.spawn-limits.ambient']       = String(ambient);

  // Ticks-per — Paper Chan: spawn-limits is the primary knob, ticks-per secondary
  s['bukkit.ticks-per.monster-spawns'] = '1';
  s['bukkit.ticks-per.animal-spawns']  = '400';

  d.push('── Bukkit ──────────────────────────────');
  d.push(`  spawn-limits: monsters=${monsters}, animals=${animals}, ambient=${ambient}`);
  d.push(`  water: animals=${waterAnimal}, ambient=${waterAmbi}`);
  d.push('  ticks-per: monster=1, animal=400');

  return monsters;
}

/**
 * Spigot: mob-spawn-range, tracking ranges, merge radius, nerf-spawner-mobs.
 */
function buildSpigot(
  s: Record<string, string>, d: string[],
  tier: HardwareTier, monsterLimit: number, simDist: number, profile: GameProfile,
): void {
  // mob-spawn-range aligned with monster limit & sim-dist (Paper Chan cheat sheet)
  const optRange = recommendedMobSpawnRange(monsterLimit);
  const maxRange = Math.max(3, simDist - 1);
  const mobSpawnRange = Math.max(3, Math.min(optRange, maxRange));
  s['spigot.mob-spawn-range'] = String(mobSpawnRange);

  // Entity tracking ranges — Paper Chan: keep vanilla-like for best gameplay
  s['spigot.entity-tracking-range.players']  = '128';
  s['spigot.entity-tracking-range.animals']  = '96';
  s['spigot.entity-tracking-range.monsters'] = '96';
  s['spigot.entity-tracking-range.misc']     = '96';
  s['spigot.entity-tracking-range.display']  = '128';
  s['spigot.entity-tracking-range.other']    = '64';

  // Merge radius — Paper Chan: keep vanilla (-1), use spawn-limits instead
  s['spigot.merge-radius.item'] = '-1';
  s['spigot.merge-radius.exp']  = '-1';

  // nerf-spawner-mobs — recommended for heavy spawner usage (Skyblock)
  const nerfSpawners = profile === 'SKYBLOCK';
  s['spigot.nerf-spawner-mobs'] = String(nerfSpawners);

  d.push('── Spigot ──────────────────────────────');
  d.push(`  mob-spawn-range: ${mobSpawnRange}    nerf-spawner-mobs: ${nerfSpawners}`);
  d.push('  merge-radius: -1 (vanilla)    tracking: vanilla-like');
}

/**
 * Paper world-defaults: redstone, spawning, chunks, collisions, despawn,
 * villager tick-rates, armor stands, tracking-range-y.
 */
function buildPaperWorld(
  s: Record<string, string>, d: string[],
  tier: HardwareTier, level: AggressivenessLevel, profile: GameProfile,
  simDist: number,
): void {
  // Redstone — ALTERNATE_CURRENT is universally faster
  s['paper-world.misc.redstone-implementation'] = 'ALTERNATE_CURRENT';

  // Spawning core
  s['paper-world.entities.spawning.per-player-mob-spawns'] = 'true';
  s['paper-world.entities.spawning.alt-item-despawn-rate.enabled'] = 'true';

  // Chunks
  s['paper-world.chunks.prevent-moving-into-unloaded-chunks'] = 'true';
  s['paper-world.chunks.delay-chunk-unloads-by'] = '10s';
  s['paper-world.chunks.max-auto-save-chunks-per-tick'] = '24';

  // Collisions
  let maxCollisions = pick(6, 8, 8, tier);
  if (level === 'AGGRESSIVE' && tier !== 'HIGH') maxCollisions = Math.max(3, maxCollisions - 2);
  s['paper-world.collisions.max-entity-collisions'] = String(maxCollisions);
  s['paper-world.collisions.fix-climbing-bypassing-cramming-rule'] = 'true';

  // Explosions — optimise for combat/creative or aggressive tuning
  const optExplosions = profile !== 'SMP' || level === 'AGGRESSIVE';
  s['paper-world.environment.optimize-explosions'] = String(optExplosions);

  // Treasure maps — prevent expensive searches
  s['paper-world.environment.treasure-maps.find-already-discovered.villager-trade'] = 'true';
  s['paper-world.environment.treasure-maps.find-already-discovered.loot-tables'] = 'true';

  // Entity-per-chunk save limits — prevent chunk-load stalls from projectile spam
  s['paper-world.chunks.entity-per-chunk-save-limit.arrow']           = '16';
  s['paper-world.chunks.entity-per-chunk-save-limit.ender_pearl']     = '16';
  s['paper-world.chunks.entity-per-chunk-save-limit.experience_orb']  = '16';
  s['paper-world.chunks.entity-per-chunk-save-limit.fireball']        = '5';
  s['paper-world.chunks.entity-per-chunk-save-limit.small_fireball']  = '5';
  s['paper-world.chunks.entity-per-chunk-save-limit.snowball']        = '16';

  // Despawn ranges — must align with sim-dist when below default 10
  if (simDist < 10) {
    const hardH = (simDist - 1) * 16;
    const softH = clamp(round(hardH * 0.5), 24, hardH);
    s['paper-world.entities.spawning.despawn-ranges.monster.soft']   = String(softH);
    s['paper-world.entities.spawning.despawn-ranges.monster.hard']   = String(hardH);
    s['paper-world.entities.spawning.despawn-ranges.creature.soft']  = String(softH);
    s['paper-world.entities.spawning.despawn-ranges.creature.hard']  = String(hardH);
    s['paper-world.entities.spawning.despawn-ranges.misc.soft']      = String(softH);
    s['paper-world.entities.spawning.despawn-ranges.misc.hard']      = String(hardH);
  }

  // Villager tick-rates — reduced polling saves CPU on low-end / aggressive
  if (tier === 'LOW' || level === 'AGGRESSIVE') {
    s['paper-world.tick-rates.sensor.villager.secondarypoisensor']   = '240';
    s['paper-world.tick-rates.sensor.villager.validatenearbypoi']    = '120';
    s['paper-world.tick-rates.behavior.villager.validatenearbypoi']  = '120';
  } else {
    s['paper-world.tick-rates.sensor.villager.secondarypoisensor']   = '80';
    s['paper-world.tick-rates.sensor.villager.validatenearbypoi']    = '60';
    s['paper-world.tick-rates.behavior.villager.validatenearbypoi']  = '60';
  }

  // Armor stands — Paper Chan: never disable, saves almost nothing
  s['paper-world.entities.armor-stands.tick']                        = 'true';
  s['paper-world.entities.armor-stands.do-collision-entity-lookups'] = 'true';

  // Tracking range Y — finer vertical entity visibility
  s['paper-world.entities.tracking-range-y.enabled'] = 'true';

  // Projectile despawn time (ticks) — 1200 = 60s
  s['paper-world.entities.spawning.despawn-time.arrow'] = '1200';

  // Description
  d.push('── Paper World Defaults ────────────────');
  d.push('  redstone: ALTERNATE_CURRENT');
  d.push(`  per-player-mob-spawns: true    alt-item-despawn: enabled`);
  d.push(`  max-entity-collisions: ${maxCollisions}    optimize-explosions: ${optExplosions}`);
  d.push('  prevent-unloaded-chunks: true    chunk-unload-delay: 10s');
  if (simDist < 10) {
    d.push(`  despawn-ranges: hard=${(simDist - 1) * 16} (sim-dist ${simDist})`);
  }
  d.push('  entity-save-limits: arrow=16, fireball=5');
  d.push('  tracking-range-y: enabled    armor-stands: tick=true');
}

/** Paper global: chunk-system threads, book validation. */
function buildPaperGlobal(
  s: Record<string, string>, d: string[],
): void {
  s['paper-global.chunk-system.worker-threads'] = '-1';
  s['paper-global.chunk-system.io-threads']     = '-1';
  s['paper-global.item-validation.book-size.page-max']         = '1024';
  s['paper-global.item-validation.resolve-selectors-in-books'] = 'false';

  d.push('── Paper Global ────────────────────────');
  d.push('  chunk-system: auto-detect    book page-max: 1024');
}

/** Purpur: villager lobotomize. */
function buildPurpur(
  s: Record<string, string>, d: string[],
  tier: HardwareTier, level: AggressivenessLevel,
): void {
  const lobotomize = tier === 'LOW' || level !== 'SAFE';
  s['purpur.mobs.villager.lobotomize.enabled']        = String(lobotomize);
  s['purpur.mobs.villager.lobotomize.check-interval'] = String(pick(60, 100, 100, tier));

  d.push('── Purpur ──────────────────────────────');
  d.push(`  villager-lobotomize: ${lobotomize}`);
}

/** Pufferfish: DAB (Distance-based AI Batching). */
function buildPufferfish(
  s: Record<string, string>, d: string[],
  tier: HardwareTier,
): void {
  s['pufferfish.dab.enabled']             = 'true';
  const dabRange = pick(8, 12, 16, tier);
  s['pufferfish.dab.start-distance']      = String(dabRange);
  s['pufferfish.dab.max-tick-freq']       = '20';
  s['pufferfish.dab.activation-dist-mod'] = String(pick(7, 8, 9, tier));

  d.push('── Pufferfish ──────────────────────────');
  d.push(`  DAB: start-distance=${dabRange}, max-tick-freq=20`);
}

/**
 * LessLag-specific modules: workload budget, redstone, entities,
 * mob AI, density optimizer, breeding, villager optimizer, TPS thresholds.
 */
function buildLessLag(
  s: Record<string, string>, d: string[],
  tier: HardwareTier, level: AggressivenessLevel, profile: GameProfile, scale: number,
): void {
  // ── Core workload budget
  let workloadMs = pick(1.0, 2.0, 3.0, tier);
  if (level === 'AGGRESSIVE') workloadMs = Math.max(0.5, workloadMs * 0.7);
  s['workload-limit-ms'] = String(workloadMs);

  // ── Redstone
  let redstoneMax = round(pick(150, 250, 350, tier));
  if (level === 'AGGRESSIVE') redstoneMax = round(redstoneMax * 0.6);
  if (profile === 'CREATIVE')  redstoneMax = round(redstoneMax * 1.3);
  s['modules.redstone.max-activations-per-chunk'] = String(redstoneMax);
  s['modules.redstone.cooldown-seconds'] = tier === 'LOW' ? '15' : '10';

  // ── Entity chunk limiter
  let chunkLimit = round(pick(30, 50, 70, tier));
  if (level === 'AGGRESSIVE')   chunkLimit = round(chunkLimit * 0.6);
  if (profile === 'SKYBLOCK')   chunkLimit = round(chunkLimit * 0.8);
  if (scale < 1.0)              chunkLimit = Math.max(20, round(chunkLimit * scale));
  s['modules.entities.chunk-limiter.max-entities-per-chunk'] = String(chunkLimit);

  // ── Per-world entity limits
  let monsterWorld = round(pick(1200, 2000, 3000, tier) * profileEntityFactor(profile));
  let animalWorld  = round(pick(600, 1000, 1500, tier)  * profileEntityFactor(profile));
  if (level === 'AGGRESSIVE') {
    monsterWorld = round(monsterWorld * 0.6);
    animalWorld  = round(animalWorld * 0.6);
  }
  if (scale < 1.0) {
    monsterWorld = Math.max(600, round(monsterWorld * scale));
    animalWorld  = Math.max(300, round(animalWorld * scale));
  }
  s['modules.entities.limits.per-world-limit.monster'] = String(monsterWorld);
  s['modules.entities.limits.per-world-limit.animal']  = String(animalWorld);

  // ── Mob AI / Frustum Culling
  let aiRadius = round(pick(28, 40, 52, tier));
  if (level === 'AGGRESSIVE')  aiRadius = round(aiRadius * 0.7);
  if (profile === 'MINIGAME')  aiRadius = round(aiRadius * 0.8);
  const aiInterval = pick(20, 30, 40, tier);
  s['modules.mob-ai.active-radius']   = String(aiRadius);
  s['modules.mob-ai.update-interval'] = String(aiInterval);

  // ── Density optimizer (profile-specific base tables)
  const densityBases: Record<GameProfile, {
    livestock: [number, number, number];
    chicken:   [number, number, number];
    villager:  [number, number, number];
  }> = {
    SMP:      { livestock: [7, 10, 14], chicken: [10, 15, 20], villager: [14, 20, 28] },
    SKYBLOCK: { livestock: [5,  8, 11], chicken: [ 8, 12, 16], villager: [10, 14, 20] },
    MINIGAME: { livestock: [10, 15, 20], chicken: [15, 20, 25], villager: [20, 28, 35] },
    CREATIVE: { livestock: [12, 18, 24], chicken: [15, 22, 30], villager: [18, 25, 32] },
  };
  const db = densityBases[profile];

  const densityVal = (base: [number, number, number]): number => {
    let v = pick(base[0], base[1], base[2], tier);
    if (level === 'AGGRESSIVE') v = Math.max(3, round(v * 0.6));
    if (scale < 1.0)            v = Math.max(3, round(v * Math.max(0.7, scale)));
    return v;
  };

  const cowLim   = densityVal(db.livestock);
  const sheepLim = densityVal(db.livestock);
  const pigLim   = densityVal(db.livestock);
  const chickLim = densityVal(db.chicken);
  const villLim  = densityVal(db.villager);
  s['modules.density-optimizer.limits.COW']      = String(cowLim);
  s['modules.density-optimizer.limits.SHEEP']    = String(sheepLim);
  s['modules.density-optimizer.limits.PIG']      = String(pigLim);
  s['modules.density-optimizer.limits.CHICKEN']  = String(chickLim);
  s['modules.density-optimizer.limits.VILLAGER'] = String(villLim);

  // ── Breeding limiter
  let breedingLimit = round(pick(10, 20, 30, tier));
  if (level === 'AGGRESSIVE')  breedingLimit = round(breedingLimit * 0.6);
  if (profile === 'SKYBLOCK')  breedingLimit = round(breedingLimit * 0.7);
  s['modules.breeding-limiter.max-animals-per-chunk'] = String(breedingLimit);

  // ── Villager optimizer
  const restoreDuration = pick(15, 30, 45, tier);
  s['modules.villager-optimizer.ai-restore-duration'] = String(restoreDuration);

  // ── TPS thresholds
  const minorTps    = pick(18.5, 18.0, 17.5, tier);
  const moderateTps = pick(16.0, 15.0, 14.0, tier);
  const criticalTps = pick(12.0, 10.0, 9.0, tier);
  s['automation.thresholds.minor.tps']    = String(minorTps);
  s['automation.thresholds.moderate.tps'] = String(moderateTps);
  s['automation.thresholds.critical.tps'] = String(criticalTps);

  // ── Chunk distance minimums
  s['modules.chunks.view-distance.min']       = '5';
  s['modules.chunks.simulation-distance.min'] = '5';

  // Description
  d.push('── LessLag ─────────────────────────────');
  d.push(`  workload: ${workloadMs}ms    ai-radius: ${aiRadius}    redstone: ${redstoneMax}/chunk`);
  d.push(`  density: COW=${cowLim} SHEEP=${sheepLim} PIG=${pigLim} CHICKEN=${chickLim} VILLAGER=${villLim}`);
  d.push(`  entity-limit: ${chunkLimit}/chunk    breeding: ${breedingLimit}/chunk`);
  d.push(`  thresholds: minor=${minorTps} moderate=${moderateTps} critical=${criticalTps}`);
}

// ─── Main Generator ─────────────────────────────────────────

/**
 * Generate a comprehensive PresetProfile from the 3-axis matrix plus
 * optional player count and server fork.
 *
 * @param profile      Game type (SMP, SKYBLOCK, MINIGAME, CREATIVE)
 * @param tier         Hardware capability (LOW, MID, HIGH)
 * @param level        How aggressively to optimise (SAFE, BALANCED, AGGRESSIVE)
 * @param playerCount  Expected concurrent players (affects tier + scaling)
 * @param fork         Server fork for fork-specific settings (default: 'paper')
 */
export function generatePreset(
  profile: GameProfile,
  tier: HardwareTier,
  level: AggressivenessLevel,
  playerCount?: number,
  fork?: ServerFork,
): PresetProfile {
  const players       = playerCount ?? 20;
  const effectiveTier = playerCount != null ? applyLoadModifier(tier, playerCount) : tier;
  const effectiveFork = fork ?? 'paper';
  const scale         = playerScale(players);

  const settings: Record<string, string> = {};
  const desc: string[] = [];

  // Header
  const profileLabel = GameProfileMeta[profile].displayName;
  const tierLabel    = HardwareTierMeta[effectiveTier].displayName;
  const aggLabel     = AggressivenessLevelMeta[level].displayName;

  desc.push(`${profileLabel} · ${tierLabel} · ${aggLabel}`);
  if (players > 1) {
    desc.push(`Target: ~${players} players (scale ${(scale * 100).toFixed(0)}%)`);
  }
  if (effectiveFork !== 'vanilla' && effectiveFork !== 'spigot') {
    desc.push(`Fork: ${effectiveFork}`);
  }
  desc.push('');

  // Build all sections; each adds to settings & desc
  const { viewDist, simDist } = buildServerCore(settings, desc, effectiveTier, level, profile, players);
  const monsterLimit = buildBukkit(settings, desc, effectiveTier, level, profile, scale);
  buildSpigot(settings, desc, effectiveTier, monsterLimit, simDist, profile);

  if (isPaperLike(effectiveFork)) {
    buildPaperWorld(settings, desc, effectiveTier, level, profile, simDist);
    buildPaperGlobal(settings, desc);
  }
  if (effectiveFork === 'purpur') {
    buildPurpur(settings, desc, effectiveTier, level);
  }
  if (effectiveFork === 'pufferfish') {
    buildPufferfish(settings, desc, effectiveTier);
  }

  buildLessLag(settings, desc, effectiveTier, level, profile, scale);

  // Summary line
  desc.push('');
  desc.push(`${Object.keys(settings).length} settings generated`);

  return {
    gameProfile: profile,
    hardwareTier: effectiveTier,
    aggressiveness: level,
    settings,
    label: `${profileLabel} / ${tierLabel} / ${aggLabel}`,
    description: desc.join('\n'),
  };
}
