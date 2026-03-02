import type { GameProfile, HardwareTier, AggressivenessLevel } from '../types/enums';
import { GameProfileMeta, HardwareTierMeta, AggressivenessLevelMeta } from '../types/enums';
import type { PresetProfile } from '../types/config';

/**
 * Port of PresetMatrix.java — generates a PresetProfile from the 3-axis matrix.
 * All formulas match the Java source line-for-line.
 */

export function applyLoadModifier(baseTier: HardwareTier, playerCount: number): HardwareTier {
  if (playerCount >= 80 && baseTier === 'HIGH') return 'MID';
  if (playerCount >= 50 && baseTier === 'MID') return 'LOW';
  if (playerCount >= 100 && baseTier === 'MID') return 'LOW';
  return baseTier;
}

function baseValue(low: number, mid: number, high: number, tier: HardwareTier): number {
  switch (tier) {
    case 'LOW': return low;
    case 'HIGH': return high;
    default: return mid;
  }
}

/** Paper Chan cheat sheet: monster limit → recommended mob-spawn-range */
function recommendedMobSpawnRange(monsterLimit: number): number {
  if (monsterLimit >= 70) return 8;
  if (monsterLimit >= 56) return 7;
  if (monsterLimit >= 42) return 6;
  if (monsterLimit >= 28) return 5;
  if (monsterLimit >= 14) return 4;
  return 3;
}

function densityLimit(
  low: number, mid: number, high: number,
  tier: HardwareTier, level: AggressivenessLevel, profile: GameProfile,
): number {
  let base: number;
  switch (tier) {
    case 'LOW': base = low; break;
    case 'HIGH': base = high; break;
    default: base = mid; break;
  }
  if (level === 'AGGRESSIVE') base = Math.max(3, Math.trunc(base * 0.6));
  if (profile === 'SKYBLOCK') base = Math.max(3, Math.trunc(base * 0.8));
  return base;
}

export function generatePreset(
  profile: GameProfile,
  tier: HardwareTier,
  level: AggressivenessLevel,
  playerCount?: number,
): PresetProfile {
  const effectiveTier = playerCount != null ? applyLoadModifier(tier, playerCount) : tier;

  const settings: Record<string, string> = {};
  const descParts: string[] = [];

  descParts.push(
    `Preset: ${GameProfileMeta[profile].displayName} / ${HardwareTierMeta[effectiveTier].displayName} / ${AggressivenessLevelMeta[level].displayName}`,
  );

  // ── Core workload budget ──
  let workloadMs = baseValue(1.0, 2.0, 3.0, effectiveTier);
  if (level === 'AGGRESSIVE') workloadMs = Math.max(0.5, workloadMs * 0.7);
  settings['workload-limit-ms'] = String(workloadMs);

  // ── Redstone ──
  let redstoneMax = Math.trunc(baseValue(150, 250, 350, effectiveTier));
  if (level === 'AGGRESSIVE') redstoneMax = Math.trunc(redstoneMax * 0.6);
  if (profile === 'CREATIVE') redstoneMax = Math.trunc(redstoneMax * 1.3);
  settings['modules.redstone.max-activations-per-chunk'] = String(redstoneMax);
  settings['modules.redstone.cooldown-seconds'] = effectiveTier === 'LOW' ? '15' : '10';

  // ── Entity limits ──
  let chunkLimit = Math.trunc(baseValue(30, 50, 70, effectiveTier));
  if (level === 'AGGRESSIVE') chunkLimit = Math.trunc(chunkLimit * 0.6);
  if (profile === 'SKYBLOCK') chunkLimit = Math.trunc(chunkLimit * 0.8);
  settings['modules.entities.chunk-limiter.max-entities-per-chunk'] = String(chunkLimit);

  let monsterPerWorld = Math.trunc(baseValue(1200, 2000, 3000, effectiveTier));
  let animalPerWorld = Math.trunc(baseValue(600, 1000, 1500, effectiveTier));
  if (level === 'AGGRESSIVE') {
    monsterPerWorld = Math.trunc(monsterPerWorld * 0.6);
    animalPerWorld = Math.trunc(animalPerWorld * 0.6);
  }
  settings['modules.entities.limits.per-world-limit.monster'] = String(monsterPerWorld);
  settings['modules.entities.limits.per-world-limit.animal'] = String(animalPerWorld);

  // ── Mob AI / Frustum Culling ──
  let aiRadius = Math.trunc(baseValue(28, 40, 52, effectiveTier));
  if (level === 'AGGRESSIVE') aiRadius = Math.trunc(aiRadius * 0.7);
  if (profile === 'MINIGAME') aiRadius = Math.trunc(aiRadius * 0.8);
  settings['modules.mob-ai.active-radius'] = String(aiRadius);
  settings['modules.mob-ai.update-interval'] = String(effectiveTier === 'LOW' ? 20 : 30);

  // ── Density optimizer ──
  const cowLimit = densityLimit(7, 10, 14, effectiveTier, level, profile);
  const sheepLimit = densityLimit(7, 10, 14, effectiveTier, level, profile);
  const pigLimit = densityLimit(7, 10, 14, effectiveTier, level, profile);
  const chickenLimit = densityLimit(10, 15, 20, effectiveTier, level, profile);
  const villagerLimit = densityLimit(14, 20, 28, effectiveTier, level, profile);
  settings['modules.density-optimizer.limits.COW'] = String(cowLimit);
  settings['modules.density-optimizer.limits.SHEEP'] = String(sheepLimit);
  settings['modules.density-optimizer.limits.PIG'] = String(pigLimit);
  settings['modules.density-optimizer.limits.CHICKEN'] = String(chickenLimit);
  settings['modules.density-optimizer.limits.VILLAGER'] = String(villagerLimit);

  // ── Breeding limiter ──
  let breedingLimit = Math.trunc(baseValue(10, 20, 30, effectiveTier));
  if (level === 'AGGRESSIVE') breedingLimit = Math.trunc(breedingLimit * 0.6);
  if (profile === 'SKYBLOCK') breedingLimit = Math.trunc(breedingLimit * 0.7);
  settings['modules.breeding-limiter.max-animals-per-chunk'] = String(breedingLimit);

  // ── Villager optimizer ──
  settings['modules.villager-optimizer.ai-restore-duration'] = String(
    effectiveTier === 'LOW' ? 15 : effectiveTier === 'HIGH' ? 45 : 30,
  );

  // ── TPS thresholds ──
  const minorTps = baseValue(18.5, 18.0, 17.5, effectiveTier);
  const moderateTps = baseValue(16.0, 15.0, 14.0, effectiveTier);
  const criticalTps = baseValue(12.0, 10.0, 9.0, effectiveTier);
  settings['automation.thresholds.minor.tps'] = String(minorTps);
  settings['automation.thresholds.moderate.tps'] = String(moderateTps);
  settings['automation.thresholds.critical.tps'] = String(criticalTps);

  // ── Chunk management ──
  const viewMin = 5; // Paper Chan: never below 5
  settings['modules.chunks.view-distance.min'] = String(viewMin);
  settings['modules.chunks.simulation-distance.min'] = String(viewMin);

  // ── Server config recommendations (Paper Chan) ──
  let recViewDist: number;
  let recSimDist: number;
  switch (effectiveTier) {
    case 'LOW':
      recViewDist = level === 'AGGRESSIVE' ? 6 : 8;
      recSimDist = level === 'AGGRESSIVE' ? 5 : 6;
      break;
    case 'HIGH':
      recViewDist = 10;
      recSimDist = 10;
      break;
    default:
      recViewDist = level === 'AGGRESSIVE' ? 7 : 10;
      recSimDist = level === 'AGGRESSIVE' ? 6 : 8;
      break;
  }
  settings['server.view-distance'] = String(Math.max(5, recViewDist));
  settings['server.simulation-distance'] = String(Math.max(5, recSimDist));

  // Paper Chan: spawn-limits
  let recMonsters: number;
  let recAnimals: number;
  let recAmbient: number;
  switch (effectiveTier) {
    case 'LOW': recMonsters = 28; recAnimals = 5; recAmbient = 0; break;
    case 'HIGH': recMonsters = 70; recAnimals = 10; recAmbient = 5; break;
    default: recMonsters = 35; recAnimals = 8; recAmbient = 1; break;
  }
  if (level === 'AGGRESSIVE') {
    recMonsters = Math.max(21, Math.trunc(recMonsters * 0.6));
  }
  settings['bukkit.spawn-limits.monsters'] = String(recMonsters);
  settings['bukkit.spawn-limits.animals'] = String(recAnimals);
  settings['bukkit.spawn-limits.ambient'] = String(recAmbient);

  // Paper Chan cheat sheet: mob-spawn-range
  let recMobSpawnRange = recommendedMobSpawnRange(recMonsters);
  recMobSpawnRange = Math.max(3, Math.min(recMobSpawnRange, Math.max(3, recSimDist - 1)));
  settings['spigot.mob-spawn-range'] = String(recMobSpawnRange);

  // ── Description ──
  descParts.push(`Entity chunk limit: ${chunkLimit}`);
  descParts.push(`AI culling radius: ${aiRadius}`);
  descParts.push(`Redstone limit: ${redstoneMax}/chunk`);
  descParts.push(`TPS thresholds: minor=${minorTps} moderate=${moderateTps} critical=${criticalTps}`);
  descParts.push(`Recommended server config (Paper Chan guide):`);
  descParts.push(`  view-distance: ${Math.max(5, recViewDist)}, sim-distance: ${Math.max(5, recSimDist)}`);
  descParts.push(`  spawn-limits.monsters: ${recMonsters}, mob-spawn-range: ${recMobSpawnRange}`);

  return {
    gameProfile: profile,
    hardwareTier: effectiveTier,
    aggressiveness: level,
    settings,
    label: `${GameProfileMeta[profile].displayName} / ${HardwareTierMeta[effectiveTier].displayName} / ${AggressivenessLevelMeta[level].displayName}`,
    description: descParts.join('\n'),
  };
}
