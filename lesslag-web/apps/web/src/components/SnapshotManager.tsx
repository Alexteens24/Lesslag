'use client';

import { useState } from 'react';
import { useLessLagStore } from '@/store/lesslag-store';

export function SnapshotManager() {
  const { snapshots, currentSnapshotId, saveSnapshot, restoreSnapshot, deleteSnapshot } = useLessLagStore();
  const [newLabel, setNewLabel] = useState('');

  const handleSave = () => {
    if (!newLabel.trim()) return;
    saveSnapshot(newLabel.trim());
    setNewLabel('');
  };

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      {/* Save new snapshot */}
      <div className="rounded-xl border border-[var(--border)] bg-[var(--bg-card)] p-6">
        <h3 className="text-lg font-semibold text-[var(--text-primary)] mb-4">💾 Config Snapshots</h3>
        <p className="text-sm text-[var(--text-muted)] mb-4">
          Save your current configuration state and roll back to any previous version.
        </p>

        <div className="flex gap-2">
          <input
            value={newLabel}
            onChange={(e) => setNewLabel(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && handleSave()}
            placeholder="Snapshot label (e.g., 'before aggressive tuning')"
            className="flex-1 rounded-lg border border-[var(--border)] bg-[var(--bg-primary)] px-3 py-2 text-sm text-[var(--text-primary)] focus:border-[var(--accent)] focus:outline-none"
          />
          <button
            onClick={handleSave}
            disabled={!newLabel.trim()}
            className="rounded-lg bg-[var(--accent)] px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-[var(--accent-hover)] disabled:opacity-50"
          >
            Save
          </button>
        </div>
      </div>

      {/* Snapshot list */}
      {snapshots.length === 0 ? (
        <div className="text-center py-12 text-[var(--text-muted)]">
          <p className="text-lg">No snapshots yet</p>
          <p className="mt-1 text-sm">Save your first snapshot to enable rollback</p>
        </div>
      ) : (
        <div className="space-y-2">
          {snapshots
            .slice()
            .sort((a, b) => b.timestamp - a.timestamp)
            .map((snap) => {
              const isCurrent = snap.id === currentSnapshotId;
              const fileCount = Object.keys(snap.configs).length;
              const keyCount = Object.values(snap.configs).reduce<number>(
                (sum, cfg) => sum + Object.keys(cfg ?? {}).length,
                0
              );

              return (
                <div
                  key={snap.id}
                  className={`flex flex-col gap-3 rounded-lg border p-3 transition-all sm:flex-row sm:items-center sm:gap-4 sm:p-4 ${
                    isCurrent
                      ? 'border-[var(--accent)] bg-[var(--accent)]/5'
                      : 'border-[var(--border)] bg-[var(--bg-card)]'
                  }`}
                >
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <span className="text-sm font-medium text-[var(--text-primary)] truncate">{snap.label}</span>
                      {isCurrent && (
                        <span className="shrink-0 rounded-full bg-[var(--accent)]/20 px-2 py-0.5 text-[10px] font-medium text-[var(--accent)]">
                          ACTIVE
                        </span>
                      )}
                    </div>
                    <div className="mt-1 flex flex-wrap gap-2 text-xs text-[var(--text-muted)] sm:gap-3">
                      <span>{new Date(snap.timestamp).toLocaleString()}</span>
                      <span>{fileCount} files</span>
                      <span>{keyCount} settings</span>
                    </div>
                  </div>

                  <div className="flex gap-2 self-end sm:self-auto">
                    <button
                      onClick={() => restoreSnapshot(snap.id)}
                      disabled={isCurrent}
                      className="rounded-lg border border-[var(--border)] px-3 py-1.5 text-xs font-medium text-[var(--text-secondary)] hover:bg-[var(--bg-elevated)] disabled:opacity-30 transition-colors"
                    >
                      Restore
                    </button>
                    <button
                      onClick={() => {
                        if (confirm(`Delete snapshot "${snap.label}"?`)) {
                          deleteSnapshot(snap.id);
                        }
                      }}
                      className="rounded-lg border border-[var(--danger)]/30 px-3 py-1.5 text-xs font-medium text-[var(--danger)] hover:bg-[var(--danger)]/10 transition-colors"
                    >
                      Delete
                    </button>
                  </div>
                </div>
              );
            })}
        </div>
      )}
    </div>
  );
}
