'use client';

import { useState, useRef } from 'react';
import { useLessLagStore } from '@/store/lesslag-store';

export function ImportExportModal() {
  const { showImportModal, importConfigs, exportConfigs } = useLessLagStore();
  const setShowImportModal = useLessLagStore((s) => s.setShowImportModal);
  const [activeTab, setActiveTab] = useState<'import' | 'export'>('import');
  const [importText, setImportText] = useState('');
  const [dragOver, setDragOver] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  if (!showImportModal) return null;

  const handleFileUpload = (files: FileList | null) => {
    if (!files) return;
    const reader = new FileReader();
    reader.onload = (e) => {
      const text = e.target?.result as string;
      setImportText(text);
    };
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

  const exportData = exportConfigs();
  const exportJson = JSON.stringify(exportData, null, 2);

  const copyToClipboard = () => {
    navigator.clipboard.writeText(exportJson);
  };

  const downloadFile = () => {
    const blob = new Blob([exportJson], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'lesslag-config.json';
    a.click();
    URL.revokeObjectURL(url);
  };

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

        {/* Tabs */}
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
            <div className="space-y-4">
              <div
                className={`flex cursor-pointer flex-col items-center justify-center rounded-lg border-2 border-dashed p-8 transition-colors ${
                  dragOver
                    ? 'border-[var(--accent)] bg-[var(--accent)]/5'
                    : 'border-[var(--border)] hover:border-[var(--text-muted)]'
                }`}
                onDragOver={(e) => { e.preventDefault(); setDragOver(true); }}
                onDragLeave={() => setDragOver(false)}
                onDrop={(e) => {
                  e.preventDefault();
                  setDragOver(false);
                  handleFileUpload(e.dataTransfer.files);
                }}
                onClick={() => fileInputRef.current?.click()}
              >
                <span className="text-2xl">📄</span>
                <span className="mt-2 text-sm text-[var(--text-muted)]">
                  Drop a JSON file or click to browse
                </span>
                <input
                  ref={fileInputRef}
                  type="file"
                  accept=".json"
                  className="hidden"
                  onChange={(e) => handleFileUpload(e.target.files)}
                />
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
            <div className="space-y-4">
              <textarea
                readOnly
                value={exportJson}
                className="w-full rounded-lg border border-[var(--border)] bg-[var(--bg-primary)] px-3 py-2 text-sm text-[var(--text-secondary)] font-mono resize-none"
                rows={10}
              />
              <div className="flex gap-3">
                <button
                  onClick={copyToClipboard}
                  className="flex-1 rounded-lg border border-[var(--border)] px-4 py-2.5 text-sm font-medium text-[var(--text-primary)] transition-colors hover:bg-[var(--bg-elevated)]"
                >
                  📋 Copy to Clipboard
                </button>
                <button
                  onClick={downloadFile}
                  className="flex-1 rounded-lg bg-[var(--accent)] px-4 py-2.5 text-sm font-medium text-white transition-colors hover:bg-[var(--accent-hover)]"
                >
                  💾 Download JSON
                </button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
