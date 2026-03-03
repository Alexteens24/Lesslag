import { Hono } from 'hono';
import { cors } from 'hono/cors';
import { prettyJSON } from 'hono/pretty-json';
import { rateLimiter } from './middleware/rate-limiter';
import { evaluateRoute } from './routes/evaluate';
import { presetRoute } from './routes/preset';
import { diffRoute } from './routes/diff';
import { healthRoute } from './routes/health';
import { sessionRoute } from './routes/session';
import { serversRoute } from './routes/servers';
import { metricsRoute } from './routes/metrics';
import { applyQueueRoute } from './routes/apply-queue';
import { benchmarksRoute } from './routes/benchmarks';

export interface Env {
  RATE_LIMIT?: KVNamespace;
  SESSIONS?: KVNamespace;
  /** KV namespace storing server credentials, metadata, metrics, and apply-queue. */
  SERVERS?: KVNamespace;
  RATE_LIMIT_MAX?: string;
  RATE_LIMIT_WINDOW_SEC?: string;
  WEB_DASHBOARD_URL?: string;
}

/**
 * Variables set by requireServerAuth middleware on authenticated routes.
 * Routes that use server auth declare: Hono<{ Bindings: Env; Variables: AppVariables }>
 */
export type AppVariables = {
  serverId: string;
};

const app = new Hono<{ Bindings: Env }>();

// ── Global middleware ──────────────────────────────────────
app.use('*', cors({
  origin: ['https://lesslag-web.vercel.app', 'https://lesslag-api.daucatmoitu.workers.dev', 'http://localhost:3000'],
  allowMethods: ['GET', 'POST', 'OPTIONS'],
  allowHeaders: ['Content-Type', 'X-API-Key'],
  maxAge: 86400,
}));

app.use('*', prettyJSON());

// Rate limit all routes
app.use('/api/*', rateLimiter());

// ── Routes ─────────────────────────────────────────────────
app.route('/api', healthRoute);
app.route('/api', evaluateRoute);
app.route('/api', presetRoute);
app.route('/api', diffRoute);
app.route('/api', sessionRoute);
app.route('/api', serversRoute);
app.route('/api', metricsRoute);
app.route('/api', applyQueueRoute);
app.route('/api', benchmarksRoute);

// ── Root ───────────────────────────────────────────────────
app.get('/', (c) =>
  c.json({
    name: 'LessLag API',
    version: '0.1.0',
    docs: '/api/health',
    endpoints: [
      'GET  /api/health',
      'POST /api/evaluate',
      'POST /api/preset',
      'POST /api/diff',
      'POST /api/sessions',
      'GET  /api/sessions/:token',
      'POST /api/servers/register',
      'GET  /api/servers/:id/info',
      'GET  /api/servers/:id/sessions',
      'POST /api/servers/:id/heartbeat',
      'GET  /api/servers/:id/metrics',
      'POST /api/servers/:id/apply-queue',
      'GET  /api/servers/:id/apply-queue',
      'GET  /api/servers/:id/apply-queue/:patchId',
      'DELETE /api/servers/:id/apply-queue/:patchId',
    ],
  })
);

// ── 404 ────────────────────────────────────────────────────
app.notFound((c) =>
  c.json({ error: 'Not Found', message: `Route ${c.req.method} ${c.req.path} not found` }, 404)
);

// ── Error handler ──────────────────────────────────────────
app.onError((err, c) => {
  console.error('Unhandled error:', err);
  return c.json(
    { error: 'Internal Server Error', message: err.message },
    500
  );
});

export default app;
