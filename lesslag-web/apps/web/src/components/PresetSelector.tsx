'use client';

import { useLessLagStore } from '@/store/lesslag-store';
import {
  GameProfiles,
  HardwareTiers,
  AggressivenessLevels,
  GameProfileMeta,
  HardwareTierMeta,
  AggressivenessLevelMeta,
} from '@lesslag/shared-rules';
import type { GameProfile, HardwareTier, AggressivenessLevel } from '@lesslag/shared-rules';

interface SelectorProps<T extends string> {
  label: string;
  value: T;
  options: readonly T[];
  meta: Record<T, { displayName: string; description: string }>;
  onChange: (v: T) => void;
}

function AxisSelector<T extends string>({ label, value, options, meta, onChange }: SelectorProps<T>) {
  return (
    <div>
      <label className="mb-2 block text-sm font-medium text-[var(--text-secondary)]">{label}</label>
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-2 lg:grid-cols-4">
        {options.map((opt) => (
          <button
            key={opt}
            onClick={() => onChange(opt)}
            className={`rounded-lg border p-3 text-left transition-all ${value === opt
              ? 'border-[var(--accent)] bg-[var(--accent)]/10 text-[var(--text-primary)]'
              : 'border-[var(--border)] bg-[var(--bg-card)] text-[var(--text-secondary)] hover:border-[var(--border-hover)]'
              }`}
          >
            <div className="font-medium">{meta[opt].displayName}</div>
            <div className="mt-1 text-xs text-[var(--text-muted)]">{meta[opt].description}</div>
          </button>
        ))}
      </div>
    </div>
  );
}

export function PresetSelector() {
  const {
    profile, tier, aggressiveness, playerCount, preset,
    setProfile, setTier, setAggressiveness, setPlayerCount,
    generatePresetAction, applyPreset, runEvaluation,
    serverPayload, benchmarkScore,
  } = useLessLagStore();

  // Tier is considered auto-detected any time we have a server payload —
  // regardless of whether the Geekbench lookup succeeded.
  const hasAutoTier = serverPayload != null;

  const handleGenerate = () => {
    generatePresetAction();
    runEvaluation();
  };

  return (
    <div className="space-y-6">
      <AxisSelector<GameProfile>
        label="Game Profile"
        value={profile}
        options={GameProfiles}
        meta={GameProfileMeta}
        onChange={setProfile}
      />

      {hasAutoTier && serverPayload ? (
        /* ── Locked auto-detected hardware summary ────────────────────── */
        <div className="rounded-lg border border-emerald-600/25 bg-emerald-500/5 px-4 py-3">
          <div className="mb-1.5 flex items-center gap-2">
            <span className="text-xs font-semibold uppercase tracking-wider text-[var(--text-muted)]">
              Hardware
            </span>
            <span className="inline-flex items-center gap-1 rounded-full bg-emerald-500/15 px-2 py-0.5 text-[10px] font-bold uppercase tracking-wide text-emerald-400">
              ✓ Auto-detected · {HardwareTierMeta[tier].displayName}
            </span>
          </div>
          <div className="text-sm font-medium text-[var(--text-primary)] truncate">
            {serverPayload.cpuModel}
          </div>
          <div className="mt-1 flex flex-wrap gap-3 text-xs text-[var(--text-muted)]">
            <span>{serverPayload.cores} cores</span>
            <span>{Math.round(serverPayload.maxHeapMb / 1024)} GB heap</span>
            {benchmarkScore ? (
              <span>{benchmarkScore.toLocaleString()} pts Geekbench SC</span>
            ) : null}
          </div>
        </div>
      ) : (
        <AxisSelector<HardwareTier>
          label="Hardware Tier"
          value={tier}
          options={HardwareTiers}
          meta={HardwareTierMeta}
          onChange={setTier}
        />
      )}

      <AxisSelector<AggressivenessLevel>
        label="Aggressiveness"
        value={aggressiveness}
        options={AggressivenessLevels}
        meta={AggressivenessLevelMeta}
        onChange={setAggressiveness}
      />

      <div>
        <label className="mb-2 block text-sm font-medium text-[var(--text-secondary)]">
          Expected Player Count
        </label>
        <input
          type="range"
          min={1}
          max={200}
          value={playerCount}
          onChange={(e) => setPlayerCount(Number(e.target.value))}
          className="w-full accent-[var(--accent)]"
        />
        <div className="mt-1 text-sm text-[var(--text-muted)]">{playerCount} players</div>
      </div>

      <div className="flex flex-col gap-3 sm:flex-row">
        <button
          onClick={handleGenerate}
          className="rounded-lg bg-[var(--accent)] px-6 py-3 text-sm font-medium text-white transition-colors hover:bg-[var(--accent-hover)] sm:py-2.5"
        >
          Generate Recommendations
        </button>
        {preset && (
          <button
            onClick={applyPreset}
            className="rounded-lg border border-[var(--accent)] px-6 py-3 text-sm font-medium text-[var(--accent)] transition-colors hover:bg-[var(--accent)]/10 sm:py-2.5"
          >
            Apply Preset to Config
          </button>
        )}
      </div>

      {preset && (
        <div className="rounded-lg border border-[var(--border)] bg-[var(--bg-card)] p-4">
          <h3 className="mb-2 font-semibold text-[var(--text-primary)]">{preset.label}</h3>
          <pre className="whitespace-pre-wrap text-sm text-[var(--text-secondary)]">
            {preset.description}
          </pre>
        </div>
      )}
    </div>
  );
}
