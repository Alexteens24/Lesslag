import { Hono } from 'hono';
import type { Env, AppVariables } from '../index';
import { requireServerAuth } from '../middleware/server-auth';

const route = new Hono<{ Bindings: Env; Variables: AppVariables }>();

const QUEUE_TTL_SEC  = 24 * 60 * 60; // 1 day
const MAX_QUEUE_SIZE = 5;
const DEFAULT_EXPIRES_HOURS = 24;

// ── Types ────────────────────────────────────────────────────

interface PatchProposalLike {
  targetFile:  string;
  configKey:   string;
  beforeValue: string;
  afterValue:  string;
  riskTag?:    string;
  rationale?:  string;
}

type PatchStatus = 'QUEUED' | 'APPLIED' | 'REJECTED' | 'EXPIRED';

interface ApplyRequest {
  patchId:      string;
  proposals:    PatchProposalLike[];
  queuedAt:     number;
  queuedBy:     string;
  expiresAt:    number;
  status:       PatchStatus;
  msptBefore?:  number;
  msptAfter?:   number;
  confirmedAt?: number;
}

// ── Helpers ──────────────────────────────────────────────────

async function readQueue(
  c: { env?: { SERVERS?: KVNamespace } },
  serverId: string,
): Promise<ApplyRequest[]> {
  if (!c.env?.SERVERS) return [];
  const raw = await c.env.SERVERS.get(`applyqueue:${serverId}`);
  return raw ? (JSON.parse(raw) as ApplyRequest[]) : [];
}

async function writeQueue(
  c: { env?: { SERVERS?: KVNamespace } },
  serverId: string,
  queue: ApplyRequest[],
): Promise<void> {
  if (!c.env?.SERVERS) return;
  await c.env.SERVERS.put(`applyqueue:${serverId}`, JSON.stringify(queue), {
    expirationTtl: QUEUE_TTL_SEC,
  });
}

// ── POST — web client queues a patch ──────────────────────────

/**
 * POST /api/servers/:id/apply-queue
 *
 * Enqueues a set of PatchProposals for the plugin to pick up.
 * Accessible without server auth — the server UUID is already unguessable;
 * the authenticated read/confirm operations remain server-auth-only.
 *
 * Body: {
 *   proposals:       PatchProposal[],
 *   queuedBy?:       string,        // label shown in plugin console, default 'web-dashboard'
 *   expiresInHours?: number,        // default 24
 * }
 * Returns: { patchId, status: 'QUEUED', queuedAt, expiresAt }
 */
route.post('/servers/:id/apply-queue', async (c) => {
  const serverId = c.req.param('id');

  let body: Record<string, unknown>;
  try {
    body = (await c.req.json()) as Record<string, unknown>;
  } catch {
    return c.json({ error: 'Bad Request', message: 'Invalid JSON body' }, 400);
  }

  if (!Array.isArray(body.proposals) || body.proposals.length === 0) {
    return c.json(
      { error: 'Validation Error', message: 'proposals must be a non-empty array' },
      422,
    );
  }

  const queue = await readQueue(c, serverId);

  // Prune expired entries
  const now = Date.now();
  const live = queue.filter(
    (r) => r.status === 'QUEUED' && r.expiresAt > now,
  );

  if (live.length >= MAX_QUEUE_SIZE) {
    return c.json(
      {
        error: 'Conflict',
        message: `Apply queue already has ${live.length} pending patches (max ${MAX_QUEUE_SIZE}). Wait for the plugin to process them first.`,
      },
      409,
    );
  }

  const expiresInHours =
    typeof body.expiresInHours === 'number' && body.expiresInHours > 0
      ? Math.min(body.expiresInHours, 168) // cap at 1 week
      : DEFAULT_EXPIRES_HOURS;

  const patch: ApplyRequest = {
    patchId:   crypto.randomUUID(),
    proposals: body.proposals as PatchProposalLike[],
    queuedAt:  now,
    queuedBy:  typeof body.queuedBy === 'string' ? body.queuedBy : 'web-dashboard',
    expiresAt: now + expiresInHours * 3_600_000,
    status:    'QUEUED',
  };

  await writeQueue(c, serverId, [...live, patch]);

  return c.json(
    {
      patchId:   patch.patchId,
      status:    'QUEUED',
      queuedAt:  new Date(patch.queuedAt).toISOString(),
      expiresAt: new Date(patch.expiresAt).toISOString(),
    },
    201,
  );
});

// ── GET — plugin polls for pending patches ────────────────────

/**
 * GET /api/servers/:id/apply-queue
 *
 * Returns all non-expired QUEUED patches.  The plugin calls this on its
 * configured polling interval and processes each entry.
 * Requires X-Server-Id + X-Server-Secret authentication.
 */
route.get('/servers/:id/apply-queue', requireServerAuth, async (c) => {
  const serverId = c.req.param('id');
  const now = Date.now();

  const queue = await readQueue(c, serverId);

  // Mark expired entries in-place if any exist
  let dirty = false;
  const updated = queue.map((r) => {
    if (r.status === 'QUEUED' && r.expiresAt <= now) {
      dirty = true;
      return { ...r, status: 'EXPIRED' as PatchStatus };
    }
    return r;
  });
  if (dirty) await writeQueue(c, serverId, updated);

  const pending = updated.filter((r) => r.status === 'QUEUED');
  return c.json({ patches: pending });
});

// ── DELETE — plugin confirms or rejects a patch ───────────────

/**
 * DELETE /api/servers/:id/apply-queue/:patchId
 *
 * Called by the plugin after it processes a patch.
 * Body: { status: 'APPLIED' | 'REJECTED', msptBefore?: number, msptAfter?: number }
 * Requires X-Server-Id + X-Server-Secret authentication.
 */
route.delete('/servers/:id/apply-queue/:patchId', requireServerAuth, async (c) => {
  const serverId = c.req.param('id');
  const patchId  = c.req.param('patchId');

  let body: Record<string, unknown> = {};
  try {
    body = (await c.req.json()) as Record<string, unknown>;
  } catch { /* body optional */ }

  const finalStatus = body.status === 'REJECTED' ? 'REJECTED' : 'APPLIED';

  const queue = await readQueue(c, serverId);
  const idx = queue.findIndex((r) => r.patchId === patchId);

  if (idx === -1) {
    return c.json({ error: 'Not Found', message: 'Patch not found in queue' }, 404);
  }

  queue[idx] = {
    ...queue[idx],
    status:      finalStatus as PatchStatus,
    confirmedAt: Date.now(),
    msptBefore:  typeof body.msptBefore === 'number' ? body.msptBefore : undefined,
    msptAfter:   typeof body.msptAfter  === 'number' ? body.msptAfter  : undefined,
  };

  await writeQueue(c, serverId, queue);

  return c.json({ patchId, status: finalStatus });
});

// ── GET single patch status — web client polls for result ─────

/**
 * GET /api/servers/:id/apply-queue/:patchId
 * Returns the current status of a single patch request.
 * Public — identified by server UUID + patch UUID, both unguessable.
 */
route.get('/servers/:id/apply-queue/:patchId', async (c) => {
  const serverId = c.req.param('id');
  const patchId  = c.req.param('patchId');

  const queue = await readQueue(c, serverId);
  const patch = queue.find((r) => r.patchId === patchId);

  if (!patch) {
    return c.json({ error: 'Not Found', message: 'Patch not found' }, 404);
  }

  const { patchId: id, status, queuedAt, expiresAt, confirmedAt, msptBefore, msptAfter } = patch;
  return c.json({ patchId: id, status, queuedAt, expiresAt, confirmedAt, msptBefore, msptAfter });
});

export { route as applyQueueRoute };
