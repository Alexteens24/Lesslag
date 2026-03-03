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

const defaultConfigs: ConfigMap = {
  'server.properties': {
    'online-mode': 'true',
    'view-distance': 10,
    'simulation-distance': 10,
    'allow-flight': 'false',
    'pause-when-empty-seconds': '60',
  },
  'bukkit.yml': {
    'spawn-limits.monsters': 70,
    'spawn-limits.animals': 10,
    'spawn-limits.ambient': 15,
    'ticks-per.animal-spawns': 400,
  },
  'spigot.yml': {
    'world-settings.default.simulation-distance': 10,
    'world-settings.default.mob-spawn-range': 8,
    'world-settings.default.merge-radius.item': -1,
    'world-settings.default.merge-radius.exp': -1,
    'world-settings.default.entity-tracking-range.players': 128,
  },
  'config/paper-world-defaults.yml': {
    'misc.redstone-implementation': 'VANILLA',
    'entities.spawning.per-player-mob-spawns': true,
    'chunks.prevent-moving-into-unloaded-chunks': true,
    'collisions.max-entity-collisions': 8,
    'collisions.fix-climbing-bypassing-cramming-rule': true,
    'environment.optimize-explosions': false,
    'environment.treasure-maps.find-already-discovered.villager-trade': false,
    'feature-seeds.generate-random-seeds-for-all': false,
    'chunks.delay-chunk-unloads-by': '10s',
    'chunks.max-auto-save-chunks-per-tick': 24,
    'entities.spawning.alt-item-despawn-rate.enabled': false,
    'chunks.entity-per-chunk-save-limit.arrow': -1,
    'entities.armor-stands.tick': true,
    'entities.armor-stands.do-collision-entity-lookups': true,
    'entities.tracking-range-y.enabled': false,
  },
  'config/paper-global.yml': {
    'chunk-system.worker-threads': -1,
    'chunk-system.io-threads': -1,
    'item-validation.book-size.page-max': 2560,
    'item-validation.resolve-selectors-in-books': false,
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
