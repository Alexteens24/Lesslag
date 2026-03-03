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
  isLuminol: boolean;
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
  /** True when pre-generating chunks is recommended alongside the proposed changes. */
  pregenerateReminder?: boolean;
}

export interface EvaluationSummary {
  totalResults: number;
  totalProposals: number;
  bySeverity: Record<string, number>;
  byRisk: Record<string, number>;
  autoApplicable: number;
  recommendOnly: number;
}

/** Payload sent from the plugin to the web app via base64-encoded URL token. */
export interface ServerPayload {
  cpuModel: string;
  cores: number;
  maxHeapMb: number;
  physicalRamMb: number;
  javaVersion: number;
  jvmFlags: string[];
  fork: string;
  mcVersion: string;
  pluginNames: string[];
  tps: number;
  mspt: number;
}

/** The JSON file exported by the web configurator and consumed by `/lg apply`. */
export interface LessLagConfigJson {
  /** Flat key→value map for LessLag config.yml entries (auto-applied by plugin). */
  lesslag: Record<string, unknown>;
  /** Nested file→{key→value} map for server configs (verified by plugin, manual apply). */
  server_config_expectations: Record<string, Record<string, unknown>>;
}

/** Result of the startup command builder (JVM flags recommendation). */
export interface StartupCommandResult {
  command: string;
  gcType: 'ZGC' | 'G1GC';
  reason: string;
}

/** Per-file checklist of expected server config values for the verify/export UI. */
export type ServerConfigChecklist = Record<
  string,
  { key: string; currentValue: unknown; expectedValue: unknown; rationale: string }[]
>;
