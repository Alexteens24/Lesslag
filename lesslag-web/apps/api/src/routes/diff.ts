import { Hono } from 'hono';
import { generateDiffs, groupDiffsByFile, renderFullDiff } from '@lesslag/shared-rules';
import type { PatchProposal } from '@lesslag/shared-rules';

const route = new Hono();

/**
 * POST /api/diff
 * Body: { proposals: PatchProposal[], format?: 'raw' | 'grouped' | 'text' }
 * Returns: diffs in the requested format
 */
route.post('/diff', async (c) => {
  let body: unknown;
  try {
    body = await c.req.json();
  } catch {
    return c.json({ error: 'Bad Request', message: 'Invalid JSON body' }, 400);
  }

  const input = body as Record<string, unknown>;
  const errors: string[] = [];

  if (!Array.isArray(input.proposals)) {
    errors.push('proposals must be an array of PatchProposal objects');
  } else if (input.proposals.length === 0) {
    errors.push('proposals array must not be empty');
  }

  const format = (input.format as string) ?? 'raw';
  if (!['raw', 'grouped', 'text'].includes(format)) {
    errors.push('format must be one of: raw, grouped, text');
  }

  if (errors.length > 0) {
    return c.json({ error: 'Validation Error', messages: errors }, 422);
  }

  try {
    const diffs = generateDiffs(input.proposals as PatchProposal[]);

    switch (format) {
      case 'grouped':
        return c.json({ diffs: groupDiffsByFile(diffs) });
      case 'text':
        return c.json({ text: renderFullDiff(input.proposals as PatchProposal[]) });
      default:
        return c.json({ diffs });
    }
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    return c.json({ error: 'Diff Generation Error', message: msg }, 500);
  }
});

export { route as diffRoute };
