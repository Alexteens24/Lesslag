import { describe, it, expect } from 'vitest';
import app from '../src/index';

// Minimal valid EvaluationInput
const VALID_EVAL_INPUT = {
  platform: { fork: 'paper', version: '1.20.4', isPaper: true },
  configs: {
    'server.properties': { 'view-distance': '10', 'simulation-distance': '8' },
    'spigot.yml': { 'mob-spawn-range': '8' },
  },
  plugins: [],
  hardware: {
    availableProcessors: 4,
    cpuModel: 'Test CPU',
    maxHeapMB: 4096,
    gcOverheadPercent: 5,
    averageMspt: 35,
  },
  profile: 'SMP',
  tier: 'MID',
  aggressiveness: 'BALANCED',
};

// ─── Health ────────────────────────────────────────────────
describe('GET /api/health', () => {
  it('returns status ok', async () => {
    const res = await app.request('/api/health');
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.status).toBe('ok');
    expect(body).toHaveProperty('timestamp');
    expect(body).toHaveProperty('version');
  });
});

// ─── Root ──────────────────────────────────────────────────
describe('GET /', () => {
  it('returns API info', async () => {
    const res = await app.request('/');
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.name).toBe('LessLag API');
    expect(body.endpoints).toBeInstanceOf(Array);
  });
});

// ─── Evaluate ──────────────────────────────────────────────
describe('POST /api/evaluate', () => {
  it('returns evaluation output for valid input', async () => {
    const res = await app.request('/api/evaluate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(VALID_EVAL_INPUT),
    });
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body).toHaveProperty('results');
    expect(body).toHaveProperty('proposals');
    expect(body).toHaveProperty('summary');
    expect(Array.isArray(body.results)).toBe(true);
  });

  it('rejects invalid JSON', async () => {
    const res = await app.request('/api/evaluate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: 'not json',
    });
    expect(res.status).toBe(400);
  });

  it('rejects missing required fields', async () => {
    const res = await app.request('/api/evaluate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ profile: 'INVALID' }),
    });
    expect(res.status).toBe(422);
    const body = await res.json();
    // Zod validation returns { error, message, issues[] }
    expect(body.error).toBe('Validation Error');
    expect(body.issues).toBeInstanceOf(Array);
    expect(body.issues.length).toBeGreaterThan(0);
  });

  it('rejects invalid profile value', async () => {
    const res = await app.request('/api/evaluate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ...VALID_EVAL_INPUT, profile: 'NOPE' }),
    });
    expect(res.status).toBe(422);
  });

  it('rejects oversized payload (> 200 KB)', async () => {
    const bigConfigs: Record<string, Record<string, string>> = {};
    for (let i = 0; i < 500; i++) {
      bigConfigs[`file-${i}.yml`] = Object.fromEntries(
        Array.from({ length: 50 }, (_, j) => [`key-${j}`, 'x'.repeat(20)]),
      );
    }
    const res = await app.request('/api/evaluate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ...VALID_EVAL_INPUT, configs: bigConfigs }),
    });
    expect(res.status).toBe(413);
  });

  it('issues array contains path and message fields', async () => {
    const res = await app.request('/api/evaluate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ profile: 'SMP' }), // missing required fields
    });
    expect(res.status).toBe(422);
    const body = await res.json();
    expect(Array.isArray(body.issues)).toBe(true);
    for (const issue of body.issues) {
      expect(typeof issue.message).toBe('string');
    }
  });
});

// ─── Preset ────────────────────────────────────────────────
describe('POST /api/preset', () => {
  it('generates a preset for valid input', async () => {
    const res = await app.request('/api/preset', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        profile: 'SMP',
        tier: 'MID',
        aggressiveness: 'BALANCED',
      }),
    });
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body).toHaveProperty('settings');
    expect(body).toHaveProperty('description');
  });

  it('accepts optional playerCount', async () => {
    const res = await app.request('/api/preset', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        profile: 'MINIGAME',
        tier: 'HIGH',
        aggressiveness: 'SAFE',
        playerCount: 150,
      }),
    });
    expect(res.status).toBe(200);
  });

  it('rejects invalid tier', async () => {
    const res = await app.request('/api/preset', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        profile: 'SMP',
        tier: 'MEGA',
        aggressiveness: 'BALANCED',
      }),
    });
    expect(res.status).toBe(422);
  });
});

// ─── Diff ──────────────────────────────────────────────────
describe('POST /api/diff', () => {
  const sampleProposals = [
    {
      file: 'spigot.yml',
      key: 'mob-spawn-range',
      oldValue: '8',
      newValue: '6',
      reason: 'Reduce mob load',
      scope: 'GLOBAL',
      ruleId: 'test-rule',
    },
  ];

  it('returns raw diffs by default', async () => {
    const res = await app.request('/api/diff', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ proposals: sampleProposals }),
    });
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body).toHaveProperty('diffs');
    expect(Array.isArray(body.diffs)).toBe(true);
  });

  it('returns grouped diffs', async () => {
    const res = await app.request('/api/diff', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ proposals: sampleProposals, format: 'grouped' }),
    });
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body).toHaveProperty('diffs');
  });

  it('returns text diff', async () => {
    const res = await app.request('/api/diff', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ proposals: sampleProposals, format: 'text' }),
    });
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body).toHaveProperty('text');
    expect(typeof body.text).toBe('string');
  });

  it('rejects empty proposals array', async () => {
    const res = await app.request('/api/diff', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ proposals: [] }),
    });
    expect(res.status).toBe(422);
  });
});

// ─── Sessions ──────────────────────────────────────────────
describe('Session flow', () => {
  it('creates a session link with token', async () => {
    const res = await app.request('/api/sessions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        profile: 'SMP',
        tier: 'MID',
        aggressiveness: 'BALANCED',
        configs: { 'server.properties': { 'view-distance': '8' } },
      }),
    });

    expect(res.status).toBe(201);
    const body = await res.json();
    expect(typeof body.token).toBe('string');
    expect(typeof body.url).toBe('string');
    expect(body.url).toContain(`/session/${body.token}`);
    expect(typeof body.expiresAt).toBe('string');
  });

  it('rejects sessions without configs object', async () => {
    const res = await app.request('/api/sessions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ profile: 'SMP' }),
    });

    expect(res.status).toBe(422);
  });

  it('loads a previously created session by token', async () => {
    const createRes = await app.request('/api/sessions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        profile: 'MINIGAME',
        tier: 'HIGH',
        aggressiveness: 'SAFE',
        configs: { 'spigot.yml': { 'mob-spawn-range': '6' } },
      }),
    });

    const createBody = await createRes.json();
    const token = createBody.token as string;
    expect(typeof token).toBe('string');

    const getRes = await app.request(`/api/sessions/${token}`);
    expect(getRes.status).toBe(200);
    const session = await getRes.json();
    expect(session.profile).toBe('MINIGAME');
    expect(session.tier).toBe('HIGH');
    expect(session.configs['spigot.yml']['mob-spawn-range']).toBe('6');
  });
});

// ─── 404 ───────────────────────────────────────────────────
describe('Unknown routes', () => {
  it('returns 404 for unknown paths', async () => {
    const res = await app.request('/api/nonexistent');
    expect(res.status).toBe(404);
    const body = await res.json();
    expect(body.error).toBe('Not Found');
  });
});
