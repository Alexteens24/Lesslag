'use client';

import { useEffect, useState } from 'react';
import { useRouter, useParams } from 'next/navigation';
import { useLessLagStore } from '@/store/lesslag-store';
import type { GameProfile, HardwareTier, AggressivenessLevel } from '@lesslag/shared-rules';

const API_URL =
  process.env.NEXT_PUBLIC_API_URL ??
  'https://lesslag-api.daucatmoitu.workers.dev';

const VALID_PROFILES = new Set<GameProfile>(['SMP', 'SKYBLOCK', 'MINIGAME', 'CREATIVE']);
const VALID_TIERS = new Set<HardwareTier>(['LOW', 'MID', 'HIGH']);
const VALID_AGGRESSIVENESS = new Set<AggressivenessLevel>(['SAFE', 'BALANCED', 'AGGRESSIVE']);

function normalizeEnum<T extends string>(value: unknown, validValues: Set<T>, fallback: T): T {
  if (typeof value !== 'string') return fallback;
  const normalized = value.trim().toUpperCase() as T;
  return validValues.has(normalized) ? normalized : fallback;
}

export default function SessionPage() {
  const router = useRouter();
  const params = useParams();
  const token = params.token as string;

  const [error, setError] = useState<string | null>(null);

  const {
    setProfile,
    setTier,
    setAggressiveness,
    setPlayerCount,
    setPlugins,
    setPlatform,
    setHardware,
    setConfigs,
    setConnectedServer,
  } = useLessLagStore();

  useEffect(() => {
    if (!token) return;

    fetch(`${API_URL}/api/sessions/${token}`)
      .then((res) => {
        if (!res.ok)
          throw new Error(
            res.status === 404
              ? 'Session not found or expired'
              : `Failed to load session (${res.status})`,
          );
        return res.json();
      })
      .then((data) => {
        const profile = normalizeEnum(data.profile, VALID_PROFILES, 'SMP');
        const tier = normalizeEnum(data.tier, VALID_TIERS, 'MID');
        const aggressiveness = normalizeEnum(
          data.aggressiveness,
          VALID_AGGRESSIVENESS,
          'BALANCED',
        );

        // ── hydrate Zustand store ──
        setProfile(profile);
        setTier(tier);
        setAggressiveness(aggressiveness);
        if (data.playerCount != null) setPlayerCount(data.playerCount);
        if (data.plugins) setPlugins(data.plugins);
        if (data.platform) setPlatform(data.platform);
        if (data.hardware) setHardware(data.hardware);
        if (data.configs) setConfigs(data.configs);

        // Wire up server identity for live metrics
        if (data.serverId) {
          setConnectedServer(data.serverId, data.serverName ?? 'Minecraft Server');
        }

        // Navigate to main configurator – store is already populated
        router.replace('/');
      })
      .catch((err) => {
        setError(err.message ?? 'Unknown error');
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  /* ── Error state ─────────────────────────────────── */
  if (error) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-[var(--bg-primary)] text-[var(--text-primary)]">
        <div className="space-y-4 text-center">
          <h1 className="text-2xl font-bold text-[var(--danger)]">Session Error</h1>
          <p className="text-[var(--text-muted)]">{error}</p>
          <a
            href="/"
            className="inline-block rounded bg-[var(--accent)] px-4 py-2 hover:bg-[var(--accent-hover)] text-white"
          >
            Go to Configurator
          </a>
        </div>
      </div>
    );
  }

  /* ── Loading state ───────────────────────────────── */
  return (
    <div className="flex min-h-screen items-center justify-center bg-[var(--bg-primary)] text-[var(--text-primary)]">
      <div className="space-y-4 text-center">
        <div className="mx-auto h-8 w-8 animate-spin rounded-full border-2 border-[var(--accent)] border-t-transparent" />
        <p className="text-[var(--text-muted)]">Loading server configuration…</p>
        <p className="text-xs text-[var(--text-subtle)]">Token: {token}</p>
      </div>
    </div>
  );
}
