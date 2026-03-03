import { Hono } from 'hono';
import type { Env, AppVariables } from '../index';
import { devStoreSet, requireServerAuth } from '../middleware/server-auth';

const route = new Hono<{ Bindings: Env; Variables: AppVariables }>();

const SERVER_TTL_SEC      = 365 * 24 * 60 * 60; // 1 year
const MAX_SESSIONS_SERVER = 20;

// ─── Register ───────────────────────────────────────────────

/**
 * POST /api/servers/register
 *
 * Called once by the plugin on first startup.  Generates a durable server
 * identity (serverId + serverSecret) and persists it in SERVERS KV so all
 * subsequent authenticated API calls can be verified.
 *
 * Body (all optional): { serverName?: string }
 * Returns: { serverId, serverSecret, serverName }
 */
route.post('/servers/register', async (c) => {
  let serverName = 'Minecraft Server';
  try {
    const body = (await c.req.json()) as Record<string, unknown>;
    if (typeof body.serverName === 'string' && body.serverName.trim()) {
      serverName = body.serverName.trim().slice(0, 64);
    }
  } catch { /* body is optional */ }

  const serverId = crypto.randomUUID();
  // 64-char random secret — two UUID v4s concatenated, hyphens removed
  const serverSecret =
    crypto.randomUUID().replace(/-/g, '') + crypto.randomUUID().replace(/-/g, '');

  const meta = JSON.stringify({ serverName, registeredAt: Date.now() });

  if (c.env?.SERVERS) {
    await c.env.SERVERS.put(`server:${serverId}`, serverSecret, {
      expirationTtl: SERVER_TTL_SEC,
    });
    await c.env.SERVERS.put(`servermeta:${serverId}`, meta, {
      expirationTtl: SERVER_TTL_SEC,
    });
  } else {
    // dev / unbound
    devStoreSet(serverId, serverSecret);
  }

  return c.json({ serverId, serverSecret, serverName }, 201);
});

// ─── Metadata ───────────────────────────────────────────────

/**
 * GET /api/servers/:id/info
 * Returns server metadata (name, registeredAt).  Public — no secret required.
 */
route.get('/servers/:id/info', async (c) => {
  const serverId = c.req.param('id');
  let meta: Record<string, unknown> | null = null;

  if (c.env?.SERVERS) {
    const raw = await c.env.SERVERS.get(`servermeta:${serverId}`);
    if (raw) meta = JSON.parse(raw) as Record<string, unknown>;
  }

  if (!meta) {
    return c.json({ error: 'Not Found', message: 'Server not registered' }, 404);
  }

  return c.json(meta);
});

// ─── Sessions list ───────────────────────────────────────────

/**
 * GET /api/servers/:id/sessions
 * Returns up to the last 20 sessions created by this server.
 * Requires X-Server-Id + X-Server-Secret authentication.
 */
route.get('/servers/:id/sessions', requireServerAuth, async (c) => {
  const serverId = c.req.param('id');

  let tokens: string[] = [];
  if (c.env?.SESSIONS) {
    const raw = await c.env.SESSIONS.get(`server-sessions:${serverId}`);
    if (raw) tokens = JSON.parse(raw) as string[];
  }

  // Resolve and return non-expired sessions (latest MAX first)
  const sessions: unknown[] = [];
  for (const token of tokens.slice(-MAX_SESSIONS_SERVER).reverse()) {
    if (!c.env?.SESSIONS) break;
    const raw = await c.env.SESSIONS.get(token);
    if (raw) {
      const data = JSON.parse(raw) as Record<string, unknown>;
      sessions.push({ token, ...data });
    }
  }

  return c.json({ sessions });
});

export { route as serversRoute };
