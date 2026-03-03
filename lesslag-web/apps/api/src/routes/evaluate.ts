import { Hono } from 'hono';
import { evaluate } from '@lesslag/shared-rules';
import type { EvaluationInput } from '@lesslag/shared-rules';
import { EvaluateRequest, parseBody, MAX_BODY_BYTES, estimateJsonSize } from '../middleware/schemas';

const route = new Hono();

/**
 * POST /api/evaluate
 * Body: EvaluationInput (validated by Zod EvaluateRequest schema)
 * Returns: EvaluationOutput
 */
route.post('/evaluate', async (c) => {
  let body: unknown;
  try {
    body = await c.req.json();
  } catch {
    return c.json({ error: 'Bad Request', message: 'Invalid JSON body' }, 400);
  }

  // ── Size guard ───────────────────────────────────────────
  if (estimateJsonSize(body) > MAX_BODY_BYTES) {
    return c.json(
      { error: 'Payload Too Large', message: `Request body must not exceed ${MAX_BODY_BYTES / 1024} KB` },
      413,
    );
  }

  // ── Zod validation ───────────────────────────────────────
  const parsed = parseBody(EvaluateRequest, body);
  if (!parsed.ok) {
    return c.json(
      {
        error: 'Validation Error',
        message: parsed.message,
        issues: parsed.issues?.map((i) => ({ path: i.path.join('.'), message: i.message })),
      },
      parsed.status,
    );
  }

  // ── Run evaluation ───────────────────────────────────────
  try {
    const result = evaluate(parsed.data as unknown as EvaluationInput);
    return c.json(result);
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    return c.json({ error: 'Evaluation Error', message: msg }, 500);
  }
});

export { route as evaluateRoute };
