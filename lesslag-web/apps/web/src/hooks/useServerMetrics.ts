'use client';

import { useEffect, useState } from 'react';

const API_URL =
  process.env.NEXT_PUBLIC_API_URL ??
  'https://lesslag-api.daucatmoitu.workers.dev';

export interface HeartbeatSnapshot {
  tps: number;
  tps1m: number;
  mspt: { current: number; min: number; max: number };
  gcOverheadPercent: number;
  heapUsedMB: number;
  heapMaxMB: number;
  onlinePlayers: number;
  timestamp: number;
}

export interface ServerMetricsData {
  metrics: HeartbeatSnapshot[];
  /** epoch-ms of the last heartbeat, or null if never received. */
  lastSeen: number | null;
  isOnline: boolean;
}

/**
 * Polls GET /api/servers/:id/metrics on the given interval.
 * Returns null data while loading or when serverId is not set.
 */
export function useServerMetrics(
  serverId: string | null,
  pollIntervalMs = 30_000,
): { data: ServerMetricsData | null; error: string | null } {
  const [data, setData] = useState<ServerMetricsData | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!serverId) {
      setData(null);
      setError(null);
      return;
    }

    let cancelled = false;

    const poll = async () => {
      try {
        const res = await fetch(`${API_URL}/api/servers/${serverId}/metrics`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const body = (await res.json()) as ServerMetricsData;
        if (!cancelled) {
          setData(body);
          setError(null);
        }
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : 'Failed to fetch metrics');
        }
      }
    };

    poll(); // immediate first fetch
    const id = setInterval(poll, pollIntervalMs);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, [serverId, pollIntervalMs]);

  return { data, error };
}
