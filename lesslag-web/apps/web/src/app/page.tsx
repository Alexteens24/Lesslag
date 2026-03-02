'use client';

import { useLessLagStore } from '@/store/lesslag-store';
import { PresetSelector } from '@/components/PresetSelector';
import { ConfigEditor } from '@/components/ConfigEditor';
import { DiffViewer } from '@/components/DiffViewer';
import { RationalePanel } from '@/components/RationalePanel';
import { ImportExportModal } from '@/components/ImportExportModal';
import { HardwareWizard } from '@/components/HardwareWizard';
import { PluginConflictDetector } from '@/components/PluginConflictDetector';
import { SnapshotManager } from '@/components/SnapshotManager';
import { useShareableState, generateShareUrl } from '@/hooks/useShareableState';

const TABS = [
  { id: 'presets' as const, label: '🎯 Presets' },
  { id: 'editor' as const, label: '📝 Editor' },
  { id: 'diff' as const, label: '🔄 Changes' },
  { id: 'rationale' as const, label: '📊 Analysis' },
  { id: 'hardware' as const, label: '🖥️ Hardware' },
  { id: 'conflicts' as const, label: '🔌 Plugins' },
] as const;

export default function HomePage() {
  const { activeTab, setActiveTab, evaluation, diffs, snapshots, setShowImportModal } = useLessLagStore();
  useShareableState();

  const handleShare = () => {
    const url = generateShareUrl();
    navigator.clipboard.writeText(url);
    alert('Share link copied to clipboard!');
  };

  return (
    <div className="space-y-6">
      {/* Hero section */}
      <section className="text-center py-4 sm:py-6">
        <h1 className="text-2xl font-bold text-[var(--text-primary)] sm:text-3xl">
          Server Optimizer
        </h1>
        <p className="mt-2 text-sm text-[var(--text-muted)] max-w-xl mx-auto sm:text-base">
          Choose your server profile, review AI-powered recommendations, and export optimized configs.
        </p>
      </section>

      {/* Action bar */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-2">
          {evaluation && (
            <span className="rounded-full bg-[var(--success)]/10 text-[var(--success)] px-3 py-1 text-xs font-medium">
              {evaluation.results.length} findings
            </span>
          )}
          {diffs.length > 0 && (
            <span className="rounded-full bg-[var(--accent)]/10 text-[var(--accent)] px-3 py-1 text-xs font-medium">
              {diffs.length} changes
            </span>
          )}
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={handleShare}
            className="rounded-lg border border-[var(--border)] px-2.5 py-1.5 text-xs text-[var(--text-secondary)] hover:bg-[var(--bg-elevated)] transition-colors sm:px-3 sm:py-2 sm:text-sm"
            title="Copy share link"
          >
            🔗 <span className="hidden sm:inline">Share</span>
          </button>
          <button
            onClick={() => setActiveTab('editor')}
            className="relative rounded-lg border border-[var(--border)] px-2.5 py-1.5 text-xs text-[var(--text-secondary)] hover:bg-[var(--bg-elevated)] transition-colors sm:px-3 sm:py-2 sm:text-sm"
            title="Snapshots"
          >
            💾 <span className="hidden sm:inline">Snapshots</span>
            {snapshots.length > 0 && (
              <span className="absolute -top-1.5 -right-1.5 flex h-4 w-4 items-center justify-center rounded-full bg-[var(--accent)] text-[10px] text-white">
                {snapshots.length}
              </span>
            )}
          </button>
          <button
            onClick={() => setShowImportModal(true)}
            className="rounded-lg border border-[var(--border)] px-2.5 py-1.5 text-xs text-[var(--text-secondary)] hover:bg-[var(--bg-elevated)] transition-colors sm:px-3 sm:py-2 sm:text-sm"
          >
            📦 <span className="hidden sm:inline">Import / Export</span>
          </button>
        </div>
      </div>

      {/* Tab navigation */}
      <nav className="grid grid-cols-3 gap-1 rounded-lg bg-[var(--bg-card)] border border-[var(--border)] p-1 sm:flex">
        {TABS.map((tab) => (
          <button
            key={tab.id}
            onClick={() => setActiveTab(tab.id)}
            className={`rounded-md px-2 py-2 text-xs font-medium transition-all sm:flex-1 sm:px-3 sm:py-2.5 sm:text-sm ${
              activeTab === tab.id
                ? 'bg-[var(--accent)] text-white shadow-sm'
                : 'text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-elevated)]'
            }`}
          >
            {tab.label}
          </button>
        ))}
      </nav>

      {/* Tab content */}
      <main className="min-h-[60vh]">
        {activeTab === 'presets' && <PresetSelector />}
        {activeTab === 'editor' && (
          <div className="space-y-8">
            <ConfigEditor />
            <SnapshotManager />
          </div>
        )}
        {activeTab === 'diff' && <DiffViewer />}
        {activeTab === 'rationale' && <RationalePanel />}
        {activeTab === 'hardware' && <HardwareWizard />}
        {activeTab === 'conflicts' && <PluginConflictDetector />}
      </main>

      <ImportExportModal />
    </div>
  );
}
