import { create } from 'zustand';
import type {
  GameProfile,
  HardwareTier,
  AggressivenessLevel,
  EvaluationInput,
  EvaluationOutput,
  PresetProfile,
  ConfigMap,
  HardwareProfile,
  PlatformInfo,
  PatchProposal,
} from '@lesslag/shared-rules';
import { evaluate, generatePreset, generateDiffs, parseConfig, type ConfigDiff } from '@lesslag/shared-rules';

interface Snapshot {
  id: string;
  label: string;
  timestamp: number;
  configs: ConfigMap;
}

export interface Toast {
  id: string;
  message: string;
  type: 'success' | 'error' | 'info' | 'warning';
}

export type SetupStepId = 'hardware' | 'preset' | 'analysis' | 'changes' | 'export';
export type SetupStepStatus = 'not_started' | 'ready' | 'done';

type SetupProgress = Record<SetupStepId, SetupStepStatus>;

const defaultSetupProgress: SetupProgress = {
  hardware: 'ready',
  preset: 'not_started',
  analysis: 'not_started',
  changes: 'not_started',
  export: 'not_started',
};

const setupOrder: SetupStepId[] = ['hardware', 'preset', 'analysis', 'changes', 'export'];

function markStepDone(progress: SetupProgress, step: SetupStepId): SetupProgress {
  const next: SetupProgress = { ...progress, [step]: 'done' };
  const idx = setupOrder.indexOf(step);
  if (idx >= 0 && idx < setupOrder.length - 1) {
    const nextStep = setupOrder[idx + 1];
    if (next[nextStep] === 'not_started') {
      next[nextStep] = 'ready';
    }
  }
  return next;
}

function ensureStepReady(progress: SetupProgress, step: SetupStepId): SetupProgress {
  if (progress[step] !== 'not_started') return progress;
  return { ...progress, [step]: 'ready' };
}

interface LessLagState {
  // ─── Wizard inputs ───
  profile: GameProfile;
  tier: HardwareTier;
  aggressiveness: AggressivenessLevel;
  playerCount: number;
  plugins: string[];

  hardware: HardwareProfile;
  platform: PlatformInfo;
  configs: ConfigMap;

  // ─── Engine outputs ───
  preset: PresetProfile | null;
  evaluation: EvaluationOutput | null;
  diffs: ConfigDiff[];

  // ─── UI state ───
  selectedProposals: Set<string>; // keyed by `file:key`
  activeTab: 'editor' | 'presets' | 'diff' | 'rationale' | 'hardware' | 'conflicts';
  showImportModal: boolean;
  toasts: Toast[];
  setupProgress: SetupProgress;

  // ─── Snapshots (Phase 4) ───
  snapshots: Snapshot[];
  currentSnapshotId: string | null;

  // ─── Connected server (from plugin session) ───
  connectedServerId: string | null;
  connectedServerName: string | null;
  setConnectedServer: (id: string | null, name: string | null) => void;

  // ─── Actions ───
  setProfile: (p: GameProfile) => void;
  setTier: (t: HardwareTier) => void;
  setAggressiveness: (a: AggressivenessLevel) => void;
  setPlayerCount: (n: number) => void;
  setPlugins: (plugins: string[]) => void;
  setHardware: (hw: Partial<HardwareProfile>) => void;
  setPlatform: (pl: Partial<PlatformInfo>) => void;
  setConfigs: (configs: ConfigMap) => void;
  updateConfig: (file: string, key: string, value: string | number | boolean) => void;
  setActiveTab: (tab: LessLagState['activeTab']) => void;
  setShowImportModal: (show: boolean) => void;
  addToast: (message: string, type?: Toast['type']) => void;
  dismissToast: (id: string) => void;
  completeSetupStep: (step: SetupStepId) => void;
  setSetupStepReady: (step: SetupStepId) => void;

  runEvaluation: () => void;
  generatePresetAction: () => void;
  applyPreset: () => void;

  toggleProposal: (key: string) => void;
  selectAllProposals: () => void;
  deselectAllProposals: () => void;
  applySelectedProposals: () => void;

  saveSnapshot: (label: string) => void;
  restoreSnapshot: (id: string) => void;
  deleteSnapshot: (id: string) => void;

  importConfigs: (files: Record<string, string>) => void;
  exportConfigs: () => Record<string, string>;
}

const defaultHardware: HardwareProfile = {
  availableProcessors: 4,
  cpuModel: 'Unknown',
  maxHeapMB: 4096,
  gcOverheadPercent: 5,
  averageMspt: 40,
};

const defaultPlatform: PlatformInfo = {
  fork: 'paper',
  version: '1.21',
  isPaper: true,
  isPurpur: false,
  isPufferfish: false,
  isLeaf: false,
  hasFolia: false,
};

// Full default values sourced from Paper docs: https://docs.papermc.io/paper/reference/configuration/
const defaultConfigs: ConfigMap = {
  // ── server.properties ──────────────────────────────────────────────
  'server.properties': {
    'allow-flight': false,
    'broadcast-console-to-ops': true,
    'broadcast-rcon-to-ops': true,
    'difficulty': 'easy',
    'enable-command-block': false,
    'enable-query': false,
    'enable-rcon': false,
    'enable-status': true,
    'enforce-secure-profile': true,
    'enforce-whitelist': false,
    'entity-broadcast-range-percentage': 100,
    'force-gamemode': false,
    'function-permission-level': 2,
    'gamemode': 'survival',
    'generate-structures': true,
    'hardcore': false,
    'hide-online-players': false,
    'level-name': 'world',
    'level-type': 'minecraft:normal',
    'log-ips': true,
    'max-chained-neighbor-updates': 1000000,
    'max-players': 20,
    'max-tick-time': 60000,
    'max-world-size': 29999984,
    'motd': 'A Minecraft Server',
    'network-compression-threshold': 256,
    'online-mode': true,
    'op-permission-level': 4,
    'pause-when-empty-seconds': 60,
    'pvp': true,
    'rate-limiting': 0,
    'require-resource-pack': false,
    'server-port': 25565,
    'simulation-distance': 10,
    'spawn-animals': true,
    'spawn-monsters': true,
    'spawn-npcs': true,
    'spawn-protection': 16,
    'sync-chunk-writes': true,
    'use-native-transport': true,
    'view-distance': 10,
    'white-list': false,
  },

  // ── bukkit.yml ─────────────────────────────────────────────────────
  // Source: https://docs.papermc.io/paper/reference/bukkit-configuration/
  'bukkit.yml': {
    'settings.allow-end': true,
    'settings.warn-on-overload': true,
    'settings.query-plugins': true,
    'settings.connection-throttle': 4000,
    'settings.minimum-api': 'none',
    'settings.use-map-color-cache': true,
    // spawn-limits
    'spawn-limits.monsters': 70,
    'spawn-limits.animals': 10,
    'spawn-limits.water-animals': 5,
    'spawn-limits.water-ambient': 20,
    'spawn-limits.water-underground-creature': 5,
    'spawn-limits.axolotls': 5,
    'spawn-limits.ambient': 15,
    // ticks-per
    'ticks-per.animal-spawns': 400,
    'ticks-per.monster-spawns': 1,
    'ticks-per.water-spawns': 1,
    'ticks-per.water-ambient-spawns': 1,
    'ticks-per.water-underground-creature-spawns': 1,
    'ticks-per.axolotl-spawns': 1,
    'ticks-per.ambient-spawns': 1,
    'ticks-per.autosave': 6000,
    'chunk-gc.period-in-ticks': 600,
  },

  // ── spigot.yml ─────────────────────────────────────────────────────
  // Source: https://docs.papermc.io/paper/reference/spigot-configuration/
  'spigot.yml': {
    'settings.debug': false,
    'settings.save-user-cache-on-stop-only': false,
    'settings.sample-count': 12,
    'settings.timeout-time': 60,
    'settings.restart-on-crash': true,
    'settings.log-villager-deaths': true,
    'settings.log-named-deaths': true,
    'settings.bungeecord': false,
    'settings.netty-threads': 4,
    'settings.player-shuffle': 0,
    'settings.user-cache-size': 1000,
    'settings.moved-too-quickly-multiplier': 10.0,
    'settings.moved-wrongly-threshold': 0.0625,
    // world-settings defaults
    'world-settings.default.below-zero-generation-in-existing-chunks': true,
    'world-settings.default.mob-spawn-range': 8,
    // merge-radius
    'world-settings.default.merge-radius.item': 0.5,
    'world-settings.default.merge-radius.exp': -1,
    // ticks-per
    'world-settings.default.ticks-per.hopper-check': 1,
    'world-settings.default.ticks-per.hopper-transfer': 8,
    'world-settings.default.ticks-per.hopper-amount': 1,
    'world-settings.default.ticks-per.hopper-can-load-chunks': false,
    // hunger
    'world-settings.default.hunger.combat-exhaustion': 0.1,
    'world-settings.default.hunger.jump-sprint-exhaustion': 0.2,
    'world-settings.default.hunger.jump-walk-exhaustion': 0.05,
    'world-settings.default.hunger.other-multiplier': 0.0,
    'world-settings.default.hunger.regen-exhaustion': 6.0,
    'world-settings.default.hunger.sprint-multiplier': 0.1,
    'world-settings.default.hunger.swim-multiplier': 0.01,
    // entity-activation-range
    'world-settings.default.entity-activation-range.animals': 32,
    'world-settings.default.entity-activation-range.monsters': 32,
    'world-settings.default.entity-activation-range.raiders': 48,
    'world-settings.default.entity-activation-range.misc': 16,
    'world-settings.default.entity-activation-range.water': 16,
    'world-settings.default.entity-activation-range.villagers': 32,
    'world-settings.default.entity-activation-range.flying-monsters': 32,
    // entity-tracking-range
    'world-settings.default.entity-tracking-range.players': 128,
    'world-settings.default.entity-tracking-range.animals': 96,
    'world-settings.default.entity-tracking-range.monsters': 96,
    'world-settings.default.entity-tracking-range.misc': 96,
    'world-settings.default.entity-tracking-range.other': 64,
    'world-settings.default.entity-tracking-range.display': 128,
    // misc world-settings
    'world-settings.default.item-despawn-rate': 6000,
    'world-settings.default.arrow-despawn-rate': 1200,
    'world-settings.default.trident-despawn-rate': 1200,
    'world-settings.default.zombie-aggressive-towards-villager': true,
    'world-settings.default.nerf-spawner-mobs': false,
    'world-settings.default.enable-zombie-pigmen-portal-spawns': true,
    'world-settings.default.simulation-distance': 10,
    'world-settings.default.max-tnt-per-tick': 100,
    'world-settings.default.growth.cactus-modifier': 100,
    'world-settings.default.growth.cane-modifier': 100,
    'world-settings.default.growth.melon-modifier': 100,
    'world-settings.default.growth.mushroom-modifier': 100,
    'world-settings.default.growth.pumpkin-modifier': 100,
    'world-settings.default.growth.sapling-modifier': 100,
    'world-settings.default.growth.beetroot-modifier': 100,
    'world-settings.default.growth.carrot-modifier': 100,
    'world-settings.default.growth.potato-modifier': 100,
    'world-settings.default.growth.wheat-modifier': 100,
    'world-settings.default.growth.netherwart-modifier': 100,
    'world-settings.default.growth.vine-modifier': 100,
    'world-settings.default.growth.cocoa-modifier': 100,
    'world-settings.default.growth.bamboo-modifier': 100,
    'world-settings.default.growth.sweetberry-modifier': 100,
    'world-settings.default.growth.kelp-modifier': 100,
    'world-settings.default.growth.twistingvines-modifier': 100,
    'world-settings.default.growth.weepingvines-modifier': 100,
    'world-settings.default.growth.cavevines-modifier': 100,
    'world-settings.default.growth.glowberry-modifier': 100,
    'world-settings.default.growth.pitcherplant-modifier': 100,
    'world-settings.default.growth.torchflower-modifier': 100,
  },

  // ── config/paper-world-defaults.yml ────────────────────────────────
  // Source: https://docs.papermc.io/paper/reference/world-configuration/
  'config/paper-world-defaults.yml': {
    // anticheat.anti-xray
    'anticheat.anti-xray.enabled': false,
    'anticheat.anti-xray.engine-mode': 1,
    'anticheat.anti-xray.lava-obscures': false,
    'anticheat.anti-xray.max-block-height': 64,
    'anticheat.anti-xray.update-radius': 2,
    'anticheat.anti-xray.use-permission': false,
    // chunks
    'chunks.delay-chunk-unloads-by': '10s',
    'chunks.entity-per-chunk-save-limit.arrow': -1,
    'chunks.entity-per-chunk-save-limit.ender_pearl': -1,
    'chunks.entity-per-chunk-save-limit.experience_orb': -1,
    'chunks.entity-per-chunk-save-limit.fireball': -1,
    'chunks.entity-per-chunk-save-limit.small_fireball': -1,
    'chunks.entity-per-chunk-save-limit.snowball': -1,
    'chunks.flush-regions-on-save': false,
    'chunks.max-auto-save-chunks-per-tick': 24,
    'chunks.prevent-moving-into-unloaded-chunks': false,
    // collisions
    'collisions.allow-player-cramming-damage': false,
    'collisions.allow-vehicle-collisions': true,
    'collisions.fix-climbing-bypassing-cramming-rule': false,
    'collisions.max-entity-collisions': 8,
    'collisions.only-players-collide': false,
    // command-blocks
    'command-blocks.force-follow-perm-level': true,
    'command-blocks.permissions-level': 2,
    // entities.armor-stands
    'entities.armor-stands.do-collision-entity-lookups': true,
    'entities.armor-stands.tick': true,
    // entities.behavior
    'entities.behavior.allow-spider-world-border-climbing': true,
    'entities.behavior.baby-zombie-movement-modifier': 0.5,
    'entities.behavior.nerf-pigmen-from-nether-portals': false,
    'entities.behavior.parrots-are-unaffected-by-player-movement': false,
    'entities.behavior.phantoms-do-not-spawn-on-creative-players': true,
    'entities.behavior.phantoms-only-attack-insomniacs': true,
    'entities.behavior.piglins-guard-chests': true,
    'entities.behavior.should-remove-dragon': false,
    // entities.spawning
    'entities.spawning.alt-item-despawn-rate.enabled': false,
    'entities.spawning.despawn-ranges.ambient.hard': 128,
    'entities.spawning.despawn-ranges.ambient.soft': 32,
    'entities.spawning.despawn-ranges.axolotl.hard': 128,
    'entities.spawning.despawn-ranges.axolotl.soft': 32,
    'entities.spawning.despawn-ranges.creature.hard': 128,
    'entities.spawning.despawn-ranges.creature.soft': 32,
    'entities.spawning.despawn-ranges.misc.hard': 128,
    'entities.spawning.despawn-ranges.misc.soft': 32,
    'entities.spawning.despawn-ranges.monster.hard': 128,
    'entities.spawning.despawn-ranges.monster.soft': 32,
    'entities.spawning.despawn-ranges.underground_water_creature.hard': 128,
    'entities.spawning.despawn-ranges.underground_water_creature.soft': 32,
    'entities.spawning.despawn-ranges.water_ambient.hard': 128,
    'entities.spawning.despawn-ranges.water_ambient.soft': 32,
    'entities.spawning.despawn-ranges.water_creature.hard': 128,
    'entities.spawning.despawn-ranges.water_creature.soft': 32,
    'entities.spawning.ender-dragons-death-always-places-dragon-egg': false,
    'entities.spawning.experience-merge-max-value': -1,
    'entities.spawning.iron-golems-can-spawn-in-air': false,
    'entities.spawning.monster-spawn-max-light-level': -1,
    'entities.spawning.non-player-arrow-despawn-rate': -1,
    'entities.spawning.per-player-mob-spawns': true,
    'entities.spawning.scan-for-legacy-ender-dragon': true,
    'entities.spawning.skeleton-horse-thunder-spawn-chance': -1,
    // entities.spawning.spawn-limits (all -1 = inherit from bukkit.yml)
    'entities.spawning.spawn-limits.ambient': -1,
    'entities.spawning.spawn-limits.axolotls': -1,
    'entities.spawning.spawn-limits.creature': -1,
    'entities.spawning.spawn-limits.monster': -1,
    'entities.spawning.spawn-limits.underground_water_creature': -1,
    'entities.spawning.spawn-limits.water_ambient': -1,
    'entities.spawning.spawn-limits.water_creature': -1,
    // entities.spawning.ticks-per-spawn (all -1 = inherit from bukkit.yml)
    'entities.spawning.ticks-per-spawn.ambient': -1,
    'entities.spawning.ticks-per-spawn.axolotls': -1,
    'entities.spawning.ticks-per-spawn.creature': -1,
    'entities.spawning.ticks-per-spawn.monster': -1,
    'entities.spawning.ticks-per-spawn.underground_water_creature': -1,
    'entities.spawning.ticks-per-spawn.water_ambient': -1,
    'entities.spawning.ticks-per-spawn.water_creature': -1,
    // entities.tracking-range-y
    'entities.tracking-range-y.enabled': false,
    // environment
    'environment.disable-explosion-knockback': false,
    'environment.disable-ice-and-snow': false,
    'environment.disable-thunder': false,
    'environment.frosted-ice.delay.max': 40,
    'environment.frosted-ice.delay.min': 20,
    'environment.frosted-ice.enabled': true,
    'environment.optimize-explosions': false,
    'environment.treasure-maps.enabled': true,
    'environment.treasure-maps.find-already-discovered.loot-tables': false,
    'environment.treasure-maps.find-already-discovered.villager-trade': false,
    // feature-seeds
    'feature-seeds.generate-random-seeds-for-all': false,
    // lootables
    'lootables.auto-replenish': false,
    'lootables.max-refills': -1,
    'lootables.refresh-max': '2d',
    'lootables.refresh-min': '12h',
    'lootables.reset-seed-on-fill': true,
    'lootables.restrict-player-reloot': true,
    // misc
    'misc.disable-end-credits': false,
    'misc.light-queue-size': 20,
    'misc.max-leash-distance': 10.0,
    'misc.redstone-implementation': 'VANILLA',
    'misc.shield-blocking-delay': 5,
    'misc.show-sign-click-command-failure-msgs-to-player': false,
    'misc.update-pathfinding-on-block-update': true,
    // tick-rates
    'tick-rates.behavior.villager.acquirepoi': -1,
    'tick-rates.behavior.villager.validatenearbypoi': -1,
    'tick-rates.sensor.villager.nearestbedsensor': -1,
    'tick-rates.sensor.villager.nearestlivingentitysensor': -1,
    'tick-rates.sensor.villager.playersensor': -1,
    'tick-rates.sensor.villager.secondarypoisensor': -1,
    'tick-rates.sensor.villager.villagerbabiessensor': -1,
  },

  // ── config/paper-global.yml ────────────────────────────────────────
  // Source: https://docs.papermc.io/paper/reference/global-configuration/
  'config/paper-global.yml': {
    // chunk-loading
    'chunk-loading-advanced.auto-config-send-distance': true,
    'chunk-loading-advanced.player-max-concurrent-chunk-generates': 0,
    'chunk-loading-advanced.player-max-concurrent-chunk-loads': 0,
    'chunk-loading-basic.player-max-chunk-generate-rate': -1,
    'chunk-loading-basic.player-max-chunk-load-rate': 100,
    'chunk-loading-basic.player-max-chunk-send-rate': 75,
    // chunk-system
    'chunk-system.io-threads': -1,
    'chunk-system.worker-threads': -1,
    // collisions
    'collisions.enable-player-collisions': true,
    'collisions.send-full-pos-for-hard-colliding-entities': true,
    // commands
    'commands.suggest-player-names-when-null-tab-completions': true,
    'commands.time-command-affects-all-worlds': false,
    // item-validation
    'item-validation.book-size.page-max': 2560,
    'item-validation.resolve-selectors-in-books': false,
    // misc
    'misc.compression-level': -1,
    'misc.enable-nether': true,
    'misc.fix-far-end-terrain-generation': true,
    'misc.load-permissions-yml-before-plugins': true,
    'misc.max-joins-per-tick': 5,
    'misc.region-file-cache-size': 256,
    'misc.send-full-pos-for-item-entities': false,
    // packet-limiter
    'packet-limiter.all-packets.action': 'KICK',
    'packet-limiter.all-packets.interval': 7.0,
    'packet-limiter.all-packets.max-packet-rate': 500.0,
    // player-auto-save
    'player-auto-save.max-per-tick': -1,
    'player-auto-save.rate': -1,
    // proxies
    'proxies.bungee-cord.online-mode': true,
    'proxies.bungee-cord.proxy-protocol': false,
    'proxies.velocity.enabled': false,
    'proxies.velocity.online-mode': true,
    // scoreboards
    'scoreboards.save-empty-scoreboard-teams': true,
    'scoreboards.track-plugin-scoreboards': false,
    // spam-limiter
    'spam-limiter.incoming-packet-threshold': 300,
    'spam-limiter.recipe-spam-increment': 1,
    'spam-limiter.recipe-spam-limit': 20,
    'spam-limiter.tab-spam-increment': 1,
    'spam-limiter.tab-spam-limit': 500,
    // watchdog
    'watchdog.early-warning-delay': 10000,
    'watchdog.early-warning-every': 5000,
  },
};

export const useLessLagStore = create<LessLagState>((set, get) => ({
  // ─── Defaults ───
  profile: 'SMP',
  tier: 'MID',
  aggressiveness: 'BALANCED',
  playerCount: 20,
  plugins: [],
  hardware: defaultHardware,
  platform: defaultPlatform,
  configs: defaultConfigs,
  preset: null,
  evaluation: null,
  diffs: [],
  selectedProposals: new Set<string>(),
  activeTab: 'presets',
  showImportModal: false,
  toasts: [],
  setupProgress: defaultSetupProgress,
  snapshots: [],
  currentSnapshotId: null,
  connectedServerId: null,
  connectedServerName: null,
  setConnectedServer: (id, name) => set({ connectedServerId: id, connectedServerName: name }),

  // ─── Setters ───
  setProfile: (p) => set({ profile: p }),
  setTier: (t) => set({ tier: t }),
  setAggressiveness: (a) => set({ aggressiveness: a }),
  setPlayerCount: (n) => set({ playerCount: n }),
  setPlugins: (plugins) => set({ plugins }),
  setHardware: (hw) => set((s) => ({ hardware: { ...s.hardware, ...hw } })),
  setPlatform: (pl) => set((s) => ({ platform: { ...s.platform, ...pl } })),
  setConfigs: (configs) => set({ configs }),
  updateConfig: (file, key, value) =>
    set((s) => ({
      configs: {
        ...s.configs,
        [file]: { ...s.configs[file], [key]: value },
      },
    })),
  setActiveTab: (tab) => set({ activeTab: tab }),
  setShowImportModal: (show) => set({ showImportModal: show }),
  addToast: (message, type = 'info') =>
    set((s) => ({
      toasts: [...s.toasts.slice(-4), { id: crypto.randomUUID(), message, type }],
    })),
  dismissToast: (id) =>
    set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })),
  completeSetupStep: (step) =>
    set((s) => ({
      setupProgress: markStepDone(s.setupProgress, step),
    })),
  setSetupStepReady: (step) =>
    set((s) => ({
      setupProgress: ensureStepReady(s.setupProgress, step),
    })),

  // ─── Engine actions ───
  runEvaluation: () => {
    const s = get();
    const input: EvaluationInput = {
      profile: s.profile,
      tier: s.tier,
      aggressiveness: s.aggressiveness,
      plugins: s.plugins,
      hardware: s.hardware,
      platform: s.platform,
      configs: s.configs,
    };
    const output = evaluate(input);
    const diffs = generateDiffs(output.proposals);
    const selected = new Set(diffs.map((d) => `${d.file}:${d.key}`));
    set({ evaluation: output, diffs, selectedProposals: selected });
  },

  generatePresetAction: () => {
    const s = get();
    const preset = generatePreset(s.profile, s.tier, s.aggressiveness, s.playerCount, s.platform.fork);
    set({
      preset,
      setupProgress: markStepDone(s.setupProgress, 'preset'),
    });
  },

  applyPreset: () => {
    const s = get();
    if (!s.preset) return;
    // Apply preset settings to configs
    const newConfigs = structuredClone(s.configs);
    for (const [key, val] of Object.entries(s.preset.settings)) {
      // Parse keys like "server.view-distance" → file="server.properties", key="view-distance"
      // or "bukkit.spawn-limits.monsters" → file="bukkit.yml"
      // or "modules.xyz" → file="config.yml"
      const [fileHint, ...rest] = key.split('.');
      let file: string, configKey: string;
      if (fileHint === 'server') {
        file = 'server.properties';
        configKey = rest.join('.');
      } else if (fileHint === 'bukkit') {
        file = 'bukkit.yml';
        configKey = rest.join('.');
      } else if (fileHint === 'spigot') {
        file = 'spigot.yml';
        configKey = rest.join('.');
      } else if (fileHint === 'paper-world') {
        file = 'config/paper-world-defaults.yml';
        configKey = rest.join('.');
      } else if (fileHint === 'paper-global') {
        file = 'config/paper-global.yml';
        configKey = rest.join('.');
      } else if (fileHint === 'purpur') {
        file = 'purpur.yml';
        configKey = rest.join('.');
      } else if (fileHint === 'pufferfish') {
        file = 'pufferfish.yml';
        configKey = rest.join('.');
      } else if (fileHint === 'modules' || fileHint === 'automation' || fileHint === 'workload-limit-ms') {
        file = 'config.yml';
        configKey = key;
      } else {
        file = 'config.yml';
        configKey = key;
      }
      if (!newConfigs[file]) newConfigs[file] = {};
      newConfigs[file]![configKey] = parseValue(val);
    }
    set({ configs: newConfigs });
  },

  // ─── Proposal selection ───
  toggleProposal: (key) =>
    set((s) => {
      const next = new Set(s.selectedProposals);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return { selectedProposals: next };
    }),
  selectAllProposals: () =>
    set((s) => ({
      selectedProposals: new Set(s.diffs.map((d) => `${d.file}:${d.key}`)),
    })),
  deselectAllProposals: () => set({ selectedProposals: new Set() }),
  applySelectedProposals: () => {
    const s = get();
    const newConfigs = structuredClone(s.configs);
    let appliedCount = 0;
    for (const d of s.diffs) {
      const key = `${d.file}:${d.key}`;
      if (!s.selectedProposals.has(key)) continue;
      if (!newConfigs[d.file]) newConfigs[d.file] = {};
      newConfigs[d.file]![d.key] = parseValue(d.after);
      appliedCount += 1;
    }

    const nextSetupProgress = appliedCount > 0
      ? markStepDone(s.setupProgress, 'changes')
      : s.setupProgress;

    set({ configs: newConfigs, setupProgress: nextSetupProgress });
  },

  // ─── Snapshots ───
  saveSnapshot: (label) =>
    set((s) => ({
      snapshots: [
        ...s.snapshots,
        {
          id: crypto.randomUUID(),
          label,
          timestamp: Date.now(),
          configs: structuredClone(s.configs),
        },
      ],
    })),
  restoreSnapshot: (id) =>
    set((s) => {
      const snap = s.snapshots.find((x) => x.id === id);
      if (!snap) return {};
      return { configs: structuredClone(snap.configs), currentSnapshotId: id };
    }),
  deleteSnapshot: (id) =>
    set((s) => ({
      snapshots: s.snapshots.filter((x) => x.id !== id),
      currentSnapshotId: s.currentSnapshotId === id ? null : s.currentSnapshotId,
    })),

  // ─── Import/Export ───
  importConfigs: (files) => {
    const newConfigs: ConfigMap = {};
    for (const [filename, content] of Object.entries(files)) {
      newConfigs[filename] = parseConfig(content, filename);
    }
    set((s) => ({ configs: { ...s.configs, ...newConfigs } }));
  },
  exportConfigs: () => {
    const s = get();
    const result: Record<string, string> = {};
    for (const [file, config] of Object.entries(s.configs)) {
      result[file] = Object.entries(config ?? {})
        .map(([k, v]) => {
          if (file.endsWith('.properties')) return `${k}=${v}`;
          return `${k}: ${v}`;
        })
        .join('\n');
    }
    return result;
  },
}));

function parseValue(val: string): string | number | boolean {
  if (val === 'true') return true;
  if (val === 'false') return false;
  const n = Number(val);
  if (!isNaN(n) && val.trim() !== '') return n;
  return val;
}
