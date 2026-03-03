'use client';

import { useState, useRef } from 'react';
import { useLessLagStore } from '@/store/lesslag-store';

type ExportTab = 'config' | 'checklist' | 'startup';

const EXPORT_TABS: { id: ExportTab; label: string }[] = [
  { id: 'config', label: '⚙️ LessLag Config' },
  { id: 'checklist', label: '📋 Server Checklist' },
  { id: 'startup', label: '🚀 Startup Command' },
];

export function ImportExportModal() {
  const {
    showImportModal, importConfigs,
    lesslagConfigJson, serverConfigChecklist, startupCommand,
  } = useLessLagStore();
  const setShowImportModal = useLessLagStore((s) => s.setShowImportModal);
  const [activeTab, setActiveTab] = useState<'import' | 'export'>('export');
  const [exportTab, setExportTab] = useState<ExportTab>('config');
  const [importText, setImportText] = useState('');
  const [dragOver, setDragOver] = useState(false);
  const [expandedFiles, setExpandedFiles] = useState<Set<string>>(new Set());
  const [copied, setCopied] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const copy = (text: string, key: string) => {
    navigator.clipboard.writeText(text);
    setCopied(key);
    setTimeout(() => setCopied(null), 2000);
  };

  const toggleFile = (file: string) =>
    setExpandedFiles((prev) => {
      const next = new Set(prev);
      if (next.has(file)) next.delete(file); else next.add(file);
      return next;
    });

  if (!showImportModal) return null;

  const handleFileUpload = (files: FileList | null) => {
    if (!files) return;
    const reader = new FileReader();
    reader.onload = (e) => setImportText(e.target?.result as string);
    reader.readAsText(files[0]);
  };

  const handleImport = () => {
    if (!importText.trim()) return;
    try {
      const parsed = JSON.parse(importText);
      importConfigs(parsed);
      setShowImportModal(false);
      setImportText('');
    } catch {
      alert('Invalid JSON. Please paste valid config JSON.');
    }
  };

  const downloadJson = (json: string, filename: string) => {
    const blob = new Blob([json], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url; a.download = filename; a.click();
    URL.revokeObjectURL(url);
  };

  const configJson = lesslagConfigJson ? JSON.stringify(lesslagConfigJson, null, 2) : null;
  const checklistEntries = serverConfigChecklist
    ? Object.entries(serverConfigChecklist).filter(([f]) => f !== 'paper-world.yml')
    : [];
  const worldEntries = serverConfigChecklist?.['paper-world.yml'] ?? [];

  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-black/60 backdrop-blur-sm sm:items-center">
      <div className="w-full max-h-[90vh] overflow-y-auto rounded-t-xl border border-[var(--border)] bg-[var(--bg-card)] shadow-2xl sm:max-w-lg sm:rounded-xl">

        {/* Header */}
        <div className="flex items-center justify-between border-b border-[var(--border)] px-5 py-4">
          <h2 className="text-lg font-semibold text-[var(--text-primary)]">Import / Export</h2>
          <button
            onClick={() => setShowImportModal(false)}
            className="text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
          >
            ✕
          </button>
        </div>

        {/* Main tabs */}
        <div className="flex border-b border-[var(--border)]">
          {(['import', 'export'] as const).map((tab) => (
            <button
              key={tab}
              onClick={() => setActiveTab(tab)}
              className={`flex-1 py-2.5 text-sm font-medium transition-colors ${
                activeTab === tab
                  ? 'border-b-2 border-[var(--accent)] text-[var(--accent)]'
                  : 'text-[var(--text-muted)] hover:text-[var(--text-secondary)]'
              }`}
            >
              {tab === 'import' ? '📥 Import' : '📤 Export'}
            </button>
          ))}
        </div>

        {/* Content */}
        <div className="p-5">
          {activeTab === 'import' ? (
            /* ─── Import tab ─────────────────────────────── */
            <div className="space-y-4">
              <div
                className={`flex cursor-pointer flex-col items-center justify-center rounded-lg border-2 border-dashed p-8 transition-colors ${
                  dragOver
                    ? 'border-[var(--accent)] bg-[var(--accent)]/5'
                    : 'border-[var(--border)] hover:border-[var(--text-muted)]'
                }`}
                onDragOver={(e) => { e.preventDefault(); setDragOver(true); }}
                onDragLeave={() => setDragOver(false)}
                onDrop={(e) => { e.preventDefault(); setDragOver(false); handleFileUpload(e.dataTransfer.files); }}
                onClick={() => fileInputRef.current?.click()}
              >
                <span className="text-2xl">📄</span>
                <span className="mt-2 text-sm text-[var(--text-muted)]">Drop a JSON file or click to browse</span>
                <input ref={fileInputRef} type="file" accept=".json" className="hidden" onChange={(e) => handleFileUpload(e.target.files)} />
              </div>
              <div>
                <label className="mb-1 block text-xs text-[var(--text-muted)]">Or paste JSON:</label>
                <textarea
                  value={importText}
                  onChange={(e) => setImportText(e.target.value)}
                  className="w-full rounded-lg border border-[var(--border)] bg-[var(--bg-primary)] px-3 py-2 text-sm text-[var(--text-primary)] font-mono resize-none focus:border-[var(--accent)] focus:outline-none"
                  rows={6}
                  placeholder='{"server.properties": {...}, "bukkit.yml": {...}}'
                />
              </div>
              <button
                onClick={handleImport}
                disabled={!importText.trim()}
                className="w-full rounded-lg bg-[var(--accent)] px-4 py-2.5 text-sm font-medium text-white transition-colors hover:bg-[var(--accent-hover)] disabled:cursor-not-allowed disabled:opacity-50"
              >
                Import Configuration
              </button>
            </div>
          ) : (
            /* ─── Export tab ─────────────────────────────── */
            <div className="space-y-4">

              {/* Export sub-tabs */}
              <div className="flex rounded-lg border border-[var(--border)] overflow-hidden">
                {EXPORT_TABS.map(({ id, label }) => (
                  <button
                    key={id}
                    onClick={() => setExportTab(id)}
                    className={`flex-1 py-2 text-xs font-medium transition-colors ${
                      exportTab === id
                        ? 'bg-[var(--accent)] text-white'
                        : 'text-[var(--text-muted)] hover:bg-[var(--bg-elevated)]'
                    }`}
                  >
                    {label}
                  </button>
                ))}
              </div>

              {/* ── LessLag Config ── */}
              {exportTab === 'config' && (
                configJson ? (
                  <div className="space-y-3">
                    <div className="flex flex-wrap gap-2 text-xs">
                      <span className="rounded bg-emerald-500/15 px-2 py-0.5 text-emerald-400 font-medium">
                        {Object.keys(lesslagConfigJson!.lesslag).length} auto-apply
                      </span>
                      <span className="rounded bg-amber-500/15 px-2 py-0.5 text-amber-400 font-medium">
                        {Object.values(lesslagConfigJson!.server_config_expectations).reduce((a, r) => a + Object.keys(r).length, 0)} manual-verify
                      </span>
                    </div>
                    <textarea
                      readOnly
                      value={configJson}
                      className="w-full rounded-lg border border-[var(--border)] bg-[var(--bg-primary)] px-3 py-2 text-xs text-[var(--text-secondary)] font-mono resize-none"
                      rows={8}
                    />
                    <div className="flex gap-3">
                      <button
                        onClick={() => copy(configJson, 'config')}
                        className="flex-1 rounded-lg border border-[var(--border)] px-4 py-2.5 text-sm font-medium text-[var(--text-primary)] transition-colors hover:bg-[var(--bg-elevated)]"
                      >
                        {copied === 'config' ? '✅ Copied!' : '📋 Copy JSON'}
                      </button>
                      <button
                        onClick={() => downloadJson(configJson, 'lesslag-config.json')}
                        className="flex-1 rounded-lg bg-[var(--accent)] px-4 py-2.5 text-sm font-medium text-white transition-colors hover:bg-[var(--accent-hover)]"
                      >
                        💾 Download
                      </button>
                    </div>
                    <p className="text-xs text-[var(--text-muted)]">
                      Place <code className="rounded bg-[var(--bg-elevated)] px-1">lesslag-config.json</code> in{' '}
                      <code className="rounded bg-[var(--bg-elevated)] px-1">plugins/LessLag/</code>, then run{' '}
                      <code className="rounded bg-[var(--bg-elevated)] px-1">/lg apply</code>.
                    </p>
                  </div>
                ) : (
                  <div className="rounded-lg border border-dashed border-[var(--border)] p-8 text-center text-sm text-[var(--text-muted)]">
                    Complete the configuration steps and click Export to generate artifacts.
                  </div>
                )
              )}

              {/* ── Server Checklist ── */}
              {exportTab === 'checklist' && (
                <div className="space-y-3">
                  {/* Pre-gen reminder */}
                  <div className="rounded-lg border border-amber-500/30 bg-amber-500/5 p-3 text-xs text-amber-300">
                    <strong>Tip:</strong> After applying, run{' '}
                    <code className="rounded bg-amber-500/20 px-1 font-mono">/lg world pregenerate</code>{' '}
                    near spawn to pre-generate chunks for best performance.
                  </div>

                  {checklistEntries.length === 0 && worldEntries.length === 0 ? (
                    <div className="rounded-lg border border-dashed border-[var(--border)] p-8 text-center text-sm text-[var(--text-muted)]">
                      No manual server config changes are recommended for your setup.
                    </div>
                  ) : (
                    <>
                      {checklistEntries.map(([file, entries]) => {
                        const isOpen = expandedFiles.has(file);
                        const filename = file.split('/').pop() ?? file;
                        const allEntries = entries
                          .map((e) => `# ${e.rationale}\n${e.key}: ${String(e.expectedValue)}`)
                          .join('\n\n');
                        return (
                          <div key={file} className="rounded-lg border border-[var(--border)] overflow-hidden">
                            <button
                              onClick={() => toggleFile(file)}
                              className="flex w-full items-center justify-between px-4 py-3 text-left hover:bg-[var(--bg-elevated)] transition-colors"
                            >
                              <div className="flex items-center gap-2">
                                <span className={`text-xs transition-transform ${isOpen ? 'rotate-90' : ''}`}>▶</span>
                                <span className="font-mono text-sm font-medium text-[var(--accent)]">{filename}</span>
                                <span className="rounded-full bg-[var(--bg-elevated)] px-1.5 py-0.5 text-[10px] text-[var(--text-muted)]">
                                  {entries.length}
                                </span>
                              </div>
                              <button
                                onClick={(e) => { e.stopPropagation(); copy(allEntries, `file-${file}`); }}
                                className="text-xs text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
                              >
                                {copied === `file-${file}` ? '✅' : '📋 Copy all'}
                              </button>
                            </button>
                            {isOpen && (
                              <div className="divide-y divide-[var(--border)] border-t border-[var(--border)]">
                                {entries.map((entry) => (
                                  <div key={entry.key} className="flex items-start justify-between gap-3 px-4 py-3">
                                    <div className="min-w-0 flex-1">
                                      <div className="font-mono text-xs text-[var(--text-primary)]">{entry.key}</div>
                                      <div className="mt-0.5 text-[10px] text-[var(--text-muted)]">{entry.rationale}</div>
                                    </div>
                                    <div className="flex flex-shrink-0 items-center gap-1.5">
                                      <span className="rounded bg-emerald-500/15 px-1.5 py-0.5 font-mono text-[10px] text-emerald-400">
                                        {String(entry.expectedValue)}
                                      </span>
                                      <button
                                        onClick={() => copy(String(entry.expectedValue), `val-${entry.key}`)}
                                        className="text-[10px] text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
                                      >
                                        {copied === `val-${entry.key}` ? '✅' : '📋'}
                                      </button>
                                    </div>
                                  </div>
                                ))}
                              </div>
                            )}
                          </div>
                        );
                      })}

                      {/* Per-world section */}
                      {worldEntries.length > 0 && (
                        <div className="rounded-lg border border-violet-500/30 overflow-hidden">
                          <button
                            onClick={() => toggleFile('paper-world.yml')}
                            className="flex w-full items-center justify-between px-4 py-3 text-left hover:bg-[var(--bg-elevated)] transition-colors"
                          >
                            <div className="flex items-center gap-2">
                              <span className={`text-xs transition-transform ${expandedFiles.has('paper-world.yml') ? 'rotate-90' : ''}`}>▶</span>
                              <span className="font-mono text-sm font-medium text-violet-400">paper-world.yml</span>
                              <span className="rounded-full bg-violet-500/15 px-1.5 py-0.5 text-[10px] text-violet-400">
                                per-world · {worldEntries.length}
                              </span>
                            </div>
                            <button
                              onClick={(e) => {
                                e.stopPropagation();
                                copy(
                                  worldEntries.map((e) => `# ${e.rationale}\n${e.key}: ${String(e.expectedValue)}`).join('\n\n'),
                                  'file-paper-world.yml',
                                );
                              }}
                              className="text-xs text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
                            >
                              {copied === 'file-paper-world.yml' ? '✅' : '📋 Copy all'}
                            </button>
                          </button>
                          {expandedFiles.has('paper-world.yml') && (
                            <div className="divide-y divide-[var(--border)] border-t border-violet-500/20">
                              <p className="px-4 py-2 text-[10px] text-[var(--text-muted)]">
                                Apply these in each world's <code className="font-mono">paper-world.yml</code> (or in <code className="font-mono">paper-world-defaults.yml</code> to apply globally).
                              </p>
                              {worldEntries.map((entry) => (
                                <div key={entry.key} className="flex items-start justify-between gap-3 px-4 py-3">
                                  <div className="min-w-0 flex-1">
                                    <div className="font-mono text-xs text-[var(--text-primary)]">{entry.key}</div>
                                    <div className="mt-0.5 text-[10px] text-[var(--text-muted)]">{entry.rationale}</div>
                                  </div>
                                  <div className="flex flex-shrink-0 items-center gap-1.5">
                                    <span className="rounded bg-violet-500/15 px-1.5 py-0.5 font-mono text-[10px] text-violet-400">
                                      {String(entry.expectedValue)}
                                    </span>
                                    <button
                                      onClick={() => copy(String(entry.expectedValue), `val-${entry.key}`)}
                                      className="text-[10px] text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
                                    >
                                      {copied === `val-${entry.key}` ? '✅' : '📋'}
                                    </button>
                                  </div>
                                </div>
                              ))}
                            </div>
                          )}
                        </div>
                      )}
                    </>
                  )}
                </div>
              )}

              {/* ── Startup Command ── */}
              {exportTab === 'startup' && (
                startupCommand ? (
                  <div className="space-y-3">
                    <div className="flex items-center gap-2">
                      <span
                        className={`rounded-full px-2.5 py-0.5 text-xs font-bold ${
                          startupCommand.gcType === 'ZGC'
                            ? 'bg-violet-500/15 text-violet-400'
                            : 'bg-blue-500/15 text-blue-400'
                        }`}
                      >
                        {startupCommand.gcType}
                      </span>
                      <span className="text-xs text-[var(--text-muted)] font-medium">Recommended GC</span>
                    </div>
                    <p className="text-xs text-[var(--text-muted)] leading-relaxed">{startupCommand.reason}</p>
                    <pre className="w-full overflow-x-auto rounded-lg border border-[var(--border)] bg-[var(--bg-primary)] p-3 text-xs font-mono text-[var(--text-secondary)] whitespace-pre-wrap break-all">
                      {startupCommand.command}
                    </pre>
                    <button
                      onClick={() => copy(startupCommand.command, 'startup')}
                      className="w-full rounded-lg border border-[var(--border)] px-4 py-2.5 text-sm font-medium text-[var(--text-primary)] transition-colors hover:bg-[var(--bg-elevated)]"
                    >
                      {copied === 'startup' ? '✅ Copied!' : '📋 Copy Command'}
                    </button>
                  </div>
                ) : (
                  <div className="rounded-lg border border-dashed border-[var(--border)] p-8 text-center text-sm text-[var(--text-muted)]">
                    Complete the export step to generate a startup command recommendation.
                  </div>
                )
              )}

            </div>
          )}
        </div>
      </div>
    </div>
  );
}
