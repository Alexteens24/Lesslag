import { Hono } from 'hono';
import { RULES_VERSION } from '@lesslag/shared-rules';

const health = new Hono();

health.get('/health', (c) =>
  c.json({
    status: 'ok',
    timestamp: new Date().toISOString(),
    version: '0.1.0',
    rulesVersion: RULES_VERSION,
  })
);

export { health as healthRoute };
