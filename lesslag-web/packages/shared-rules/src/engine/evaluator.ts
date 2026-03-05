/**
 * @file evaluator.ts
 *
 * LessLag rule engine — plugin-based orchestrator.
 *
 * Rule groups live in `./rules/` and are each responsible for a domain.
 * To add a new rule group without modifying this file, call `registerRule()`.
 *
 * Public API:
 *   evaluate(input): EvaluationOutput
 *   registerRule(ruleGroup): void   ← custom extension point
 */

import type { EvaluationInput, EvaluationOutput, EvaluationSummary } from '../types/config.js';
import type { RuleResult, PatchProposal } from '../types/rule-result.js';
import { buildRuleResult } from '../types/rule-result.js';

// Built-in rule groups
import { evaluateSafetyRules } from './rules/safety.js';
import { evaluateConsistencyRules } from './rules/consistency.js';
import { evaluateConflictRules } from './rules/conflict.js';
import { evaluateForkSpecificRules } from './rules/fork-specific.js';
import { evaluatePerformanceTuningRules } from './rules/performance.js';

// ─── Rule function type ──────────────────────────────────────

/**
 * Signature for all rule group functions.
 *
 * @param input     Full evaluation input (hardware, configs, platform, profile, tier…)
 * @param results   Accumulator for RuleResult entries (push here)
 * @param proposals Accumulator for PatchProposal entries (push here)
 * @param seen      Deduplication set — add "file:key" strings you've already proposed
 */
export type RuleGroupFn = (
  input: EvaluationInput,
  results: RuleResult[],
  proposals: PatchProposal[],
  seen: Set<string>,
) => void;

// ─── Rule registry ───────────────────────────────────────────

const _builtin: RuleGroupFn[] = [
  evaluateSafetyRules,
  evaluateConsistencyRules,
  evaluateConflictRules,
  evaluateForkSpecificRules,
  evaluatePerformanceTuningRules,
];

const _custom: RuleGroupFn[] = [];

/**
 * Register a custom rule group that will run after all built-in groups.
 *
 * @example
 * import { registerRule } from '@lesslag/shared-rules';
 * registerRule((input, results, proposals) => {
 *   if (input.plugins.includes('MyPlugin')) {
 *     results.push({ ... });
 *   }
 * });
 */
export function registerRule(fn: RuleGroupFn): void {
  _custom.push(fn);
}

// ─── Core evaluator ──────────────────────────────────────────

export function evaluate(input: EvaluationInput): EvaluationOutput {
  const results: RuleResult[] = [];
  const proposals: PatchProposal[] = [];
  const seen = new Set<string>();

  const allGroups = [..._builtin, ..._custom];

  for (const fn of allGroups) {
    try {
      fn(input, results, proposals, seen);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      results.push(buildRuleResult(`error-${fn.name || 'unknown'}`, {
        group: 'internal',
        severity: 'WARNING',
        confidence: 1.0,
        why: `Rule group '${fn.name || 'unknown'}' threw an exception: ${msg}`,
        impact: "This rule group's recommendations are unavailable",
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
      (typeof p.targetFile === 'string' && p.targetFile.includes('paper-world') &&
        p.configKey.includes('chunk')),
  );

  return {
    results,
    proposals: dedupedProposals,
    summary: computeSummary(results, dedupedProposals),
    pregenerateReminder,
  };
}

// ─── Summary ─────────────────────────────────────────────────

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
