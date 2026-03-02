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
      <div className="grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-4">
        {options.map((opt) => (
          <button
            key={opt}
            onClick={() => onChange(opt)}
            className={`rounded-lg border p-3 text-left transition-all ${
              value === opt
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
  } = useLessLagStore();

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

      <AxisSelector<HardwareTier>
        label="Hardware Tier"
        value={tier}
        options={HardwareTiers}
        meta={HardwareTierMeta}
        onChange={setTier}
      />

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

      <div className="flex gap-3">
        <button
          onClick={handleGenerate}
          className="rounded-lg bg-[var(--accent)] px-6 py-2.5 font-medium text-white transition-colors hover:bg-[var(--accent-hover)]"
        >
          Generate Recommendations
        </button>
        {preset && (
          <button
            onClick={applyPreset}
            className="rounded-lg border border-[var(--accent)] px-6 py-2.5 font-medium text-[var(--accent)] transition-colors hover:bg-[var(--accent)]/10"
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
