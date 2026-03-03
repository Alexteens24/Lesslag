import { Hono } from 'hono';
import type { Env, AppVariables } from '../index';
import { requireServerAuth } from '../middleware/server-auth';

const route = new Hono<{ Bindings: Env; Variables: AppVariables }>();

/** Rolling window: 60 snapshots × 30 s = 30 minutes of history. */
const MAX_SNAPSHOTS = 60;
/** A server is considered "online" if its last heartbeat was < 65 s ago. */
const ONLINE_THRESHOLD_MS = 65_000;
const META_TTL_SEC = 365 * 24 * 60 * 60;

interface HeartbeatSnapshot {
  tps: number;
  tps1m: number;
  mspt: { current: number; min: number; max: number };
  gcOverheadPercent: number;
  heapUsedMB: number;
  heapMaxMB: number;
  onlinePlayers: number;
  timestamp: number;
}

// ─── Heartbeat ──────────────────────────────────────────────

/**
 * POST /api/servers/:id/heartbeat
 *
 * Sent every 30 s by the plugin.  Appends a snapshot to the rolling window
 * (max 60 entries) and updates the last-seen timestamp on the server metadata.
 *
 * Requires X-Server-Id + X-Server-Secret authentication.
 */
route.post('/servers/:id/heartbeat', requireServerAuth, async (c) => {
  const serverId = c.req.param('id');

  let snapshot: HeartbeatSnapshot;
  try {
    snapshot = (await c.req.json()) as HeartbeatSnapshot;
  } catch {
    return c.json({ error: 'Bad Request', message: 'Invalid JSON body' }, 400);
  }

  // Ensure a timestamp is always present
  if (!snapshot.timestamp) snapshot.timestamp = Date.now();

  // ── Read existing window ──
  let metrics: HeartbeatSnapshot[] = [];
  if (c.env?.SERVERS) {
    const raw = await c.env.SERVERS.get(`metrics:${serverId}`);
    if (raw) metrics = JSON.parse(raw) as HeartbeatSnapshot[];
  }

  // ── Append + trim ──
  metrics.push(snapshot);
  if (metrics.length > MAX_SNAPSHOTS) {
    metrics = metrics.slice(metrics.length - MAX_SNAPSHOTS);
  }

  if (c.env?.SERVERS) {
    await c.env.SERVERS.put(`metrics:${serverId}`, JSON.stringify(metrics), {
      expirationTtl: META_TTL_SEC,
    });
    // Update last-seen on the server metadata entry
    const metaRaw = await c.env.SERVERS.get(`servermeta:${serverId}`);
    const meta = metaRaw
      ? (JSON.parse(metaRaw) as Record<string, unknown>)
      : {};
    meta.lastSeen = snapshot.timestamp;
    await c.env.SERVERS.put(`servermeta:${serverId}`, JSON.stringify(meta), {
      expirationTtl: META_TTL_SEC,
    });
  }

  return c.json({ ok: true, snapshotCount: metrics.length });
});

// ─── Metrics read ────────────────────────────────────────────

/**
 * GET /api/servers/:id/metrics
 *
 * Returns the 30-minute rolling window for a server.  Public — no secret
 * needed; the server ID is already an unguessable UUID.
 *
 * Returns:
 *   { metrics: HeartbeatSnapshot[], lastSeen: number | null, isOnline: boolean }
 */
route.get('/servers/:id/metrics', async (c) => {
  const serverId = c.req.param('id');

  let metrics: HeartbeatSnapshot[] = [];
  let lastSeen: number | null = null;

  if (c.env?.SERVERS) {
    const [metricsRaw, metaRaw] = await Promise.all([
      c.env.SERVERS.get(`metrics:${serverId}`),
      c.env.SERVERS.get(`servermeta:${serverId}`),
    ]);
    if (metricsRaw) metrics = JSON.parse(metricsRaw) as HeartbeatSnapshot[];
    if (metaRaw) {
      const meta = JSON.parse(metaRaw) as Record<string, unknown>;
      lastSeen = typeof meta.lastSeen === 'number' ? meta.lastSeen : null;
    }
  }

  const isOnline =
    lastSeen !== null && Date.now() - lastSeen < ONLINE_THRESHOLD_MS;

  return c.json({ metrics, lastSeen, isOnline });
});

export { route as metricsRoute };
