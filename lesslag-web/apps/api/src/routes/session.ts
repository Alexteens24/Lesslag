import { Hono } from 'hono';
import type { Env } from '../index';

const route = new Hono<{ Bindings: Env }>();

/* ── In-memory fallback when KV isn't bound (dev / first deploy) ── */
const memoryStore = new Map<string, { data: string; expiresAt: number }>();

const SESSION_TTL_SEC       = 7 * 24 * 60 * 60; // 7 days
const SERVER_SESSIONS_MAX   = 20;
const SERVER_SESSIONS_TTL   = 30 * 24 * 60 * 60; // 30 days

/* ── helpers ────────────────────────────────────────────── */

function pruneExpired() {
  const now = Date.now();
  for (const [k, v] of memoryStore) {
    if (v.expiresAt < now) memoryStore.delete(k);
  }
}

/**
 * POST /api/sessions
 *
 * Body (all optional except `configs`):
 * {
 *   profile, tier, aggressiveness, playerCount,
 *   plugins, platform, hardware, configs, serverName
 * }
 *
 * Returns: { token, url, expiresAt }
 */
route.post('/sessions', async (c) => {
  let body: unknown;
  try {
    body = await c.req.json();
  } catch {
    return c.json({ error: 'Bad Request', message: 'Invalid JSON body' }, 400);
  }

  const input = body as Record<string, unknown>;

  if (!input.configs || typeof input.configs !== 'object') {
    return c.json(
      { error: 'Validation Error', message: 'configs (object) is required' },
      422,
    );
  }

  const token = crypto.randomUUID();
  const now = Date.now();
  const expiresAt = now + SESSION_TTL_SEC * 1000;

  // Accept optional server identity from plugin-generated sessions
  const serverId = c.req.header('x-server-id') ?? (input.serverId as string | undefined) ?? null;

  const sessionData = {
    profile: input.profile ?? 'SMP',
    tier: input.tier ?? 'MID',
    aggressiveness: input.aggressiveness ?? 'BALANCED',
    playerCount: input.playerCount ?? 20,
    plugins: input.plugins ?? [],
    platform: input.platform ?? {},
    hardware: input.hardware ?? {},
    configs: input.configs,
    serverName: input.serverName ?? 'Minecraft Server',
    serverId,
    diagnostics: input.diagnostics ?? [],
    rulesVersion: input.rulesVersion ?? null,
    createdAt: now,
    expiresAt,
  };

  const json = JSON.stringify(sessionData);

  if (c.env?.SESSIONS) {
    await c.env.SESSIONS.put(token, json, { expirationTtl: SESSION_TTL_SEC });

    // Index this token under the server so GET /api/servers/:id/sessions works
    if (serverId) {
      const indexKey = `server-sessions:${serverId}`;
      const existing = await c.env.SESSIONS.get(indexKey);
      const tokens: string[] = existing ? (JSON.parse(existing) as string[]) : [];
      tokens.push(token);
      const trimmed = tokens.slice(-SERVER_SESSIONS_MAX);
      await c.env.SESSIONS.put(indexKey, JSON.stringify(trimmed), {
        expirationTtl: SERVER_SESSIONS_TTL,
      });
    }
  } else {
    pruneExpired();
    memoryStore.set(token, { data: json, expiresAt });
  }

  const configuredUrl = c.env?.WEB_DASHBOARD_URL?.trim();
  const webUrl =
    configuredUrl && configuredUrl.length > 0
      ? configuredUrl.replace(/\/$/, '')
      : 'https://lesslag-web.vercel.app';

  return c.json(
    {
      token,
      url: `${webUrl}/session/${token}`,
      expiresAt: new Date(expiresAt).toISOString(),
    },
    201,
  );
});

/**
 * GET /api/sessions/:token
 *
 * Returns the stored session payload, or 404 if expired / unknown.
 */
route.get('/sessions/:token', async (c) => {
  const token = c.req.param('token');

  let raw: string | null = null;

  if (c.env?.SESSIONS) {
    raw = await c.env.SESSIONS.get(token);
  } else {
    const entry = memoryStore.get(token);
    if (entry && entry.expiresAt > Date.now()) {
      raw = entry.data;
    }
  }

  if (!raw) {
    return c.json(
      { error: 'Not Found', message: 'Session not found or expired' },
      404,
    );
  }

  return c.json(JSON.parse(raw));
});

export const sessionRoute = route;
