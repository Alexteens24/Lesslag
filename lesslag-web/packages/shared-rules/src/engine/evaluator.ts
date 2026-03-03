import type { EvaluationInput, EvaluationOutput, EvaluationSummary, ConfigMap } from '../types/config';
import type { RuleResult, PatchProposal } from '../types/rule-result';
import type { HardwareTier, AggressivenessLevel, GameProfile, Severity, RiskTag } from '../types/enums';
import { buildRuleResult, buildPatch } from '../types/rule-result';

// ─── Config helpers (mirrors ConfigAdapter.java) ────────────

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

// ─── Safety Rules (priority 10) ────────────────────────────

function evaluateSafetyRules(
  input: EvaluationInput,
  results: RuleResult[],
  proposals: PatchProposal[],
  _seen: Set<string>,
): void {
  const { configs, hardware, profile, tier, aggressiveness: level } = input;

  // Check online-mode
  const onlineMode = getString(configs, 'server.properties', 'online-mode', 'true');
  if (onlineMode.toLowerCase() === 'false') {
    results.push(buildRuleResult('safety-online-mode', {
      group: 'safety', severity: 'WARNING', confidence: 1.0,
      why: 'Server is running in offline mode (online-mode=false)',
      impact: 'Players can join without Mojang authentication — security risk',
      tradeoff: 'Required for BungeeCord/Velocity proxied setups; otherwise a vulnerability',
      recommendation: 'Ensure this is intentional. Use a proxy with ip-forwarding if behind Bungee/Velocity.',
      manualSteps: 'If using a proxy, verify ip-forwarding is correctly configured in the proxy config.',
      impactedKeys: ['server.properties:online-mode'],
    }));
  }

  // Check world-guard safety
  results.push(buildRuleResult('safety-world-guard-defaults', {
    group: 'safety', severity: 'INFO', confidence: 0.9,
    why: 'LessLag World Chunk Guard has safe defaults (disabled by default)',
    impact: 'When enabled, aggressive chunk unloading can cause brief visual artifacts',
    tradeoff: 'Keep disabled unless experiencing chunk overload issues',
    recommendation: 'Leave world-guard disabled unless specifically needed for chunk overload',
    impactedKeys: ['modules.chunks.world-guard.enabled'],
  }));

  // Check heap size
  const maxMb = hardware.maxHeapMB;
  if (maxMb < 2048) {
    results.push(buildRuleResult('safety-low-heap', {
      group: 'safety', severity: 'CRITICAL', confidence: 0.95,
      why: `Server heap is only ${formatMB(maxMb)} — critically low`,
      impact: 'Frequent GC pauses, out-of-memory crashes, poor TPS under any load',
      tradeoff: 'Increasing heap requires more physical RAM on the host',
      recommendation: 'Allocate at least 4GB heap (-Xmx4G). Paper Chan: 10GB is sufficient for most servers. Set -Xms equal to -Xmx',
      manualSteps: 'Edit your startup script: change -Xmx to at least 4G and set -Xms equal to -Xmx',
    }));
  } else if (maxMb < 4096) {
    results.push(buildRuleResult('safety-moderate-heap', {
      group: 'safety', severity: 'WARNING', confidence: 0.8,
      why: `Server heap is ${formatMB(maxMb)} — sufficient for small servers only`,
      impact: 'May experience GC pressure with 20+ players or large worlds',
      tradeoff: 'More heap = better headroom but requires available host RAM. Paper Chan: 10GB is sufficient for most servers',
      recommendation: 'Consider 6-10GB for 20+ concurrent players. Set -Xms equal to -Xmx',
      manualSteps: 'Edit your startup script: change -Xmx to 6G-10G and set -Xms to the same value',
    }));
  }

  // Check GC overhead
  if (hardware.gcOverheadPercent > 15) {
    results.push(buildRuleResult('safety-gc-overhead', {
      group: 'safety', severity: 'WARNING', confidence: 0.85,
      why: `GC overhead is ${hardware.gcOverheadPercent.toFixed(1)}% — high`,
      impact: 'Server spending significant time on garbage collection instead of ticking',
      tradeoff: 'Switching GC algorithm may require JDK 17+ features',
      recommendation: "Paper Chan: use Aikar's flags for G1GC, or ZGC (-XX:+UseZGC) for Java 21+ (no extra tuning needed). Set -Xms equal to -Xmx",
      manualSteps: "For G1GC: use Aikar's flags (https://docs.papermc.io/paper/aikars-flags)\nFor ZGC (Java 21+): add -XX:+UseZGC -XX:+ZGenerational to start script\nAlways set -Xms equal to -Xmx",
    }));
  }

  // Check allow-flight
  const allowFlight = getString(configs, 'server.properties', 'allow-flight', 'false');
  if (allowFlight.toLowerCase() === 'false') {
    results.push(buildRuleResult('safety-allow-flight', {
      group: 'safety', severity: 'WARNING', confidence: 0.9,
      why: 'allow-flight is false — Vanilla flight detection is unreliable',
      impact: "Players get kicked for 'flying' during normal gameplay (lag, elytra, jumping on boats/slimes). Paper Chan recommends always true",
      tradeoff: 'Use a proper anti-cheat plugin instead of Vanilla flight detection',
      recommendation: 'Set allow-flight=true in server.properties',
      manualSteps: 'In server.properties, set allow-flight=true',
      impactedKeys: ['server.properties:allow-flight'],
    }));
    proposals.push(buildPatch('server.properties', 'allow-flight', 'false', 'true',
      'LOW', 'RECOMMEND', 'safety-allow-flight',
      'Enable allow-flight to prevent false kicks (Paper Chan recommended)'));
  }

  // Check pause-when-empty
  const pause = getString(configs, 'server.properties', 'pause-when-empty-seconds', '60');
  if (pause !== '-1') {
    const val = parseInt(pause.trim(), 10) || 60;
    if (val >= 0) {
      results.push(buildRuleResult('safety-pause-when-empty', {
        group: 'safety', severity: 'INFO', confidence: 0.8,
        why: `pause-when-empty-seconds is ${val} — server pauses when empty`,
        impact: 'Can cause issues with scheduled tasks, cron-based backups, and plugins that expect the server to always be running',
        tradeoff: 'Saves resources when no players are online, but breaks some functionality',
        recommendation: 'Set pause-when-empty-seconds=-1 to disable',
        manualSteps: 'In server.properties, set pause-when-empty-seconds=-1',
        impactedKeys: ['server.properties:pause-when-empty-seconds'],
      }));
      proposals.push(buildPatch('server.properties', 'pause-when-empty-seconds', pause, '-1',
        'LOW', 'RECOMMEND', 'safety-pause-when-empty',
        'Disable pause-when-empty to prevent task/plugin issues (Paper Chan recommended)'));
    }
  }

  // Check processor count
  if (hardware.availableProcessors > 0 && hardware.availableProcessors < 4) {
    results.push(buildRuleResult('safety-low-threads', {
      group: 'safety', severity: 'WARNING', confidence: 0.85,
      why: `Server has only ${hardware.availableProcessors} available processor(s) — Paper Chan recommends a minimum of 4 threads/cores`,
      impact: 'Modern Minecraft servers need at least 4 threads for main thread, chunk loading, networking, and GC',
      tradeoff: 'Consider upgrading hosting plan or dedicating more cores',
      recommendation: 'Use a host with at least 4 threads/cores',
    }));
  }

  // Check redstone defaults
  let recMaxActivations: number;
  switch (tier) {
    case 'LOW': recMaxActivations = 150; break;
    case 'HIGH': recMaxActivations = 350; break;
    default: recMaxActivations = 250; break;
  }
  if (level === 'AGGRESSIVE') recMaxActivations = Math.trunc(recMaxActivations * 0.6);
  proposals.push(buildPatch('config.yml', 'modules.redstone.max-activations-per-chunk',
    '250', String(recMaxActivations),
    'MEDIUM', 'LESSLAG_APPLY', 'safety-redstone',
    `Tune redstone activation limit for ${input.tier} hardware`));

  // Check breeding limits
  let recBreeding: number;
  switch (tier) {
    case 'LOW': recBreeding = 10; break;
    case 'HIGH': recBreeding = 25; break;
    default: recBreeding = 20; break;
  }
  if (profile === 'SKYBLOCK') recBreeding = Math.trunc(recBreeding * 0.7);
  proposals.push(buildPatch('config.yml', 'modules.breeding-limiter.max-animals-per-chunk',
    '20', String(recBreeding),
    'LOW', 'LESSLAG_APPLY', 'safety-breeding',
    `Set breeding limit for ${profile} / ${tier}`));
}

// ─── Consistency Rules (priority 20) ────────────────────────

function evaluateConsistencyRules(
  input: EvaluationInput,
  results: RuleResult[],
  proposals: PatchProposal[],
  _seen: Set<string>,
): void {
  const { configs, tier, aggressiveness: level } = input;

  // View/sim distance
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

  // Spawn density coherence
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

  // Mob-spawn-range
  const monsterLimit = getInt(configs, 'bukkit.yml', 'spawn-limits.monsters', 70);
  const spawnRange = getInt(configs, 'spigot.yml', 'world-settings.default.mob-spawn-range', 8);
  const maxRange = Math.max(3, simDist - 1);
  const optRange = recommendedMobSpawnRange(monsterLimit);
  const recRange = Math.max(3, Math.min(optRange, maxRange));

  if (spawnRange > maxRange) {
    results.push(buildRuleResult('consistency-mob-spawn-range', {
      group: 'consistency', severity: 'WARNING', confidence: 0.9,
      why: `mob-spawn-range (${spawnRange}) exceeds simulation-distance - 1 (${maxRange})`,
      impact: "Mobs can spawn in chunks that aren't fully simulated, wasting the mob cap",
      tradeoff: 'mob-spawn-range should never exceed sim-dist - 1',
      recommendation: `Set mob-spawn-range: ${recRange} (for ${monsterLimit} monsters with sim-dist ${simDist})`,
      manualSteps: `In spigot.yml, set world-settings.default.mob-spawn-range: ${recRange}`,
      impactedKeys: ['spigot.yml:world-settings.default.mob-spawn-range'],
    }));
    proposals.push(buildPatch('spigot.yml', 'world-settings.default.mob-spawn-range',
      String(spawnRange), String(recRange), 'MEDIUM', 'RECOMMEND', 'consistency-mob-spawn-range',
      'Align mob-spawn-range with sim-dist (Paper Chan cheat sheet)'));
  } else if (spawnRange !== recRange) {
    results.push(buildRuleResult('consistency-mob-spawn-range-tune', {
      group: 'consistency', severity: 'INFO', confidence: 0.8,
      why: `mob-spawn-range is ${spawnRange}, optimal is ${recRange} for ${monsterLimit} monsters / sim-dist ${simDist}`,
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

  // Merge radius
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

  // Entity tracking ranges
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

  // ticks-per
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

  // Chunk entity coherence
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

function recommendedMobSpawnRange(monsterLimit: number): number {
  if (monsterLimit >= 70) return 8;
  if (monsterLimit >= 56) return 7;
  if (monsterLimit >= 42) return 6;
  if (monsterLimit >= 28) return 5;
  if (monsterLimit >= 14) return 4;
  return 3;
}

// ─── Conflict Rules (priority 30) ───────────────────────────

function evaluateConflictRules(
  input: EvaluationInput,
  results: RuleResult[],
  proposals: PatchProposal[],
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

// ─── Fork-Specific Rules (priority 40) ─────────────────────

function evaluateForkSpecificRules(
  input: EvaluationInput,
  results: RuleResult[],
  proposals: PatchProposal[],
  _seen: Set<string>,
): void {
  const { platform, configs, tier, aggressiveness: level, profile } = input;

  if (platform.isPurpur) evaluatePurpur(configs, tier, level, results, proposals);
  if (platform.isPufferfish) evaluatePufferfish(configs, tier, results, proposals);
  if (platform.isLeaf) evaluateLeaf(results);
  if (platform.isPaper) {
    evaluatePaperWorldDefaults(configs, tier, level, profile, results, proposals);
    evaluatePaperGlobal(configs, results, proposals);
  }
}

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

  // despawn-ranges (when simDist < 10)
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

function evaluatePaperGlobal(configs: ConfigMap, results: RuleResult[], proposals: PatchProposal[]): void {
  const f = 'config/paper-global.yml';
  if (!isPresent(configs, f)) return;

  // chunk-system
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

  // book validation
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

function evaluateLeaf(results: RuleResult[]): void {
  results.push(buildRuleResult('leaf-optimizations', {
    group: 'fork-specific', severity: 'INFO', confidence: 0.75,
    why: 'Leaf server detected — additional optimizations available',
    impact: 'Leaf includes extra performance patches beyond Paper/Purpur',
    tradeoff: 'Some Leaf optimizations may change vanilla behavior',
    recommendation: 'Review Leaf-specific settings in leaves.yml for your use case',
  }));
}

// ─── Performance Tuning Rules (priority 50) ─────────────────

function evaluatePerformanceTuningRules(
  input: EvaluationInput,
  results: RuleResult[],
  proposals: PatchProposal[],
  _seen: Set<string>,
): void {
  const { tier, aggressiveness: level, profile } = input;

  // Frustum culling
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

  // Density optimizer
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

  // Villager optimizer
  let recRestoreDuration: number;
  switch (tier) {
    case 'LOW': recRestoreDuration = 15; break;
    case 'HIGH': recRestoreDuration = 45; break;
    default: recRestoreDuration = 30; break;
  }
  proposals.push(buildPatch('config.yml', 'modules.villager-optimizer.ai-restore-duration',
    '30', String(recRestoreDuration), 'LOW', 'LESSLAG_APPLY', 'perf-villager',
    `Tune villager AI restore duration for ${tier}`));

  // TPS thresholds
  let recMinor: number, recModerate: number, recCritical: number;
  switch (tier) {
    case 'LOW': recMinor = 18.5; recModerate = 16.0; recCritical = 12.0; break;
    case 'HIGH': recMinor = 17.5; recModerate = 14.0; recCritical = 9.0; break;
    default: recMinor = 18.0; recModerate = 15.0; recCritical = 10.0; break;
  }
  proposals.push(buildPatch('config.yml', 'automation.thresholds.minor.tps', '18.0', String(recMinor), 'LOW', 'LESSLAG_APPLY', 'perf-thresholds', `Tune minor TPS threshold for ${tier}`));
  proposals.push(buildPatch('config.yml', 'automation.thresholds.moderate.tps', '15.0', String(recModerate), 'LOW', 'LESSLAG_APPLY', 'perf-thresholds', `Tune moderate TPS threshold for ${tier}`));
  proposals.push(buildPatch('config.yml', 'automation.thresholds.critical.tps', '10.0', String(recCritical), 'MEDIUM', 'LESSLAG_APPLY', 'perf-thresholds', `Tune critical TPS threshold for ${tier}`));

  // Workload budget
  let recBudget: number;
  switch (tier) {
    case 'LOW': recBudget = 1.0; break;
    case 'HIGH': recBudget = 3.0; break;
    default: recBudget = 2.0; break;
  }
  proposals.push(buildPatch('config.yml', 'workload-limit-ms', '2', String(recBudget), 'LOW', 'LESSLAG_APPLY', 'perf-workload', `Tune workload distributor budget for ${tier}`));
}

// ─── Main Evaluator ─────────────────────────────────────────

export function evaluate(input: EvaluationInput): EvaluationOutput {
  const results: RuleResult[] = [];
  const proposals: PatchProposal[] = [];
  const seen = new Set<string>();

  const ruleFns = [
    evaluateSafetyRules,
    evaluateConsistencyRules,
    evaluateConflictRules,
    evaluateForkSpecificRules,
    evaluatePerformanceTuningRules,
  ];

  for (const fn of ruleFns) {
    try {
      fn(input, results, proposals, seen);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      results.push(buildRuleResult(`error-${fn.name}`, {
        group: 'internal', severity: 'WARNING', confidence: 1.0,
        why: `Rule '${fn.name}' threw an exception: ${msg}`,
        impact: "This rule's recommendations are unavailable",
        recommendation: 'Report this issue to the LessLag developer',
      }));
    }
  }

  // De-duplicate proposals by (targetFile, configKey) — first rule wins
  const deduped = new Map<string, PatchProposal>();
  for (const p of proposals) {
    const key = `${p.targetFile}:${p.configKey}`;
    if (!deduped.has(key)) deduped.set(key, p);
  }

  const dedupedProposals = Array.from(deduped.values());

  // Show pre-generate reminder when view/sim distance or chunk settings are changed
  const pregenerateReminder = dedupedProposals.some(
    (p) =>
      (p.targetFile === 'server.properties' &&
        (p.configKey === 'view-distance' || p.configKey === 'simulation-distance')) ||
      (typeof p.targetFile === 'string' && p.targetFile.includes('paper-world')) &&
        p.configKey.includes('chunk'),
  );

  return {
    results,
    proposals: dedupedProposals,
    summary: computeSummary(results, dedupedProposals),
    pregenerateReminder,
  };
}

function computeSummary(results: RuleResult[], proposals: PatchProposal[]): EvaluationSummary {
  const bySeverity: Record<string, number> = { INFO: 0, WARNING: 0, CRITICAL: 0 };
  for (const r of results) bySeverity[r.severity] = (bySeverity[r.severity] || 0) + 1;

  const byRisk: Record<string, number> = { LOW: 0, MEDIUM: 0, HIGH: 0 };
  for (const p of proposals) byRisk[p.riskTag] = (byRisk[p.riskTag] || 0) + 1;

  return {
    totalResults: results.length,
    totalProposals: proposals.length,
    bySeverity,
    byRisk,
    autoApplicable: proposals.filter(p => p.applyScope === 'LESSLAG_APPLY').length,
    recommendOnly: proposals.filter(p => p.applyScope === 'RECOMMEND').length,
  };
}

function formatMB(mb: number): string {
  if (mb >= 1024) return `${(mb / 1024).toFixed(1)} GB`;
  return `${mb} MB`;
}
