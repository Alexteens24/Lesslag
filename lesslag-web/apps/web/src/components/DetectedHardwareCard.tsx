'use client';

import { useState } from 'react';
import { useLessLagStore } from '@/store/lesslag-store';

const TIER_COLORS = {
    LOW: { bg: 'rgba(239, 68, 68, 0.15)', text: '#f87171', border: '#f87171' },
    MID: { bg: 'rgba(251, 191, 36, 0.15)', text: '#fbbf24', border: '#fbbf24' },
    HIGH: { bg: 'rgba(34, 197, 94, 0.15)', text: '#22c55e', border: '#22c55e' },
} as const;

export function DetectedHardwareCard({ onEditManually }: { onEditManually: () => void }) {
    const { serverPayload, benchmarkScore, benchmarkTier } = useLessLagStore();
    const [showFlags, setShowFlags] = useState(false);

    if (!serverPayload) return null;

    const tier = benchmarkTier ?? 'MID';
    const tierColor = TIER_COLORS[tier];

    return (
        <div className="space-y-4">
            <div className="rounded-xl border border-[var(--border)] bg-[var(--bg-card)] p-5">
                {/* Header */}
                <div className="mb-4 flex items-center justify-between">
                    <div className="flex items-center gap-2">
                        <span className="text-lg">🖥️</span>
                        <h3 className="text-sm font-semibold text-[var(--text-primary)]">Detected Hardware</h3>
                        <span
                            className="inline-flex items-center rounded-full px-2 py-0.5 text-[10px] font-bold uppercase tracking-wider"
                            style={{ backgroundColor: tierColor.bg, color: tierColor.text, border: `1px solid ${tierColor.border}` }}
                        >
                            {tier}
                        </span>
                    </div>
                    <button
                        onClick={onEditManually}
                        className="text-xs text-[var(--accent)] hover:underline"
                    >
                        Edit manually ✏️
                    </button>
                </div>

                {/* CPU + Benchmark */}
                <div className="grid gap-3 sm:grid-cols-2">
                    <div className="rounded-lg bg-[var(--bg-primary)] p-3">
                        <div className="text-[10px] font-medium uppercase tracking-wider text-[var(--text-muted)]">CPU</div>
                        <div className="mt-0.5 text-sm font-medium text-[var(--text-primary)]">{serverPayload.cpuModel}</div>
                        <div className="mt-1 flex items-center gap-2">
                            <span className="text-xs text-[var(--text-muted)]">{serverPayload.cores} cores</span>
                            {benchmarkScore != null && (
                                <span
                                    className="inline-flex items-center rounded px-1.5 py-0.5 text-[10px] font-bold"
                                    style={{ backgroundColor: tierColor.bg, color: tierColor.text }}
                                >
                                    ⚡ {benchmarkScore} GB6
                                </span>
                            )}
                        </div>
                    </div>

                    <div className="rounded-lg bg-[var(--bg-primary)] p-3">
                        <div className="text-[10px] font-medium uppercase tracking-wider text-[var(--text-muted)]">Platform</div>
                        <div className="mt-0.5 text-sm font-medium text-[var(--text-primary)]">
                            {serverPayload.fork} {serverPayload.mcVersion}
                        </div>
                        <div className="mt-1 text-xs text-[var(--text-muted)]">Java {serverPayload.javaVersion}</div>
                    </div>
                </div>

                {/* Memory + Performance */}
                <div className="mt-3 grid gap-3 sm:grid-cols-3">
                    <div className="rounded-lg bg-[var(--bg-primary)] p-3">
                        <div className="text-[10px] font-medium uppercase tracking-wider text-[var(--text-muted)]">Heap</div>
                        <div className="mt-0.5 text-sm font-medium text-[var(--text-primary)]">
                            {serverPayload.maxHeapMb >= 1024
                                ? `${(serverPayload.maxHeapMb / 1024).toFixed(1)} GB`
                                : `${serverPayload.maxHeapMb} MB`}
                        </div>
                    </div>

                    <div className="rounded-lg bg-[var(--bg-primary)] p-3">
                        <div className="text-[10px] font-medium uppercase tracking-wider text-[var(--text-muted)]">TPS</div>
                        <div className={`mt-0.5 text-sm font-medium ${serverPayload.tps >= 19.5 ? 'text-emerald-400' : serverPayload.tps >= 18 ? 'text-yellow-400' : 'text-red-400'}`}>
                            {serverPayload.tps.toFixed(1)}
                        </div>
                    </div>

                    <div className="rounded-lg bg-[var(--bg-primary)] p-3">
                        <div className="text-[10px] font-medium uppercase tracking-wider text-[var(--text-muted)]">MSPT</div>
                        <div className={`mt-0.5 text-sm font-medium ${serverPayload.mspt <= 40 ? 'text-emerald-400' : serverPayload.mspt <= 50 ? 'text-yellow-400' : 'text-red-400'}`}>
                            {serverPayload.mspt.toFixed(1)} ms
                        </div>
                    </div>
                </div>

                {/* Plugins summary */}
                <div className="mt-3 rounded-lg bg-[var(--bg-primary)] p-3">
                    <div className="text-[10px] font-medium uppercase tracking-wider text-[var(--text-muted)]">Plugins</div>
                    <div className="mt-0.5 text-sm text-[var(--text-primary)]">
                        {serverPayload.pluginNames.length} plugins detected
                    </div>
                </div>

                {/* JVM Flags (collapsible) */}
                {serverPayload.jvmFlags.length > 0 && (
                    <div className="mt-3">
                        <button
                            onClick={() => setShowFlags(!showFlags)}
                            className="text-xs text-[var(--text-muted)] hover:text-[var(--text-secondary)] transition-colors"
                        >
                            {showFlags ? '▾' : '▸'} JVM Flags ({serverPayload.jvmFlags.length})
                        </button>
                        {showFlags && (
                            <pre className="mt-2 max-h-32 overflow-y-auto rounded-lg bg-[var(--bg-primary)] p-3 text-xs text-[var(--text-secondary)] font-mono">
                                {serverPayload.jvmFlags.join('\n')}
                            </pre>
                        )}
                    </div>
                )}
            </div>

            <div className="rounded-lg border border-emerald-500/20 bg-emerald-500/5 px-4 py-3">
                <p className="text-xs text-emerald-400">
                    ✓ Hardware detected from your server. Step auto-completed.
                </p>
            </div>
        </div>
    );
}
