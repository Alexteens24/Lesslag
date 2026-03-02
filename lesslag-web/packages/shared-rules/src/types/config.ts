import type { GameProfile, HardwareTier, AggressivenessLevel, ServerFork, TargetFile } from './enums';

/** Immutable preset output: recommended config values + label. */
export interface PresetProfile {
  gameProfile: GameProfile;
  hardwareTier: HardwareTier;
  aggressiveness: AggressivenessLevel;
  settings: Record<string, string>;
  label: string;
  description: string;
}

/** Hardware assessment input for rules. */
export interface HardwareProfile {
  availableProcessors: number;
  cpuModel: string;
  maxHeapMB: number;
  gcOverheadPercent: number;
  averageMspt: number;
}

/** Platform info for the rule engine. */
export interface PlatformInfo {
  fork: ServerFork;
  version: string;
  isPaper: boolean;
  isPurpur: boolean;
  isPufferfish: boolean;
  isLeaf: boolean;
  hasFolia: boolean;
}

/** Config map: file -> flat key-value map. */
export type ConfigMap = Partial<Record<TargetFile | string, Record<string, unknown>>>;

/** Evaluation input. */
export interface EvaluationInput {
  platform: PlatformInfo;
  configs: ConfigMap;
  plugins: string[];
  hardware: HardwareProfile;
  profile: GameProfile;
  tier: HardwareTier;
  aggressiveness: AggressivenessLevel;
}

/** Evaluation output. */
export interface EvaluationOutput {
  results: import('./rule-result').RuleResult[];
  proposals: import('./rule-result').PatchProposal[];
  summary: EvaluationSummary;
}

export interface EvaluationSummary {
  totalResults: number;
  totalProposals: number;
  bySeverity: Record<string, number>;
  byRisk: Record<string, number>;
  autoApplicable: number;
  recommendOnly: number;
}
