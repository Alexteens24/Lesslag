'use client';

import { useLessLagStore } from '@/store/lesslag-store';
import { LiveMetricsPanel } from '@/components/LiveMetricsPanel';
import type { SetupStepId } from '@/store/lesslag-store';

const STEPS: {
  id: SetupStepId;
  label: string;
  icon: string;
  tab: 'hardware' | 'presets' | 'rationale' | 'diff' | 'editor';
}[] = [
  { id: 'hardware', label: 'Hardware',        icon: '🖥️', tab: 'hardware'  },
  { id: 'preset',   label: 'Profile & Preset', icon: '🎮', tab: 'presets'  },
  { id: 'analysis', label: 'Analysis',         icon: '📊', tab: 'rationale' },
  { id: 'changes',  label: 'Changes',          icon: '📝', tab: 'diff'     },
  { id: 'export',   label: 'Export',           icon: '📤', tab: 'editor'   },
];

interface SidebarProps {
  onShare: () => void;
  onCloudLink: () => void;
  isCreatingCloudLink: boolean;
  lastCloudSessionUrl: string | null;
}

export function Sidebar({ onShare, onCloudLink, isCreatingCloudLink, lastCloudSessionUrl }: SidebarProps) {
  const {
    activeTab, setActiveTab,
    setupProgress,
    evaluation, diffs, snapshots,
    setShowImportModal,
    connectedServerId,
    connectedServerName,
  } = useLessLagStore();

  const completedCount = STEPS.filter((s) => setupProgress[s.id] === 'done').length;
  const progressPercent = Math.round((completedCount / STEPS.length) * 100);

  return (
    <aside className="hidden md:flex flex-col gap-2 w-52 shrink-0 sticky top-6">

      {/* ── Server status ──────────────────────────── */}
      {connectedServerId ? (
        <div className="rounded-xl border border-[var(--border)] bg-[var(--bg-card)] p-3">
          <div className="mb-2 flex items-center justify-between">
            <span className="text-[10px] font-semibold uppercase tracking-wider text-[var(--text-muted)]">
              Server
            </span>
          </div>
          <p className="mb-2 truncate text-xs font-medium text-[var(--text-primary)]">
            {connectedServerName ?? 'Minecraft Server'}
          </p>
          <LiveMetricsPanel serverId={connectedServerId} />
        </div>
      ) : null}

      {/* ── Setup flow ─────────────────────────────── */}
      <div className="rounded-xl border border-[var(--border)] bg-[var(--bg-card)] p-3">
        <div className="mb-2 flex items-center justify-between">
          <span className="text-[10px] font-semibold uppercase tracking-wider text-[var(--text-muted)]">
            Setup Flow
          </span>
          <span className="text-[10px] text-[var(--text-muted)]">{completedCount}/{STEPS.length}</span>
        </div>

        {/* Progress bar */}
        <div className="mb-3 h-1 rounded-full bg-[var(--bg-elevated)]">
          <div
            className="h-1 rounded-full bg-[var(--accent)] transition-all duration-500"
            style={{ width: `${progressPercent}%` }}
          />
        </div>

        <nav className="space-y-0.5">
          {STEPS.map((step, i) => {
            const status = setupProgress[step.id];
            const isActive = step.tab === activeTab;

            return (
              <button
                key={step.id}
                onClick={() => setActiveTab(step.tab)}
                className={`w-full flex items-center gap-2 rounded-lg px-2.5 py-2 text-left transition-colors ${
                  isActive
                    ? 'bg-[var(--accent)] text-white'
                    : 'text-[var(--text-secondary)] hover:bg-[var(--bg-elevated)] hover:text-[var(--text-primary)]'
                }`}
              >
                <span
                  className={`flex h-4 w-4 shrink-0 items-center justify-center rounded-full text-[9px] font-bold ${
                    isActive ? 'bg-white/20' : 'bg-[var(--bg-elevated)]'
                  }`}
                >
                  {status === 'done' && !isActive ? (
                    <span className="text-[var(--success)]">✓</span>
                  ) : (
                    <span className={isActive ? 'text-white' : 'text-[var(--text-muted)]'}>{i + 1}</span>
                  )}
                </span>

                <span className="flex-1 truncate text-xs font-medium">{step.label}</span>

                {status === 'ready' && !isActive && (
                  <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-[var(--accent)]" />
                )}
              </button>
            );
          })}
        </nav>
      </div>

      {/* ── Tools ──────────────────────────────────── */}
      <div className="rounded-xl border border-[var(--border)] bg-[var(--bg-card)] p-3">
        <p className="mb-1.5 text-[10px] font-semibold uppercase tracking-wider text-[var(--text-muted)]">Tools</p>
        <button
          onClick={() => setActiveTab('conflicts')}
          className={`w-full flex items-center gap-2 rounded-lg px-2.5 py-2 text-xs font-medium transition-colors ${
            activeTab === 'conflicts'
              ? 'bg-[var(--accent)] text-white'
              : 'text-[var(--text-secondary)] hover:bg-[var(--bg-elevated)] hover:text-[var(--text-primary)]'
          }`}
        >
          <span>🔌</span>
          <span>Plugin Checker</span>
        </button>
      </div>

      {/* ── Session actions ─────────────────────────── */}
      <div className="rounded-xl border border-[var(--border)] bg-[var(--bg-card)] p-3">
        <p className="mb-1.5 text-[10px] font-semibold uppercase tracking-wider text-[var(--text-muted)]">Session</p>
        <div className="space-y-0.5">
          <SidebarAction icon="🔗" label="Share Local"    onClick={onShare} />
          <SidebarAction
            icon="☁️"
            label={isCreatingCloudLink ? 'Creating…' : 'Share Cloud'}
            onClick={onCloudLink}
            disabled={isCreatingCloudLink}
          />
          <SidebarAction icon="📂" label="Import / Export" onClick={() => setShowImportModal(true)} />
          {lastCloudSessionUrl && (
            <a
              href={lastCloudSessionUrl}
              target="_blank"
              rel="noreferrer"
              className="flex items-center gap-2 rounded-lg px-2.5 py-2 text-xs text-[var(--accent)] hover:bg-[var(--bg-elevated)] transition-colors"
            >
              <span>↗</span>
              <span className="truncate">Last cloud session</span>
            </a>
          )}
        </div>
      </div>

      {/* ── Live stats (only after analysis) ─────────── */}
      {(evaluation || diffs.length > 0 || snapshots.length > 0) && (
        <div className="rounded-xl border border-[var(--border)] bg-[var(--bg-card)] p-3">
          <p className="mb-2 text-[10px] font-semibold uppercase tracking-wider text-[var(--text-muted)]">Summary</p>
          <div className="space-y-1.5">
            {evaluation && (
              <StatRow label="Findings"  value={evaluation.results.length} color="text-[var(--success)]" />
            )}
            {diffs.length > 0 && (
              <StatRow label="Changes"   value={diffs.length}     color="text-[var(--accent)]" />
            )}
            {snapshots.length > 0 && (
              <StatRow label="Snapshots" value={snapshots.length} color="text-[var(--text-secondary)]" />
            )}
          </div>
        </div>
      )}
    </aside>
  );
}

function SidebarAction({
  icon, label, onClick, disabled,
}: {
  icon: string;
  label: string;
  onClick: () => void;
  disabled?: boolean;
}) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      className="w-full flex items-center gap-2 rounded-lg px-2.5 py-2 text-xs text-[var(--text-secondary)] hover:bg-[var(--bg-elevated)] hover:text-[var(--text-primary)] transition-colors disabled:opacity-50"
    >
      <span>{icon}</span>
      <span className="font-medium">{label}</span>
    </button>
  );
}

function StatRow({ label, value, color }: { label: string; value: number; color: string }) {
  return (
    <div className="flex items-center justify-between text-xs">
      <span className="text-[var(--text-muted)]">{label}</span>
      <span className={`font-medium ${color}`}>{value}</span>
    </div>
  );
}
