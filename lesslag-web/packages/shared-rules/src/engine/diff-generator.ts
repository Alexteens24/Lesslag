import type { PatchProposal } from '../types/rule-result';

/**
 * A single config change represented as a unified-diff-like entry.
 */
export interface ConfigDiff {
  /** Target file path, e.g. "server.properties" */
  file: string;
  /** Dot-separated config key */
  key: string;
  /** Current value (stringified) */
  before: string;
  /** Proposed value (stringified) */
  after: string;
  /** Risk level of the change */
  risk: PatchProposal['riskTag'];
  /** Whether LessLag can auto-apply or just recommend */
  scope: PatchProposal['applyScope'];
  /** Which rule proposed this change */
  ruleId: string;
  /** Human-readable explanation */
  rationale: string;
}

/**
 * Convert proposals to ConfigDiff entries for the UI diff viewer.
 */
export function generateDiffs(proposals: PatchProposal[]): ConfigDiff[] {
  return proposals
    .filter(p => p.beforeValue !== p.afterValue)
    .map(p => ({
      file: p.targetFile,
      key: p.configKey,
      before: p.beforeValue,
      after: p.afterValue,
      risk: p.riskTag,
      scope: p.applyScope,
      ruleId: p.ruleId,
      rationale: p.rationale,
    }));
}

/**
 * Group diffs by target file for organized display.
 */
export function groupDiffsByFile(diffs: ConfigDiff[]): Map<string, ConfigDiff[]> {
  const grouped = new Map<string, ConfigDiff[]>();
  for (const d of diffs) {
    const group = grouped.get(d.file) ?? [];
    group.push(d);
    grouped.set(d.file, group);
  }
  return grouped;
}

/**
 * Generate a YAML-like text representation of changes for a single file.
 * Useful for the diff viewer component and clipboard export.
 */
export function renderFileDiff(file: string, diffs: ConfigDiff[]): string {
  const lines: string[] = [`# ${file}`];
  for (const d of diffs) {
    lines.push(`# ${d.rationale}`);
    lines.push(`# Risk: ${d.risk} | Scope: ${d.scope}`);
    lines.push(`- ${d.key}: ${d.before}`);
    lines.push(`+ ${d.key}: ${d.after}`);
    lines.push('');
  }
  return lines.join('\n');
}

/**
 * Render a full diff text for all files.
 */
export function renderFullDiff(proposals: PatchProposal[]): string {
  const diffs = generateDiffs(proposals);
  const grouped = groupDiffsByFile(diffs);
  const sections: string[] = [];
  for (const [file, fileDiffs] of grouped) {
    sections.push(renderFileDiff(file, fileDiffs));
  }
  return sections.join('\n---\n\n');
}

/**
 * Apply a set of diffs to a flat config map, returning the updated map.
 * Used for preview mode in the editor.
 */
export function applyDiffsToConfig(
  configs: Record<string, Record<string, string | number | boolean>>,
  diffs: ConfigDiff[],
): Record<string, Record<string, string | number | boolean>> {
  const result = structuredClone(configs);
  for (const d of diffs) {
    if (!result[d.file]) result[d.file] = {};
    // Try to preserve type
    const parsed = parseValue(d.after);
    result[d.file][d.key] = parsed;
  }
  return result;
}

function parseValue(val: string): string | number | boolean {
  if (val === 'true') return true;
  if (val === 'false') return false;
  const n = Number(val);
  if (!isNaN(n) && val.trim() !== '') return n;
  return val;
}
