'use client';

import { useServerMetrics, type HeartbeatSnapshot } from '@/hooks/useServerMetrics';

interface Props {
  serverId: string;
}

/** Colour-codes a TPS value: ≥18 green, ≥15 amber, <15 red. */
function tpsColor(tps: number): string {
  if (tps >= 18) return 'text-[var(--success)]';
  if (tps >= 15) return 'text-[var(--warning)]';
  return 'text-[var(--danger)]';
}

/** Colour-codes an MSPT value: ≤30 green, ≤45 amber, >45 red. */
function msptColor(mspt: number): string {
  if (mspt <= 30) return 'text-[var(--success)]';
  if (mspt <= 45) return 'text-[var(--warning)]';
  return 'text-[var(--danger)]';
}

/** Ultra-compact sparkline rendered purely with CSS flex bars. */
function Sparkline({
  values,
  min,
  max,
  color,
}: {
  values: number[];
  min: number;
  max: number;
  color: string;
}) {
  const range = max - min || 1;
  const last = values.slice(-24); // last 12 min at 30 s intervals
  return (
    <div className="flex items-end gap-px h-5" aria-hidden>
      {last.map((v, i) => {
        const pct = Math.max(5, Math.round(((v - min) / range) * 100));
        return (
          <div
            key={i}
            className={`w-1 rounded-sm opacity-80 ${color}`}
            style={{ height: `${pct}%`, backgroundColor: 'currentColor' }}
          />
        );
      })}
    </div>
  );
}

function MetricRow({
  label,
  value,
  unit,
  color,
  sparkValues,
  sparkMin,
  sparkMax,
}: {
  label: string;
  value: string;
  unit?: string;
  color: string;
  sparkValues: number[];
  sparkMin: number;
  sparkMax: number;
}) {
  return (
    <div>
      <div className="flex items-center justify-between mb-0.5">
        <span className="text-[10px] text-[var(--text-muted)]">{label}</span>
        <span className={`text-xs font-semibold ${color}`}>
          {value}
          {unit && <span className="text-[9px] font-normal ml-0.5 text-[var(--text-muted)]">{unit}</span>}
        </span>
      </div>
      {sparkValues.length > 1 && (
        <Sparkline values={sparkValues} min={sparkMin} max={sparkMax} color={color} />
      )}
    </div>
  );
}

/**
 * LiveMetricsPanel — displays live TPS, MSPT, heap and player count
 * sourced from the 30-minute rolling metrics window for a connected server.
 *
 * Designed to be embedded in the Sidebar (narrow width).
 */
export function LiveMetricsPanel({ serverId }: Props) {
  const { data, error } = useServerMetrics(serverId);

  if (error) {
    return (
      <p className="text-[10px] text-[var(--danger)] px-0.5">{error}</p>
    );
  }

  if (!data || data.metrics.length === 0) {
    return (
      <p className="text-[10px] text-[var(--text-muted)] px-0.5 animate-pulse">
        Waiting for heartbeat…
      </p>
    );
  }

  const latest: HeartbeatSnapshot = data.metrics[data.metrics.length - 1];
  const tpsHistory   = data.metrics.map((m) => m.tps);
  const msptHistory  = data.metrics.map((m) => m.mspt.current);
  const heapHistory  = data.metrics.map((m) =>
    Math.round((m.heapUsedMB / m.heapMaxMB) * 100),
  );

  const heapPct = Math.round((latest.heapUsedMB / latest.heapMaxMB) * 100);
  const heapColor =
    heapPct < 70 ? 'text-[var(--success)]'
    : heapPct < 85 ? 'text-[var(--warning)]'
    : 'text-[var(--danger)]';

  return (
    <div className="space-y-2">
      <MetricRow
        label="TPS"
        value={latest.tps.toFixed(1)}
        color={tpsColor(latest.tps)}
        sparkValues={tpsHistory}
        sparkMin={0}
        sparkMax={20}
      />
      <MetricRow
        label="MSPT"
        value={latest.mspt.current.toFixed(1)}
        unit="ms"
        color={msptColor(latest.mspt.current)}
        sparkValues={msptHistory}
        sparkMin={0}
        sparkMax={50}
      />
      <MetricRow
        label="Heap"
        value={`${heapPct}%`}
        color={heapColor}
        sparkValues={heapHistory}
        sparkMin={0}
        sparkMax={100}
      />
      <div className="flex items-center justify-between text-[10px]">
        <span className="text-[var(--text-muted)]">Players</span>
        <span className="font-semibold text-[var(--text-primary)]">
          {latest.onlinePlayers}
        </span>
      </div>
    </div>
  );
}
