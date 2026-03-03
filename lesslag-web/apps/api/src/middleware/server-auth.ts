import type { Context, Next } from 'hono';
import type { Env, AppVariables } from '../index';

/**
 * In-memory secret store — used when SERVERS KV is not bound (local dev / first deploy).
 * Populated by registerServer during the same runtime lifetime.
 */
const devStore = new Map<string, string>(); // serverId → serverSecret

/** Called by the /servers/register handler to persist credentials in dev mode. */
export function devStoreSet(serverId: string, secret: string): void {
  devStore.set(serverId, secret);
}

/**
 * Hono middleware: validates X-Server-Id + X-Server-Secret headers.
 *
 * On success sets c.var.serverId so downstream handlers can use it without
 * re-reading the header.  On failure returns 401 immediately.
 */
export async function requireServerAuth(
  c: Context<{ Bindings: Env; Variables: AppVariables }>,
  next: Next,
): Promise<Response | void> {
  const serverId = c.req.header('x-server-id');
  const secret   = c.req.header('x-server-secret');

  if (!serverId || !secret) {
    return c.json(
      { error: 'Unauthorized', message: 'Missing X-Server-Id or X-Server-Secret headers' },
      401,
    );
  }

  let stored: string | null = null;
  if (c.env?.SERVERS) {
    stored = await c.env.SERVERS.get(`server:${serverId}`);
  } else {
    stored = devStore.get(serverId) ?? null;
  }

  if (!stored || stored !== secret) {
    return c.json(
      { error: 'Unauthorized', message: 'Invalid server credentials' },
      401,
    );
  }

  c.set('serverId', serverId);
  await next();
}
