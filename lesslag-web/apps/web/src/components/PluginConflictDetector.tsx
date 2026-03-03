'use client';

import { useState, useEffect } from 'react';
import { useLessLagStore } from '@/store/lesslag-store';

/** Known plugin conflicts from the Java rule engine knowledge. */
const KNOWN_CONFLICTS: Record<string, { severity: 'high' | 'medium' | 'low'; message: string }> = {
  clearlagg: {
    severity: 'high',
    message: 'ClearLagg conflicts with LessLag\'s entity management. Disable ClearLagg\'s mob limiter or LessLag\'s entity module.',
  },
  entitycleaner: {
    severity: 'high',
    message: 'EntityCleaner duplicates LessLag\'s entity cleanup. Use only one.',
  },
  laggremover: {
    severity: 'high',
    message: 'LaggRemover and LessLag both manage server tick optimization. Running both causes double-throttling.',
  },
  spark: {
    severity: 'low',
    message: 'Spark is a profiler — safe to use alongside LessLag. Spark data can help tune LessLag settings.',
  },
  chunky: {
    severity: 'low',
    message: 'Chunky pre-generates chunks. Safe to use — may even improve LessLag performance by reducing chunk gen lag.',
  },
  viaversion: {
    severity: 'medium',
    message: 'ViaVersion adds protocol overhead. Consider reducing view-distance slightly if using cross-version support.',
  },
  worldedit: {
    severity: 'medium',
    message: 'Large WorldEdit operations can spike MSPT. Consider limiting WE operations per tick with //perf or async WE.',
  },
  fawe: {
    severity: 'low',
    message: 'FAWE (Fast Async WorldEdit) is async — minimal impact on server tick. Safe to use.',
  },
  cmi: {
    severity: 'low',
    message: 'CMI is a large all-in-one plugin. Generally compatible, but its scheduler may compete. Monitor MSPT.',
  },
  essentialsx: {
    severity: 'low',
    message: 'EssentialsX is well-optimized. Fully compatible with LessLag.',
  },
  protocollib: {
    severity: 'low',
    message: 'ProtocolLib is a library plugin. No known conflicts.',
  },
  placeholderapi: {
    severity: 'low',
    message: 'PlaceholderAPI is lightweight. No known conflicts.',
  },
  citizens: {
    severity: 'medium',
    message: 'Citizens NPCs add to entity counts. If using LessLag\'s entity limits, whitelist Citizens NPC types.',
  },
  mythicmobs: {
    severity: 'medium',
    message: 'MythicMobs custom entities may be affected by LessLag\'s spawn limits. Configure spawn-limits carefully.',
  },
};

interface ConflictResult {
  plugin: string;
  status: 'conflict' | 'warning' | 'safe' | 'unknown';
  message: string;
}

function analyzePlugins(plugins: string[]): ConflictResult[] {
  return plugins
    .filter((p) => p.trim())
    .map((plugin) => {
      const normalized = plugin.trim().toLowerCase().replace(/[^a-z0-9]/g, '');
      const match = Object.keys(KNOWN_CONFLICTS).find((k) => normalized.includes(k));
      if (match) {
        const info = KNOWN_CONFLICTS[match];
        return {
          plugin: plugin.trim(),
          status: info.severity === 'high' ? 'conflict' : info.severity === 'medium' ? 'warning' : 'safe',
          message: info.message,
        } as ConflictResult;
      }
      return {
        plugin: plugin.trim(),
        status: 'unknown' as const,
        message: 'No known compatibility data. Monitor server performance after enabling.',
      };
    });
}

const STATUS_STYLES = {
  conflict: { icon: '🔴', color: 'text-[var(--danger)]', bg: 'border-[var(--danger)]/30' },
  warning: { icon: '🟡', color: 'text-[var(--warning)]', bg: 'border-[var(--warning)]/30' },
  safe: { icon: '🟢', color: 'text-[var(--success)]', bg: 'border-[var(--success)]/30' },
  unknown: { icon: '⚪', color: 'text-[var(--text-muted)]', bg: 'border-[var(--border)]' },
};

export function PluginConflictDetector() {
  const { plugins, setPlugins } = useLessLagStore();
  const serverPayload = useLessLagStore((s) => s.serverPayload);
  const [inputText, setInputText] = useState(plugins.join('\n'));
  const [results, setResults] = useState<ConflictResult[]>([]);
  const [analyzed, setAnalyzed] = useState(false);
  const [autoPopulated, setAutoPopulated] = useState(false);

  // Auto-populate from server payload
  useEffect(() => {
    if (serverPayload && serverPayload.pluginNames.length > 0 && !autoPopulated) {
      const text = serverPayload.pluginNames.join('\n');
      setInputText(text);
      setPlugins(serverPayload.pluginNames);
      setResults(analyzePlugins(serverPayload.pluginNames));
      setAnalyzed(true);
      setAutoPopulated(true);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [serverPayload]);

  const handleAnalyze = () => {
    const pluginList = inputText
      .split(/[\n,]+/)
      .map((s) => s.trim())
      .filter(Boolean);
    setPlugins(pluginList);
    setResults(analyzePlugins(pluginList));
    setAnalyzed(true);
  };

  const conflicts = results.filter((r) => r.status === 'conflict');
  const warnings = results.filter((r) => r.status === 'warning');
  const safe = results.filter((r) => r.status === 'safe');
  const unknown = results.filter((r) => r.status === 'unknown');

  return (
    <div className="mx-auto max-w-2xl space-y-4 sm:space-y-6">
      <div className="rounded-xl border border-[var(--border)] bg-[var(--bg-card)] p-4 sm:p-6">
        <h3 className="text-lg font-semibold text-[var(--text-primary)] mb-4">Plugin Compatibility Checker</h3>
        <p className="text-sm text-[var(--text-muted)] mb-4">
          Enter your installed plugins (one per line or comma-separated) to check for known conflicts with LessLag.
        </p>

        <textarea
          value={inputText}
          onChange={(e) => {
            setInputText(e.target.value);
            setAnalyzed(false);
          }}
          placeholder={`EssentialsX\nSpark\nWorldEdit\nClearLagg\nViaVersion\nCitizens`}
          className="w-full rounded-lg border border-[var(--border)] bg-[var(--bg-primary)] px-3 py-2 text-sm text-[var(--text-primary)] font-mono resize-none focus:border-[var(--accent)] focus:outline-none"
          rows={6}
        />

        <button
          onClick={handleAnalyze}
          disabled={!inputText.trim()}
          className="mt-3 w-full rounded-lg bg-[var(--accent)] px-4 py-2.5 text-sm font-medium text-white transition-colors hover:bg-[var(--accent-hover)] disabled:opacity-50"
        >
          🔍 Analyze Compatibility
        </button>
      </div>

      {analyzed && results.length > 0 && (
        <>
          {/* Summary */}
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-4 sm:gap-3">
            <SummaryPill label="Conflicts" count={conflicts.length} color="text-[var(--danger)]" />
            <SummaryPill label="Warnings" count={warnings.length} color="text-[var(--warning)]" />
            <SummaryPill label="Safe" count={safe.length} color="text-[var(--success)]" />
            <SummaryPill label="Unknown" count={unknown.length} color="text-[var(--text-muted)]" />
          </div>

          {/* Results */}
          <div className="space-y-2">
            {results.map((r) => {
              const style = STATUS_STYLES[r.status];
              return (
                <div
                  key={r.plugin}
                  className={`flex items-start gap-3 rounded-lg border ${style.bg} bg-[var(--bg-card)] p-4`}
                >
                  <span className="mt-0.5">{style.icon}</span>
                  <div className="flex-1">
                    <div className={`text-sm font-medium ${style.color}`}>{r.plugin}</div>
                    <div className="text-xs text-[var(--text-muted)] mt-1">{r.message}</div>
                  </div>
                </div>
              );
            })}
          </div>
        </>
      )}
    </div>
  );
}

function SummaryPill({ label, count, color }: { label: string; count: number; color: string }) {
  return (
    <div className="rounded-lg border border-[var(--border)] bg-[var(--bg-card)] p-3 text-center">
      <div className={`text-lg font-bold ${color}`}>{count}</div>
      <div className="text-xs text-[var(--text-muted)]">{label}</div>
    </div>
  );
}
