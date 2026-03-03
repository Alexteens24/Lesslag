import { describe, it, expect, beforeEach } from 'vitest';
import app from '../src/index';

describe('Rate Limiter', () => {
  // The in-memory store persists across tests within the same vitest worker,
  // so we test against a burst that exceeds the default limit (30).
  // We use a custom header to simulate different IPs.

  it('returns rate-limit headers on every response', async () => {
    const res = await app.request('/api/health', {
      headers: { 'x-forwarded-for': '10.0.0.1' },
    });
    expect(res.status).toBe(200);
    expect(res.headers.get('X-RateLimit-Limit')).toBe('30');
    expect(res.headers.has('X-RateLimit-Remaining')).toBe(true);
    expect(res.headers.has('X-RateLimit-Reset')).toBe(true);
  });

  it('returns 429 after exceeding the limit', async () => {
    const ip = '10.99.99.99'; // unique IP to avoid interference
    // fire 31 requests (limit is 30)
    const results: Response[] = [];
    for (let i = 0; i < 31; i++) {
      results.push(
        await app.request('/api/health', {
          headers: { 'x-forwarded-for': ip },
        })
      );
    }

    // first 30 should succeed
    for (let i = 0; i < 30; i++) {
      expect(results[i].status).toBe(200);
    }

    // 31st should be rate-limited
    expect(results[30].status).toBe(429);
    const body = await results[30].json();
    expect(body.error).toBe('Too Many Requests');
    expect(results[30].headers.has('Retry-After')).toBe(true);
  });

  it('different IPs have independent limits', async () => {
    const resA = await app.request('/api/health', {
      headers: { 'x-forwarded-for': '192.168.1.1' },
    });
    const resB = await app.request('/api/health', {
      headers: { 'x-forwarded-for': '192.168.1.2' },
    });
    expect(resA.status).toBe(200);
    expect(resB.status).toBe(200);
    // Both should have full remaining (minus 1)
    expect(Number(resA.headers.get('X-RateLimit-Remaining'))).toBeGreaterThanOrEqual(28);
    expect(Number(resB.headers.get('X-RateLimit-Remaining'))).toBeGreaterThanOrEqual(28);
  });
});
