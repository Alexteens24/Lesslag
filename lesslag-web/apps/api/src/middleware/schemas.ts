/**
 * Zod validation schemas for all API request bodies.
 * Used to validate inputs before processing in route handlers.
 */
import { z } from 'zod';

// ── Shared building blocks ─────────────────────────────────────────────────

/** Max allowed payload body size in bytes (200 KB). */
export const MAX_BODY_BYTES = 200 * 1024;

/** Max number of config keys across all files. */
export const MAX_CONFIG_KEYS = 10_000;

/** Max depth of nested config objects. */
export const MAX_CONFIG_DEPTH = 20;

const ServerFork = z.enum(['vanilla', 'spigot', 'paper', 'purpur', 'pufferfish', 'leaf', 'folia', 'luminol']);
const GameProfile = z.enum(['SMP', 'SKYBLOCK', 'MINIGAME', 'CREATIVE']);
const HardwareTier = z.enum(['LOW', 'MID', 'HIGH']);
const Aggressiveness = z.enum(['SAFE', 'BALANCED', 'AGGRESSIVE']);

// ── /api/evaluate ──────────────────────────────────────────────────────────

/**
 * Schema for POST /api/evaluate request body.
 */
export const EvaluateRequest = z.object({
  /** Map of config file name → flat key-value pairs. */
  configs: z
    .record(z.string(), z.record(z.string(), z.unknown()))
    .refine(
      (configs) => {
        let totalKeys = 0;
        for (const map of Object.values(configs)) {
          totalKeys += Object.keys(map).length;
        }
        return totalKeys <= MAX_CONFIG_KEYS;
      },
      { message: `configs must not exceed ${MAX_CONFIG_KEYS} total keys` },
    ),

  /** Plugin names loaded on the server. */
  plugins: z.array(z.string().max(128)).max(500).default([]),

  platform: z.object({
    fork: ServerFork,
    version: z
      .string()
      .regex(/^1\.\d{1,2}(\.\d{1,2})?$/, 'version must match 1.X or 1.X.Y'),
    isPaper: z.boolean().default(false),
    isPurpur: z.boolean().default(false),
    isPufferfish: z.boolean().default(false),
    isLeaf: z.boolean().default(false),
    hasFolia: z.boolean().default(false),
    isLuminol: z.boolean().default(false),
  }),

  profile: GameProfile,
  tier: HardwareTier,
  aggressiveness: Aggressiveness,

  /** Optional hardware profile for performance-aware rules. */
  hardware: z
    .object({
      availableProcessors: z.number().int().min(1).max(256),
      cpuModel: z.string().max(256),
      maxHeapMB: z.number().int().min(256).max(1_048_576),
      gcOverheadPercent: z.number().min(0).max(100),
      averageMspt: z.number().min(0).max(50_000),
    })
    .nullable()
    .default(null),

  /** Expected player count — used for load-modifier logic. */
  playerCount: z.number().int().min(0).max(100_000).optional(),
});

export type EvaluateRequestType = z.infer<typeof EvaluateRequest>;

// ── /api/session ───────────────────────────────────────────────────────────

export const CreateSessionRequest = z.object({
  profile: GameProfile.optional(),
  tier: HardwareTier.optional(),
  aggressiveness: Aggressiveness.optional(),
});

// ── /api/apply-queue (server-side) ────────────────────────────────────────

export const PatchProposalSchema = z.object({
  targetFile: z.string().max(256),
  configKey: z.string().max(256),
  beforeValue: z.string().max(1024).optional(),
  afterValue: z.string().max(1024),
  ruleId: z.string().max(64),
  riskTag: z.enum(['LOW', 'MEDIUM', 'HIGH']),
  applyScope: z.enum(['RECOMMEND', 'LESSLAG_APPLY']),
  rationale: z.string().max(2048),
});

export const ApplyQueueRequest = z.object({
  patches: z.array(PatchProposalSchema).min(1).max(500),
  dryRun: z.boolean().default(false),
});

// ── Utility ────────────────────────────────────────────────────────────────

/**
 * Parse a JSON body with Zod and return { data } or { error, status }.
 * Keeps route handlers clean — one line of validation.
 */
export function parseBody<T extends z.ZodTypeAny>(
  schema: T,
  body: unknown,
):
  | { ok: true; data: z.infer<T> }
  | { ok: false; status: 400 | 422; message: string; issues?: z.ZodIssue[] } {
  const result = schema.safeParse(body);
  if (!result.success) {
    return {
      ok: false,
      status: 422,
      message: 'Validation failed',
      issues: result.error.issues,
    };
  }
  return { ok: true, data: result.data };
}

/**
 * Estimate size of a parsed JSON value in characters (fast heuristic).
 * Used to reject oversized payloads before detailed validation.
 */
export function estimateJsonSize(value: unknown): number {
  return JSON.stringify(value)?.length ?? 0;
}
