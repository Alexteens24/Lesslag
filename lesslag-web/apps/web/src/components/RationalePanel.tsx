'use client';

import { useLessLagStore } from '@/store/lesslag-store';

const SEVERITY_STYLES = {
  INFO: { color: 'text-[var(--info)]', bg: 'bg-[var(--info)]/10', icon: 'ℹ️' },
  WARNING: { color: 'text-[var(--warning)]', bg: 'bg-[var(--warning)]/10', icon: '⚠️' },
  CRITICAL: { color: 'text-[var(--danger)]', bg: 'bg-[var(--danger)]/10', icon: '🚨' },
} as const;

export function RationalePanel() {
  const { evaluation } = useLessLagStore();

  if (!evaluation) {
    return (
      <div className="flex flex-col items-center justify-center py-16 text-[var(--text-muted)]">
        <p className="text-lg">No analysis results yet</p>
        <p className="mt-1 text-sm">Generate recommendations first using the Presets tab</p>
      </div>
    );
  }

  const { results, summary } = evaluation;

  return (
    <div className="space-y-6">
      {/* Summary bar */}
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
        <SummaryCard label="Total Findings" value={summary.totalResults} />
        <SummaryCard label="Proposals" value={summary.totalProposals} />
        <SummaryCard label="Auto-applicable" value={summary.autoApplicable} accent />
        <SummaryCard label="Manual Only" value={summary.recommendOnly} />
      </div>

      <div className="grid grid-cols-3 gap-3">
        <MiniStat label="Critical" value={summary.bySeverity['CRITICAL'] ?? 0} color="text-[var(--danger)]" />
        <MiniStat label="Warning" value={summary.bySeverity['WARNING'] ?? 0} color="text-[var(--warning)]" />
        <MiniStat label="Info" value={summary.bySeverity['INFO'] ?? 0} color="text-[var(--info)]" />
      </div>

      {/* Results list */}
      <div className="space-y-3">
        {results.map((r, i) => {
          const style = SEVERITY_STYLES[r.severity];
          return (
            <details
              key={`${r.ruleId}-${i}`}
              className="group rounded-lg border border-[var(--border)] bg-[var(--bg-card)] overflow-hidden"
            >
              <summary className="flex cursor-pointer items-center gap-3 px-4 py-3 hover:bg-[var(--bg-elevated)] transition-colors">
                <span>{style.icon}</span>
                <span className={`rounded px-1.5 py-0.5 text-[10px] font-semibold ${style.color} ${style.bg}`}>
                  {r.severity}
                </span>
                <span className="flex-1 text-sm text-[var(--text-primary)]">{r.why}</span>
                <span className="text-xs text-[var(--text-muted)]">
                  {Math.round(r.confidence * 100)}% conf.
                </span>
              </summary>
              <div className="border-t border-[var(--border)] px-4 py-3 space-y-3">
                <div>
                  <div className="mb-1 text-xs font-medium uppercase text-[var(--text-muted)]">Impact</div>
                  <p className="text-sm text-[var(--text-secondary)]">{r.impact || 'N/A'}</p>
                </div>
                {r.tradeoff && (
                  <div>
                    <div className="mb-1 text-xs font-medium uppercase text-[var(--text-muted)]">Trade-off</div>
                    <p className="text-sm text-[var(--text-secondary)]">{r.tradeoff}</p>
                  </div>
                )}
                <div>
                  <div className="mb-1 text-xs font-medium uppercase text-[var(--text-muted)]">Recommendation</div>
                  <p className="text-sm text-[var(--text-primary)]">{r.recommendationText || 'N/A'}</p>
                </div>
                {r.manualSteps && (
                  <div>
                    <div className="mb-1 text-xs font-medium uppercase text-[var(--text-muted)]">Manual Steps</div>
                    <pre className="whitespace-pre-wrap rounded bg-[var(--bg-primary)] p-2 text-xs text-[var(--text-secondary)] font-mono">
                      {r.manualSteps}
                    </pre>
                  </div>
                )}
                <div className="flex items-center gap-4 text-xs text-[var(--text-muted)]">
                  <span>Rule: {r.ruleId}</span>
                  <span>Group: {r.ruleGroup}</span>
                  {r.impactedKeys && r.impactedKeys.length > 0 && (
                    <span>Keys: {r.impactedKeys.join(', ')}</span>
                  )}
                </div>
              </div>
            </details>
          );
        })}
      </div>
    </div>
  );
}

function SummaryCard({ label, value, accent }: { label: string; value: number; accent?: boolean }) {
  return (
    <div className="rounded-lg border border-[var(--border)] bg-[var(--bg-card)] p-4">
      <div className="text-xs text-[var(--text-muted)] uppercase">{label}</div>
      <div className={`mt-1 text-2xl font-bold ${accent ? 'text-[var(--accent)]' : 'text-[var(--text-primary)]'}`}>
        {value}
      </div>
    </div>
  );
}

function MiniStat({ label, value, color }: { label: string; value: number; color: string }) {
  return (
    <div className="rounded border border-[var(--border)] bg-[var(--bg-card)] px-3 py-2 text-center">
      <div className={`text-lg font-bold ${color}`}>{value}</div>
      <div className="text-xs text-[var(--text-muted)]">{label}</div>
    </div>
  );
}
