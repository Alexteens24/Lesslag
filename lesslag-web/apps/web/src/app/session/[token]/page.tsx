'use client';

import { useEffect, useState } from 'react';
import { useRouter, useParams } from 'next/navigation';
import { useLessLagStore } from '@/store/lesslag-store';

const API_URL =
  process.env.NEXT_PUBLIC_API_URL ??
  'https://lesslag-api.daucatmoitu.workers.dev';

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
        // ── hydrate Zustand store ──
        if (data.profile) setProfile(data.profile);
        if (data.tier) setTier(data.tier);
        if (data.aggressiveness) setAggressiveness(data.aggressiveness);
        if (data.playerCount != null) setPlayerCount(data.playerCount);
        if (data.plugins) setPlugins(data.plugins);
        if (data.platform) setPlatform(data.platform);
        if (data.hardware) setHardware(data.hardware);
        if (data.configs) setConfigs(data.configs);

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
      <div className="flex min-h-screen items-center justify-center bg-gray-950 text-white">
        <div className="space-y-4 text-center">
          <h1 className="text-2xl font-bold text-red-400">Session Error</h1>
          <p className="text-gray-400">{error}</p>
          <a
            href="/"
            className="inline-block rounded bg-blue-600 px-4 py-2 hover:bg-blue-700"
          >
            Go to Configurator
          </a>
        </div>
      </div>
    );
  }

  /* ── Loading state ───────────────────────────────── */
  return (
    <div className="flex min-h-screen items-center justify-center bg-gray-950 text-white">
      <div className="space-y-4 text-center">
        <div className="mx-auto h-8 w-8 animate-spin rounded-full border-2 border-blue-400 border-t-transparent" />
        <p className="text-gray-400">Loading server configuration…</p>
        <p className="text-xs text-gray-600">Token: {token}</p>
      </div>
    </div>
  );
}
