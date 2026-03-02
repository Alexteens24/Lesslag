'use client';

import { useLessLagStore } from '@/store/lesslag-store';

export function ConfigEditor() {
  const { configs, updateConfig } = useLessLagStore();

  const files = Object.keys(configs).sort();

  return (
    <div className="space-y-6">
      {files.map((file) => (
        <div key={file} className="rounded-lg border border-[var(--border)] bg-[var(--bg-card)]">
          <div className="border-b border-[var(--border)] px-4 py-2">
            <h3 className="font-mono text-sm font-semibold text-[var(--accent)]">{file}</h3>
          </div>
          <div className="divide-y divide-[var(--border)]">
            {Object.entries(configs[file] ?? {}).map(([key, value]) => (
              <div key={key} className="flex items-center gap-4 px-4 py-2">
                <span className="min-w-0 flex-1 truncate font-mono text-sm text-[var(--text-secondary)]">
                  {key}
                </span>
                <ConfigValueInput
                  file={file}
                  configKey={key}
                  value={value as string | number | boolean}
                  onChange={(v) => updateConfig(file, key, v)}
                />
              </div>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}

function ConfigValueInput({
  file,
  configKey,
  value,
  onChange,
}: {
  file: string;
  configKey: string;
  value: string | number | boolean;
  onChange: (v: string | number | boolean) => void;
}) {
  if (typeof value === 'boolean') {
    return (
      <button
        onClick={() => onChange(!value)}
        className={`rounded px-3 py-1 text-xs font-mono font-medium transition-colors ${
          value
            ? 'bg-[var(--success)]/20 text-[var(--success)]'
            : 'bg-[var(--danger)]/20 text-[var(--danger)]'
        }`}
      >
        {String(value)}
      </button>
    );
  }

  if (typeof value === 'number') {
    return (
      <input
        type="number"
        value={value}
        onChange={(e) => onChange(Number(e.target.value))}
        className="w-24 rounded border border-[var(--border)] bg-[var(--bg-elevated)] px-2 py-1 text-right font-mono text-sm text-[var(--text-primary)] focus:border-[var(--accent)] focus:outline-none"
      />
    );
  }

  return (
    <input
      type="text"
      value={String(value)}
      onChange={(e) => onChange(e.target.value)}
      className="w-48 rounded border border-[var(--border)] bg-[var(--bg-elevated)] px-2 py-1 font-mono text-sm text-[var(--text-primary)] focus:border-[var(--accent)] focus:outline-none"
    />
  );
}
