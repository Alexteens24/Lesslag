'use client';

import { useLessLagStore } from '@/store/lesslag-store';

const RISK_COLORS = {
  LOW: 'text-[var(--success)] bg-[var(--success)]/10',
  MEDIUM: 'text-[var(--warning)] bg-[var(--warning)]/10',
  HIGH: 'text-[var(--danger)] bg-[var(--danger)]/10',
} as const;

const SCOPE_LABELS = {
  RECOMMEND: 'Manual',
  LESSLAG_APPLY: 'Auto-apply',
} as const;

export function DiffViewer() {
  const {
    diffs, selectedProposals,
    toggleProposal, selectAllProposals, deselectAllProposals, applySelectedProposals,
  } = useLessLagStore();

  if (diffs.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-16 text-[var(--text-muted)]">
        <p className="text-lg">No changes to display</p>
        <p className="mt-1 text-sm">Generate recommendations first using the Presets tab</p>
      </div>
    );
  }

  // Group by file
  const grouped = new Map<string, typeof diffs>();
  for (const d of diffs) {
    const group = grouped.get(d.file) ?? [];
    group.push(d);
    grouped.set(d.file, group);
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div className="text-sm text-[var(--text-secondary)]">
          {selectedProposals.size} of {diffs.length} changes selected
        </div>
        <div className="flex gap-2">
          <button
            onClick={selectAllProposals}
            className="rounded px-3 py-1.5 text-xs font-medium text-[var(--text-secondary)] hover:text-[var(--text-primary)] border border-[var(--border)] hover:border-[var(--border-hover)] transition-colors"
          >
            Select All
          </button>
          <button
            onClick={deselectAllProposals}
            className="rounded px-3 py-1.5 text-xs font-medium text-[var(--text-secondary)] hover:text-[var(--text-primary)] border border-[var(--border)] hover:border-[var(--border-hover)] transition-colors"
          >
            Deselect All
          </button>
          <button
            onClick={applySelectedProposals}
            disabled={selectedProposals.size === 0}
            className="rounded bg-[var(--accent)] px-4 py-1.5 text-xs font-medium text-white transition-colors hover:bg-[var(--accent-hover)] disabled:opacity-50 disabled:cursor-not-allowed"
          >
            Apply Selected ({selectedProposals.size})
          </button>
        </div>
      </div>

      {Array.from(grouped.entries()).map(([file, fileDiffs]) => (
        <div key={file} className="rounded-lg border border-[var(--border)] bg-[var(--bg-card)] overflow-hidden">
          <div className="border-b border-[var(--border)] bg-[var(--bg-secondary)] px-4 py-2">
            <span className="font-mono text-sm font-semibold text-[var(--accent)]">{file}</span>
            <span className="ml-2 text-xs text-[var(--text-muted)]">
              {fileDiffs.length} change{fileDiffs.length > 1 ? 's' : ''}
            </span>
          </div>
          <div className="divide-y divide-[var(--border)]">
            {fileDiffs.map((d) => {
              const diffKey = `${d.file}:${d.key}`;
              const isSelected = selectedProposals.has(diffKey);
              return (
                <div
                  key={diffKey}
                  className={`px-4 py-3 transition-colors cursor-pointer ${
                    isSelected ? 'bg-[var(--accent)]/5' : 'hover:bg-[var(--bg-elevated)]'
                  }`}
                  onClick={() => toggleProposal(diffKey)}
                >
                  <div className="flex items-start gap-3">
                    <input
                      type="checkbox"
                      checked={isSelected}
                      readOnly
                      className="mt-1 accent-[var(--accent)]"
                    />
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2 mb-1">
                        <span className="font-mono text-sm text-[var(--text-primary)]">{d.key}</span>
                        <span className={`rounded px-1.5 py-0.5 text-[10px] font-medium ${RISK_COLORS[d.risk]}`}>
                          {d.risk}
                        </span>
                        <span className="rounded bg-[var(--bg-elevated)] px-1.5 py-0.5 text-[10px] text-[var(--text-muted)]">
                          {SCOPE_LABELS[d.scope]}
                        </span>
                      </div>
                      <div className="font-mono text-sm">
                        <span className="text-[var(--danger)]">- {d.before}</span>
                        <span className="mx-2 text-[var(--text-muted)]">→</span>
                        <span className="text-[var(--success)]">+ {d.after}</span>
                      </div>
                      <p className="mt-1 text-xs text-[var(--text-muted)]">{d.rationale}</p>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      ))}
    </div>
  );
}
