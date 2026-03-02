// Types
export type {
  RuleResult,
  PatchProposal,
} from './types/rule-result';

export type {
  PresetProfile,
  HardwareProfile,
  PlatformInfo,
  ConfigMap,
  EvaluationInput,
  EvaluationOutput,
  EvaluationSummary,
} from './types/config';

// Enums & constants
export {
  GameProfiles,
  HardwareTiers,
  AggressivenessLevels,
  Severities,
  RiskTags,
  ApplyScopes,
  SessionStatuses,
  ServerForks,
  TargetFiles,
  GameProfileMeta,
  HardwareTierMeta,
  AggressivenessLevelMeta,
} from './types/enums';

export type {
  GameProfile,
  HardwareTier,
  AggressivenessLevel,
  Severity,
  RiskTag,
  ApplyScope,
  SessionStatus,
  ServerFork,
  TargetFile,
} from './types/enums';

// Builders
export { buildRuleResult, buildPatch } from './types/rule-result';

// Engine
export { generatePreset, applyLoadModifier } from './engine/preset-generator';
export { evaluate } from './engine/evaluator';
export {
  generateDiffs,
  groupDiffsByFile,
  renderFileDiff,
  renderFullDiff,
  applyDiffsToConfig,
} from './engine/diff-generator';
export type { ConfigDiff } from './engine/diff-generator';

// Utilities
export {
  parseProperties,
  parseSimpleYaml,
  parseConfig,
  serializeProperties,
  detectFormat,
} from './util/yaml-parser';
