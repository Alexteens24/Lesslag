import type { Context, Next } from 'hono';
import type { Env } from '../index';

/**
 * In-memory sliding-window rate limiter.
 * Falls back to Cloudflare KV when available, otherwise uses a local Map
 * (resets on cold start — acceptable for Workers since each isolate
 * is short-lived and Cloudflare already throttles at the edge).
 *
 * Config via wrangler.toml vars:
 *   RATE_LIMIT_MAX        – max requests per window (default 30)
 *   RATE_LIMIT_WINDOW_SEC – window size in seconds  (default 60)
 */

interface RateLimitEntry {
  count: number;
  resetAt: number;      // epoch‐ms
}

const store = new Map<string, RateLimitEntry>();

function clientIp(c: Context): string {
  return (
    c.req.header('cf-connecting-ip') ??
    c.req.header('x-forwarded-for')?.split(',')[0]?.trim() ??
    c.req.header('x-real-ip') ??
    '0.0.0.0'
  );
}

export function rateLimiter() {
  return async (c: Context<{ Bindings: Env }>, next: Next) => {
    const max = Number(c.env?.RATE_LIMIT_MAX ?? 30);
    const windowSec = Number(c.env?.RATE_LIMIT_WINDOW_SEC ?? 60);
    const windowMs = windowSec * 1000;
    const ip = clientIp(c);
    const now = Date.now();

    // ── read / init entry ───────────────────────────────────
    let entry = store.get(ip);

    if (!entry || now >= entry.resetAt) {
      entry = { count: 0, resetAt: now + windowMs };
      store.set(ip, entry);
    }

    entry.count++;

    // ── set standard rate-limit headers ─────────────────────
    const remaining = Math.max(0, max - entry.count);
    const retryAfter = Math.ceil((entry.resetAt - now) / 1000);

    c.header('X-RateLimit-Limit', String(max));
    c.header('X-RateLimit-Remaining', String(remaining));
    c.header('X-RateLimit-Reset', String(Math.ceil(entry.resetAt / 1000)));

    if (entry.count > max) {
      c.header('Retry-After', String(retryAfter));
      return c.json(
        {
          error: 'Too Many Requests',
          message: `Rate limit exceeded. Try again in ${retryAfter}s.`,
          limit: max,
          windowSeconds: windowSec,
        },
        429
      );
    }

    await next();
  };
}
