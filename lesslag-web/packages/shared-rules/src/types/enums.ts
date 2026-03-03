// ─── Game Profile ───────────────────────────────────────────
export const GameProfiles = ['SMP', 'SKYBLOCK', 'MINIGAME', 'CREATIVE'] as const;
export type GameProfile = (typeof GameProfiles)[number];

export const GameProfileMeta: Record<GameProfile, { displayName: string; description: string }> = {
  SMP: { displayName: 'Survival Multiplayer', description: 'General survival gameplay with varied activities' },
  SKYBLOCK: { displayName: 'Skyblock', description: 'Island-based survival with heavy farming/automation' },
  MINIGAME: { displayName: 'Minigame', description: 'Fast player rotation, short-lived worlds' },
  CREATIVE: { displayName: 'Creative', description: 'Building-focused with large render requirements' },
};

// ─── Hardware Tier ──────────────────────────────────────────
export const HardwareTiers = ['LOW', 'MID', 'HIGH'] as const;
export type HardwareTier = (typeof HardwareTiers)[number];

export const HardwareTierMeta: Record<HardwareTier, { displayName: string; description: string }> = {
  LOW: { displayName: 'Entry VPS', description: '2 vCPU and 2-4 GB RAM, suitable for small communities' },
  MID: { displayName: 'Standard VPS', description: '4 vCPU and 6-8 GB RAM, recommended for most servers' },
  HIGH: { displayName: 'Performance VPS / Dedicated', description: '6+ vCPU and 10+ GB RAM for larger workloads' },
};

// ─── Aggressiveness Level ───────────────────────────────────
export const AggressivenessLevels = ['SAFE', 'BALANCED', 'AGGRESSIVE'] as const;
export type AggressivenessLevel = (typeof AggressivenessLevels)[number];

export const AggressivenessLevelMeta: Record<AggressivenessLevel, { displayName: string; description: string }> = {
  SAFE: { displayName: 'Safe', description: 'Minimal gameplay impact, conservative settings' },
  BALANCED: { displayName: 'Balanced', description: 'Good balance between performance and gameplay' },
  AGGRESSIVE: { displayName: 'Aggressive', description: 'Maximum performance with explicit tradeoffs' },
};

// ─── Severity ───────────────────────────────────────────────
export const Severities = ['INFO', 'WARNING', 'CRITICAL'] as const;
export type Severity = (typeof Severities)[number];

// ─── Risk Tag ───────────────────────────────────────────────
export const RiskTags = ['LOW', 'MEDIUM', 'HIGH'] as const;
export type RiskTag = (typeof RiskTags)[number];

export const RiskTagMeta: Record<RiskTag, { description: string }> = {
  LOW: { description: 'Low risk — safe to apply' },
  MEDIUM: { description: 'Medium risk — may affect gameplay' },
  HIGH: { description: 'High risk — significant tradeoffs, review carefully' },
};

// ─── Apply Scope ────────────────────────────────────────────
export const ApplyScopes = ['RECOMMEND', 'LESSLAG_APPLY'] as const;
export type ApplyScope = (typeof ApplyScopes)[number];

// ─── Session Status ─────────────────────────────────────────
export const SessionStatuses = [
  'DISCOVERY', 'PROFILING', 'REVIEW', 'CONFIRMED',
  'APPLIED', 'ABORTED', 'FAILED', 'ROLLED_BACK',
] as const;
export type SessionStatus = (typeof SessionStatuses)[number];

export const SessionStatusMeta: Record<SessionStatus, { description: string }> = {
  DISCOVERY: { description: 'Running server discovery...' },
  PROFILING: { description: 'Awaiting profile/tier selection' },
  REVIEW: { description: 'Reviewing recommendations' },
  CONFIRMED: { description: 'Session confirmed, applying changes' },
  APPLIED: { description: 'Changes applied successfully' },
  ABORTED: { description: 'Session aborted by user' },
  FAILED: { description: 'Session failed during apply' },
  ROLLED_BACK: { description: 'Changes rolled back' },
};

// ─── Server Fork ────────────────────────────────────────────
export const ServerForks = ['vanilla', 'spigot', 'paper', 'purpur', 'pufferfish', 'leaf'] as const;
export type ServerFork = (typeof ServerForks)[number];

// ─── Target Files ───────────────────────────────────────────
export const TargetFiles = [
  'server.properties',
  'bukkit.yml',
  'spigot.yml',
  'config/paper-global.yml',
  'config/paper-world-defaults.yml',
  'paper-world.yml',
  'purpur.yml',
  'pufferfish.yml',
  'leaves.yml',
  'config.yml',
] as const;
export type TargetFile = (typeof TargetFiles)[number];
