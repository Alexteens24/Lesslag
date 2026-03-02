import type { Severity, RiskTag, ApplyScope, TargetFile } from './enums';

/** Output of a single rule evaluation. */
export interface RuleResult {
  ruleId: string;
  ruleGroup: string;
  severity: Severity;
  confidence: number; // 0.0 – 1.0
  why: string;
  impact: string;
  tradeoff: string;
  manualSteps: string | null;
  recommendationText: string;
  impactedKeys: string[]; // format: "file:key"
}

/** A single proposed configuration change. */
export interface PatchProposal {
  targetFile: TargetFile | string;
  configKey: string; // dot-path
  beforeValue: string;
  afterValue: string;
  riskTag: RiskTag;
  applyScope: ApplyScope;
  ruleId: string;
  rationale: string;
}

/** Helper to build RuleResult (mirrors Java Builder). */
export function buildRuleResult(ruleId: string, opts: {
  group?: string;
  severity?: Severity;
  confidence?: number;
  why?: string;
  impact?: string;
  tradeoff?: string;
  manualSteps?: string | null;
  recommendation?: string;
  impactedKeys?: string[];
}): RuleResult {
  return {
    ruleId,
    ruleGroup: opts.group ?? 'general',
    severity: opts.severity ?? 'INFO',
    confidence: opts.confidence ?? 1.0,
    why: opts.why ?? '',
    impact: opts.impact ?? '',
    tradeoff: opts.tradeoff ?? '',
    manualSteps: opts.manualSteps ?? null,
    recommendationText: opts.recommendation ?? '',
    impactedKeys: opts.impactedKeys ?? [],
  };
}

/** Helper to build PatchProposal. */
export function buildPatch(
  targetFile: TargetFile | string,
  configKey: string,
  beforeValue: string,
  afterValue: string,
  riskTag: RiskTag,
  applyScope: ApplyScope,
  ruleId: string,
  rationale: string,
): PatchProposal {
  return { targetFile, configKey, beforeValue, afterValue, riskTag, applyScope, ruleId, rationale };
}
