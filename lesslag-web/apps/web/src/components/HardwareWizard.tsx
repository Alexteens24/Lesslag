'use client';

import { useState, useEffect, useRef } from 'react';
import { useLessLagStore } from '@/store/lesslag-store';
import { HardwareTierMeta, classifyHardware } from '@lesslag/shared-rules';
import type { HardwareTier, HardwareClassification } from '@lesslag/shared-rules';

const API_BASE = process.env.NEXT_PUBLIC_API_URL ?? '';

interface CpuSearchResult {
  model: string;
  sc: number;
  mc: number | null;
  cores: number | null;
  clockGhz: number | null;
  tier: 'LOW' | 'MID' | 'HIGH';
  similarity: number;
}

const STEPS = ['CPU & RAM', 'Server Software', 'World Info'] as const;


const VPS_PRESETS = [
  {
    id: 'vps-2c4g',
    label: 'Starter VPS',
    details: '2 vCPU • 4 GB RAM • up to ~20 concurrent players',
    cpuModel: 'AMD EPYC VPS (shared vCPU)',
    availableProcessors: 2,
    maxHeapMB: 4096,
    tier: 'LOW' as HardwareTier,
  },
  {
    id: 'vps-4c8g',
    label: 'Standard VPS',
    details: '4 vCPU • 8 GB RAM • up to ~50 concurrent players',
    cpuModel: 'AMD Ryzen VPS (dedicated vCPU)',
    availableProcessors: 4,
    maxHeapMB: 8192,
    tier: 'MID' as HardwareTier,
  },
  {
    id: 'vps-6c12g',
    label: 'Performance VPS',
    details: '6 vCPU • 12 GB RAM • up to ~100 concurrent players',
    cpuModel: 'AMD Ryzen VPS (dedicated vCPU)',
    availableProcessors: 6,
    maxHeapMB: 12288,
    tier: 'HIGH' as HardwareTier,
  },
  {
    id: 'dedicated-8c16g',
    label: 'Dedicated Node',
    details: '8 threads • 16 GB RAM • high-capacity workloads',
    cpuModel: 'Xeon E-2300',
    availableProcessors: 8,
    maxHeapMB: 16384,
    tier: 'HIGH' as HardwareTier,
  },
] as const;

const FORK_OPTIONS = [
  { id: 'paper' as const, label: 'Paper', description: 'Most popular, good defaults' },
  { id: 'purpur' as const, label: 'Purpur', description: 'Paper fork with extra features' },
  { id: 'pufferfish' as const, label: 'Pufferfish', description: 'Performance-focused Paper fork' },
  { id: 'leaf' as const, label: 'Leaf', description: 'Experimental high-performance fork' },
  { id: 'folia' as const, label: 'Folia', description: 'Multi-threaded region-based fork' },
  { id: 'luminol' as const, label: 'Luminol', description: 'Folia fork with extra Paper features' },
  { id: 'spigot' as const, label: 'Spigot', description: 'Classic server software' },
  { id: 'vanilla' as const, label: 'Vanilla', description: 'Unmodified Minecraft server' },
] as const;

interface HardwareWizardProps {
  onCompleteHardwareBaseline?: () => void;
}

export function HardwareWizard({ onCompleteHardwareBaseline }: HardwareWizardProps) {
  const [step, setStep] = useState(0);
  const { hardware, platform, setHardware, setPlatform, setTier, playerCount, setPlayerCount, benchmarkScore, setBenchmarkResult } = useLessLagStore();

  // CPU live search state
  const [cpuQuery, setCpuQuery] = useState(hardware.cpuModel ?? '');
  const [cpuResults, setCpuResults] = useState<CpuSearchResult[]>([]);
  const [cpuSearching, setCpuSearching] = useState(false);
  const [showDropdown, setShowDropdown] = useState(false);
  const searchDebounce = useRef<ReturnType<typeof setTimeout> | null>(null);
  const dropdownRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const q = cpuQuery.trim();
    if (q.length < 2) { setCpuResults([]); return; }
    if (searchDebounce.current) clearTimeout(searchDebounce.current);
    searchDebounce.current = setTimeout(async () => {
      setCpuSearching(true);
      try {
        const res = await fetch(`${API_BASE}/api/benchmarks/search?q=${encodeURIComponent(q)}`);
        if (res.ok) {
          const data = await res.json();
          setCpuResults(data.results ?? []);
          setShowDropdown(true);
        }
      } catch { /* silently ignore */ }
      finally { setCpuSearching(false); }
    }, 280);
    return () => { if (searchDebounce.current) clearTimeout(searchDebounce.current); };
  }, [cpuQuery]);

  const selectCpu = (r: CpuSearchResult) => {
    setCpuQuery(r.model);
    setHardware({ cpuModel: r.model, ...(r.cores != null ? { availableProcessors: r.cores } : {}) });
    setBenchmarkResult(r.sc, r.tier as HardwareTier);
    setShowDropdown(false);
    setCpuResults([]);
  };

  const tierBadge = (tier: string) => {
    const map: Record<string, string> = { HIGH: 'bg-green-500/20 text-green-400', MID: 'bg-yellow-500/20 text-yellow-400', LOW: 'bg-red-500/20 text-red-400' };
    return map[tier] ?? 'bg-gray-500/20 text-gray-400';
  };

  const next = () => setStep((s) => Math.min(s + 1, STEPS.length - 1));
  const prev = () => setStep((s) => Math.max(s - 1, 0));

  const getClassification = (): HardwareClassification =>
    classifyHardware(
      hardware,
      benchmarkScore ?? undefined,
      hardware.averageMspt > 0 ? hardware.averageMspt : undefined,
    );

  const autoDetectTier = () => {
    const cls = getClassification();
    setTier(cls.tier);
  };

  // Live classification for display
  const classification = getClassification();
  const detectedTier: HardwareTier = classification.tier;

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      {/* Step indicator */}
      <div className="flex items-center justify-center gap-1 sm:gap-2">
        {STEPS.map((label, i) => (
          <div key={label} className="flex items-center gap-1 sm:gap-2">
            <button
              onClick={() => setStep(i)}
              className={`flex h-7 w-7 items-center justify-center rounded-full text-xs font-bold transition-all sm:h-8 sm:w-8 ${i === step
                ? 'bg-[var(--accent)] text-white'
                : i < step
                  ? 'bg-[var(--success)] text-white'
                  : 'bg-[var(--bg-elevated)] text-[var(--text-muted)]'
                }`}
            >
              {i < step ? '✓' : i + 1}
            </button>
            <span className={`hidden text-xs sm:inline ${i === step ? 'text-[var(--text-primary)]' : 'text-[var(--text-muted)]'}`}>
              {label}
            </span>
            {i < STEPS.length - 1 && <div className="mx-1 h-px w-4 bg-[var(--border)] sm:mx-2 sm:w-8" />}
          </div>
        ))}
      </div>

      {/* Step content */}
      <div className="rounded-xl border border-[var(--border)] bg-[var(--bg-card)] p-4 sm:p-6">
        {step === 0 && (
          <div className="space-y-5">
            <h3 className="text-lg font-semibold text-[var(--text-primary)]">CPU & Memory</h3>

            <div>
              <label className="mb-2 block text-sm text-[var(--text-muted)]">Common Hosting Plans</label>
              <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
                {VPS_PRESETS.map((preset) => (
                  <button
                    key={preset.id}
                    onClick={() => {
                      setHardware({
                        cpuModel: preset.cpuModel,
                        availableProcessors: preset.availableProcessors,
                        maxHeapMB: preset.maxHeapMB,
                      });
                      setTier(preset.tier);
                    }}
                    className="rounded-lg border border-[var(--border)] bg-[var(--bg-primary)] p-3 text-left transition-all hover:border-[var(--accent)]"
                  >
                    <div className="text-sm font-medium text-[var(--text-primary)]">{preset.label}</div>
                    <div className="text-xs text-[var(--text-muted)]">{preset.details}</div>
                  </button>
                ))}
              </div>
            </div>

            <div>
              <label className="mb-1 block text-sm text-[var(--text-muted)]">CPU Model</label>
              <div className="relative" ref={dropdownRef}>
                <div className="relative">
                  <input
                    type="text"
                    value={cpuQuery}
                    onChange={(e) => { setCpuQuery(e.target.value); setHardware({ cpuModel: e.target.value }); }}
                    onFocus={() => cpuResults.length > 0 && setShowDropdown(true)}
                    placeholder="Search CPU (e.g. EPYC 9354, i9-14900K, Ryzen 9 7950X)…"
                    className="w-full rounded-lg border border-[var(--border)] bg-[var(--bg-primary)] px-3 py-2 pr-8 text-sm text-[var(--text-primary)] placeholder:text-[var(--text-muted)] focus:border-[var(--accent)] focus:outline-none"
                  />
                  {cpuSearching && (
                    <span className="absolute right-2.5 top-2.5 text-xs text-[var(--text-muted)] animate-pulse">⟳</span>
                  )}
                  {benchmarkScore != null && !cpuSearching && (
                    <span className="absolute right-2.5 top-2 text-[10px] font-bold text-[var(--accent)]">⚡{benchmarkScore}</span>
                  )}
                </div>
                {showDropdown && cpuResults.length > 0 && (
                  <div className="absolute z-50 mt-1 w-full rounded-lg border border-[var(--border)] bg-[var(--bg-card)] shadow-xl overflow-hidden">
                    {cpuResults.map((r) => (
                      <button
                        key={r.model}
                        onMouseDown={(e) => { e.preventDefault(); selectCpu(r); }}
                        className="flex w-full items-center justify-between gap-2 px-3 py-2.5 text-left hover:bg-[var(--bg-elevated)] transition-colors"
                      >
                        <div className="min-w-0">
                          <div className="truncate text-sm text-[var(--text-primary)]">{r.model}</div>
                          <div className="text-xs text-[var(--text-muted)]">
                            {r.clockGhz != null ? `${r.clockGhz} GHz` : ''}
                            {r.cores != null ? ` · ${r.cores} cores` : ''}
                          </div>
                        </div>
                        <div className="flex items-center gap-1.5 shrink-0">
                          <span className="text-xs font-mono font-bold text-[var(--text-secondary)]">SC {r.sc}</span>
                          {r.mc != null && (
                            <span className="text-[10px] text-[var(--text-muted)]">MC {r.mc}</span>
                          )}
                          <span className={`text-[10px] font-bold px-1.5 py-0.5 rounded ${tierBadge(r.tier)}`}>{r.tier}</span>
                        </div>
                      </button>
                    ))}
                  </div>
                )}
              </div>
              <p className="mt-1 text-xs text-[var(--text-muted)]">Geekbench 6 Single-Core scores — MC = Multi-Core</p>
            </div>

            <div>
              <label className="mb-1 block text-sm text-[var(--text-muted)]">
                CPU Cores: <span className="text-[var(--text-primary)] font-medium">{hardware.availableProcessors}</span>
              </label>
              <input
                type="range"
                min={1}
                max={16}
                value={hardware.availableProcessors}
                onChange={(e) => setHardware({ availableProcessors: Number(e.target.value) })}
                className="w-full accent-[var(--accent)]"
              />
              <div className="flex justify-between text-xs text-[var(--text-muted)]">
                <span>1</span><span>4</span><span>8</span><span>16</span>
              </div>
            </div>

            <div>
              <label className="mb-1 block text-sm text-[var(--text-muted)]">
                Allocated RAM: <span className="text-[var(--text-primary)] font-medium">{(hardware.maxHeapMB / 1024).toFixed(1)} GB</span>
              </label>
              <input
                type="range"
                min={1024}
                max={24576}
                step={512}
                value={hardware.maxHeapMB}
                onChange={(e) => setHardware({ maxHeapMB: Number(e.target.value) })}
                className="w-full accent-[var(--accent)]"
              />
              <div className="flex justify-between text-xs text-[var(--text-muted)]">
                <span>1 GB</span><span>4 GB</span><span>8 GB</span><span>16+ GB</span>
              </div>
            </div>

            <div>
              <label className="mb-1 block text-sm text-[var(--text-muted)]">
                Avg MSPT: <span className="text-[var(--text-primary)] font-medium">{hardware.averageMspt}ms</span>
                {hardware.averageMspt > 50 && (
                  <span className="ml-2 text-[var(--danger)]">(high latency)</span>
                )}
              </label>
              <input
                type="range"
                min={5}
                max={100}
                value={hardware.averageMspt}
                onChange={(e) => setHardware({ averageMspt: Number(e.target.value) })}
                className="w-full accent-[var(--accent)]"
              />
              <div className="flex justify-between text-xs text-[var(--text-muted)]">
                <span>5ms</span><span>20ms</span><span>50ms</span><span>100ms</span>
              </div>
            </div>
          </div>
        )}

        {step === 1 && (
          <div className="space-y-5">
            <h3 className="text-lg font-semibold text-[var(--text-primary)]">Server Software</h3>

            <div>
              <label className="mb-2 block text-sm text-[var(--text-muted)]">Server Fork</label>
              <div className="grid grid-cols-2 gap-2">
                {FORK_OPTIONS.map((f) => (
                  <button
                    key={f.id}
                    onClick={() =>
                      setPlatform({
                        fork: f.id,
                        isPaper: ['paper', 'purpur', 'pufferfish', 'leaf', 'folia', 'luminol'].includes(f.id),
                        isPurpur: f.id === 'purpur',
                        isPufferfish: f.id === 'pufferfish',
                        isLeaf: f.id === 'leaf',
                        hasFolia: f.id === 'folia' || f.id === 'luminol',
                        isLuminol: f.id === 'luminol',
                      })
                    }
                    className={`rounded-lg border p-3 text-left transition-all ${platform.fork === f.id
                      ? 'border-[var(--accent)] bg-[var(--accent)]/10'
                      : 'border-[var(--border)] hover:border-[var(--text-muted)]'
                      }`}
                  >
                    <div className="text-sm font-medium text-[var(--text-primary)]">{f.label}</div>
                    <div className="text-xs text-[var(--text-muted)]">{f.description}</div>
                  </button>
                ))}
              </div>
            </div>

            <div>
              <label className="mb-1 block text-sm text-[var(--text-muted)]">Minecraft Version</label>
              <input
                type="text"
                value={platform.version}
                onChange={(e) => setPlatform({ version: e.target.value })}
                placeholder="1.21"
                className="w-full rounded-lg border border-[var(--border)] bg-[var(--bg-primary)] px-3 py-2 text-sm text-[var(--text-primary)] focus:border-[var(--accent)] focus:outline-none"
              />
            </div>
          </div>
        )}

        {step === 2 && (
          <div className="space-y-5">
            <h3 className="text-lg font-semibold text-[var(--text-primary)]">World Info</h3>

            <div>
              <label className="mb-1 block text-sm text-[var(--text-muted)]">
                Expected Players: <span className="text-[var(--text-primary)] font-medium">{playerCount}</span>
              </label>
              <input
                type="range"
                min={1}
                max={500}
                value={playerCount}
                onChange={(e) => setPlayerCount(Number(e.target.value))}
                className="w-full accent-[var(--accent)]"
              />
              <div className="flex justify-between text-xs text-[var(--text-muted)]">
                <span>1</span><span>50</span><span>100</span><span>200</span><span>500</span>
              </div>
            </div>

            <div>
              <label className="mb-1 block text-sm text-[var(--text-muted)]">
                GC Overhead: <span className="text-[var(--text-primary)] font-medium">{hardware.gcOverheadPercent}%</span>
                {hardware.gcOverheadPercent > 15 && (
                  <span className="ml-2 text-[var(--warning)]">(high, consider more RAM)</span>
                )}
              </label>
              <input
                type="range"
                min={0}
                max={50}
                value={hardware.gcOverheadPercent}
                onChange={(e) => setHardware({ gcOverheadPercent: Number(e.target.value) })}
                className="w-full accent-[var(--accent)]"
              />
            </div>

            {/* Hardware score breakdown */}
            <div className="rounded-lg border border-[var(--border)] bg-[var(--bg-elevated)] p-4 space-y-3">
              <div className="flex items-center justify-between">
                <div className="text-xs text-[var(--text-muted)] uppercase tracking-wide">Hardware Score</div>
                <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${classification.confidence === 'HIGH' ? 'bg-green-500/20 text-green-400' :
                  classification.confidence === 'MED' ? 'bg-yellow-500/20 text-yellow-400' :
                    'bg-gray-500/20 text-gray-400'
                  }`}>
                  {classification.confidence === 'HIGH' ? '🎯 Benchmark data' :
                    classification.confidence === 'MED' ? '🔍 CPU model match' : '📊 Specs only'}
                </span>
              </div>

              {/* Score bar */}
              <div>
                <div className="flex justify-between mb-1">
                  <span className="text-lg font-bold text-[var(--text-primary)]">
                    {HardwareTierMeta[detectedTier].displayName}
                  </span>
                  <span className="text-lg font-bold text-[var(--accent)]">{classification.score}/100</span>
                </div>
                <div className="h-2 rounded-full bg-[var(--bg-primary)] overflow-hidden">
                  <div
                    className={`h-full rounded-full transition-all duration-500 ${detectedTier === 'HIGH' ? 'bg-green-500' :
                      detectedTier === 'MID' ? 'bg-yellow-500' : 'bg-red-500'
                      }`}
                    style={{ width: `${classification.score}%` }}
                  />
                </div>
              </div>

              {/* Score breakdown */}
              <div className="grid grid-cols-2 gap-1.5 text-xs">
                {[
                  { label: 'Benchmark', val: classification.breakdown.benchmarkScore, max: 40 },
                  { label: 'CPU Topology', val: classification.breakdown.topologyScore, max: 20 },
                  { label: 'Core Count', val: classification.breakdown.coreScore, max: 15 },
                  { label: 'RAM / Heap', val: classification.breakdown.ramScore, max: 15 },
                  { label: 'Live MSPT', val: classification.breakdown.msptBonus, max: 10, signed: true },
                ].map(({ label, val, max, signed }) => (
                  <div key={label} className="flex items-center gap-2">
                    <span className="text-[var(--text-muted)] w-20 shrink-0">{label}</span>
                    <div className="flex-1 h-1.5 rounded-full bg-[var(--bg-primary)] overflow-hidden">
                      <div
                        className={`h-full rounded-full ${val < 0 ? 'bg-red-500' : 'bg-[var(--accent)]'}`}
                        style={{ width: `${Math.max(0, Math.abs(val) / Math.abs(max) * 100)}%` }}
                      />
                    </div>
                    <span className={`w-8 text-right tabular-nums ${val < 0 ? 'text-red-400' : 'text-[var(--text-secondary)]'}`}>
                      {signed && val > 0 ? '+' : ''}{val}
                    </span>
                  </div>
                ))}
              </div>

              {classification.reason && (
                <p className="text-xs text-[var(--text-muted)] border-t border-[var(--border)] pt-2 leading-relaxed">
                  💡 {classification.reason}
                </p>
              )}

              <button
                onClick={() => {
                  autoDetectTier();
                  onCompleteHardwareBaseline?.();
                }}
                className="w-full rounded-lg bg-[var(--accent)] px-3 py-2 text-sm font-medium text-white hover:bg-[var(--accent-hover)] transition-colors"
              >
                Apply {HardwareTierMeta[detectedTier].displayName} Baseline
              </button>
            </div>

          </div>
        )}
      </div>

      {/* Navigation */}
      <div className="flex justify-between">
        <button
          onClick={prev}
          disabled={step === 0}
          className="rounded-lg border border-[var(--border)] px-4 py-2 text-sm text-[var(--text-secondary)] hover:bg-[var(--bg-elevated)] disabled:opacity-30 transition-colors"
        >
          Previous
        </button>
        <button
          onClick={next}
          disabled={step === STEPS.length - 1}
          className="rounded-lg bg-[var(--accent)] px-4 py-2 text-sm font-medium text-white hover:bg-[var(--accent-hover)] disabled:opacity-30 transition-colors"
        >
          Next
        </button>
      </div>
    </div>
  );
}
