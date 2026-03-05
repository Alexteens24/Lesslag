// Types
export type {
  RuleResult,
  PatchProposal,
} from './types/rule-result.js';

export type {
  PresetProfile,
  HardwareProfile,
  PlatformInfo,
  ConfigMap,
  EvaluationInput,
  EvaluationOutput,
  EvaluationSummary,
  ServerPayload,
  LessLagConfigJson,
  StartupCommandResult,
  ServerConfigChecklist,
} from './types/config.js';

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
} from './types/enums.js';

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
} from './types/enums.js';

// Builders
export { buildRuleResult, buildPatch } from './types/rule-result.js';

// Engine
export { generatePreset, applyLoadModifier } from './engine/preset-generator.js';
export { evaluate, registerRule } from './engine/evaluator.js';
export type { RuleGroupFn } from './engine/evaluator.js';
export {
  generateDiffs,
  groupDiffsByFile,
  renderFileDiff,
  renderFullDiff,
  applyDiffsToConfig,
  generateLessLagConfigJson,
} from './engine/diff-generator.js';
export type { ConfigDiff } from './engine/diff-generator.js';

// Hardware classifier
export { classifyHardware, tierFromSpecs } from './engine/hardware-classifier.js';
export type { HardwareClassification, HardwareScoreBreakdown } from './engine/hardware-classifier.js';

// Utilities
export {
  parseProperties,
  parseSimpleYaml,
  parseConfig,
  serializeProperties,
  serializeYaml,
  detectFormat,
} from './util/yaml-parser.js';

// Version
export { RULES_VERSION } from './version.js';
