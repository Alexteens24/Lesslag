'use client';

import { useState } from 'react';
import { useLessLagStore } from '@/store/lesslag-store';
import { HardwareTierMeta } from '@lesslag/shared-rules';
import type { HardwareTier } from '@lesslag/shared-rules';

const STEPS = ['CPU & RAM', 'Server Software', 'World Info'] as const;

const CPU_MODELS = [
  'AMD EPYC VPS (shared vCPU)',
  'Intel Xeon VPS (shared vCPU)',
  'AMD Ryzen VPS (dedicated vCPU)',
  'Intel Core i5',
  'Intel Core i7',
  'Intel Core i9',
  'AMD Ryzen 5',
  'AMD Ryzen 7',
  'AMD Ryzen 9',
  'Xeon E-2300',
  'EPYC 7003',
  'Apple M1/M2/M3',
  'Other',
] as const;

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
  { id: 'spigot' as const, label: 'Spigot', description: 'Classic server software' },
  { id: 'vanilla' as const, label: 'Vanilla', description: 'Unmodified Minecraft server' },
] as const;

export function HardwareWizard() {
  const [step, setStep] = useState(0);
  const { hardware, platform, setHardware, setPlatform, setTier, playerCount, setPlayerCount } = useLessLagStore();

  const next = () => setStep((s) => Math.min(s + 1, STEPS.length - 1));
  const prev = () => setStep((s) => Math.max(s - 1, 0));

  const autoDetectTier = () => {
    const cores = hardware.availableProcessors;
    const ram = hardware.maxHeapMB;

    if (cores >= 6 && ram >= 10240) {
      setTier('HIGH');
    } else if (cores >= 4 && ram >= 6144) {
      setTier('MID');
    } else {
      setTier('LOW');
    }
  };

  const detectedTier: HardwareTier =
    hardware.availableProcessors >= 6 && hardware.maxHeapMB >= 10240
      ? 'HIGH'
      : hardware.availableProcessors >= 4 && hardware.maxHeapMB >= 6144
        ? 'MID'
        : 'LOW';

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      {/* Step indicator */}
      <div className="flex items-center justify-center gap-1 sm:gap-2">
        {STEPS.map((label, i) => (
          <div key={label} className="flex items-center gap-1 sm:gap-2">
            <button
              onClick={() => setStep(i)}
              className={`flex h-7 w-7 items-center justify-center rounded-full text-xs font-bold transition-all sm:h-8 sm:w-8 ${
                i === step
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
              <select
                value={hardware.cpuModel}
                onChange={(e) => setHardware({ cpuModel: e.target.value })}
                className="w-full rounded-lg border border-[var(--border)] bg-[var(--bg-primary)] px-3 py-2 text-sm text-[var(--text-primary)] focus:border-[var(--accent)] focus:outline-none"
              >
                {CPU_MODELS.map((m) => (
                  <option key={m} value={m}>{m}</option>
                ))}
              </select>
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
                        isPaper: ['paper', 'purpur', 'pufferfish', 'leaf'].includes(f.id),
                        isPurpur: f.id === 'purpur',
                        isPufferfish: f.id === 'pufferfish',
                        isLeaf: f.id === 'leaf',
                      })
                    }
                    className={`rounded-lg border p-3 text-left transition-all ${
                      platform.fork === f.id
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

            {/* Auto-detected tier summary */}
            <div className="rounded-lg bg-[var(--bg-elevated)] p-4">
              <div className="text-xs text-[var(--text-muted)] uppercase mb-2">Detected Hardware Tier</div>
              <div className="flex items-center justify-between">
                <div>
                  <span className="text-lg font-bold text-[var(--text-primary)]">
                    {HardwareTierMeta[detectedTier].displayName}
                  </span>
                  <p className="text-xs text-[var(--text-muted)] mt-1">
                    {hardware.availableProcessors} vCPU/thread(s), {(hardware.maxHeapMB / 1024).toFixed(1)} GB RAM, {platform.fork}
                  </p>
                </div>
                <button
                  onClick={() => {
                    autoDetectTier();
                    // Navigate to presets tab
                    useLessLagStore.getState().setActiveTab('presets');
                  }}
                  className="rounded-lg bg-[var(--accent)] px-3 py-2 text-xs font-medium text-white hover:bg-[var(--accent-hover)] transition-colors sm:px-4 sm:text-sm"
                >
                  Apply Configuration
                </button>
              </div>
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
