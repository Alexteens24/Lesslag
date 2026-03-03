import { Hono } from 'hono';
import {
  generatePreset,
  GameProfiles,
  HardwareTiers,
  AggressivenessLevels,
  ServerForks,
} from '@lesslag/shared-rules';
import type { GameProfile, HardwareTier, AggressivenessLevel, ServerFork } from '@lesslag/shared-rules';

const route = new Hono();

const VALID_PROFILES: Set<string> = new Set(GameProfiles);
const VALID_TIERS: Set<string> = new Set(HardwareTiers);
const VALID_LEVELS: Set<string> = new Set(AggressivenessLevels);
const VALID_FORKS: Set<string> = new Set(ServerForks);

/**
 * POST /api/preset
 * Body: { profile, tier, aggressiveness, playerCount? }
 * Returns: PresetProfile
 */
route.post('/preset', async (c) => {
  let body: unknown;
  try {
    body = await c.req.json();
  } catch {
    return c.json({ error: 'Bad Request', message: 'Invalid JSON body' }, 400);
  }

  const input = body as Record<string, unknown>;
  const errors: string[] = [];

  if (!input.profile || !VALID_PROFILES.has(input.profile as string)) {
    errors.push(`profile must be one of: ${[...VALID_PROFILES].join(', ')}`);
  }
  if (!input.tier || !VALID_TIERS.has(input.tier as string)) {
    errors.push(`tier must be one of: ${[...VALID_TIERS].join(', ')}`);
  }
  if (!input.aggressiveness || !VALID_LEVELS.has(input.aggressiveness as string)) {
    errors.push(`aggressiveness must be one of: ${[...VALID_LEVELS].join(', ')}`);
  }
  if (input.playerCount != null && (typeof input.playerCount !== 'number' || input.playerCount < 0)) {
    errors.push('playerCount must be a non-negative number');
  }
  if (input.fork != null && !VALID_FORKS.has(input.fork as string)) {
    errors.push(`fork must be one of: ${[...VALID_FORKS].join(', ')}`);
  }

  if (errors.length > 0) {
    return c.json({ error: 'Validation Error', messages: errors }, 422);
  }

  try {
    const preset = generatePreset(
      input.profile as GameProfile,
      input.tier as HardwareTier,
      input.aggressiveness as AggressivenessLevel,
      input.playerCount as number | undefined,
      input.fork as ServerFork | undefined,
    );
    return c.json(preset);
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    return c.json({ error: 'Preset Generation Error', message: msg }, 500);
  }
});

export { route as presetRoute };
