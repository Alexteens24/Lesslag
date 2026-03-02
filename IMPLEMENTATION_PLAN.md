# LessLag Web Product — Implementation Plan

> Generated: 2026-03-02  
> Scope: MVP + v1.5 + v2 (full feature set)  
> Delivery model: parallel tracks with milestone gates

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Phase 0 — Product Baseline (Day 0-1)](#2-phase-0--product-baseline-day-0-1)
3. [Phase 1 — Core Platform Setup (Day 1-3)](#3-phase-1--core-platform-setup-day-1-3)
4. [Phase 2 — Shared Rules Engine (Day 2-6)](#4-phase-2--shared-rules-engine-day-2-6)
5. [Phase 3 — MVP Features (Day 4-10)](#5-phase-3--mvp-features-day-4-10)
6. [Phase 4 — v1.5 Features (Day 8-14)](#6-phase-4--v15-features-day-8-14)
7. [Phase 5 — v2 Power Features (Day 12-20)](#7-phase-5--v2-power-features-day-12-20)
8. [Phase 6 — Data, Security, Reliability (parallel)](#8-phase-6--data-security-reliability-parallel)
9. [Phase 7 — Testing Strategy (parallel)](#9-phase-7--testing-strategy-parallel)
10. [Phase 8 — Release Plan](#10-phase-8--release-plan)
11. [Appendix — File Map & Reference](#11-appendix)

---

## 1. Architecture Overview

### 1.1 Current Plugin Architecture (source of truth)

The existing plugin has a rich, production-tested rules system that must be the canonical source for the web product:

| Component | Location | What it provides |
|-----------|----------|-----------------|
| **3-axis preset matrix** | `setup/preset/PresetMatrix.java` | `GameProfile × HardwareTier × AggressivenessLevel` → deterministic config values |
| **Rule engine pipeline** | `setup/rules/RuleEngine.java` | 5 prioritized rule sets: Safety(10) → Consistency(20) → Conflict(30) → ForkSpecific(40) → PerformanceTuning(50) |
| **Rule results** | `setup/rules/RuleResult.java` | `ruleId, severity, confidence, why, impact, tradeoff, manualSteps, impactedKeys` |
| **Patch proposals** | `setup/rules/PatchProposal.java` | `targetFile, configKey, beforeValue, afterValue, riskTag, applyScope, rationale` |
| **Config adapter** | `setup/detect/ConfigAdapter.java` | Reads 10+ config files: `server.properties`, `bukkit.yml`, `spigot.yml`, `paper-global.yml`, `paper-world-defaults.yml`, `purpur.yml`, `pufferfish.yml`, `leaves.yml` |
| **Preset profile** | `setup/preset/PresetProfile.java` | Immutable `Map<String, String>` of all recommended values |
| **Model enums** | `setup/model/*.java` | `GameProfile`, `HardwareTier`, `AggressivenessLevel`, `Severity`, `RiskTag`, `ApplyScope`, `SessionStatus` |

### 1.2 Target Web Architecture

```
┌─────────────────────────────────────────────────────┐
│                   Monorepo Root                      │
├──────────┬──────────┬───────────────┬───────────────┤
│  web/    │  api/    │ shared-rules/ │    infra/     │
│ Next.js  │ Hono.js  │  TypeScript   │  Terraform/   │
│  React   │  Workers │  Rule engine  │  Wrangler     │
│  Tailwind│  D1/KV   │  Portable     │  CI/CD        │
└──────────┴──────────┴───────────────┴───────────────┘
         ▲               ▲                ▲
         │               │                │
         └───────────────┴────────────────┘
              All import shared-rules
```

**Recommended stack: Cloudflare Pages + Workers + D1/KV/R2**

Rationale:
- Zero cold-start edge compute (Workers)
- Generous free tier (100K requests/day, 5GB D1, 10GB R2)
- Built-in preview deployments per PR
- No Docker/server management overhead
- Global edge = fast YAML exports everywhere
- Easy migration to self-host later (Hono runs on Node/Bun/Deno too)

---

## 2. Phase 0 — Product Baseline (Day 0-1)

### 2.1 Feature Matrix (frozen)

| ID | Feature | Phase | Priority | Acceptance Criteria |
|----|---------|-------|----------|-------------------|
| F01 | Interactive config editor (sliders/toggles/forms) | MVP | P0 | User can modify any LessLag config key via UI controls; changes reflect in real-time YAML preview |
| F02 | Preset selector (SMP/Skyblock/Minigame/Creative) | MVP | P0 | All 4 GameProfile × 3 HardwareTier × 3 Aggressiveness = 36 presets generate correct configs matching `PresetMatrix.java` output |
| F03 | Diff viewer (before/after per file + key-level) | MVP | P0 | Side-by-side or inline diff of every changed key, grouped by target file; unchanged keys hidden by default |
| F04 | "Why this change?" panel | MVP | P0 | Every `PatchProposal` links to its `RuleResult.why`, `impact`, `tradeoff`; risk tag badge visible |
| F05 | Export package (zip + per-file copy) | MVP | P0 | Zip contains correct directory structure; individual file copy-to-clipboard works; re-import round-trips clean |
| F06 | Import existing config | MVP | P0 | Accepts paste or file upload of any of the 10 config files; parses and populates editor state; runs rule analysis |
| F07 | Hardware profile wizard | v1.5 | P1 | Inputs: CPU model/cores, RAM, expected players, disk type; outputs: recommended HardwareTier + load-modified preset |
| F08 | Plugin conflict detector | v1.5 | P1 | User enters plugin list; system returns conflicts from `ConflictRules` (ClearLag, Pufferfish DAB, MobFarmManager, stackers, silktouch spawners) |
| F09 | Rollback snapshots/history | v1.5 | P1 | Session state persisted; user can revert to any previous state; max 20 snapshots per session |
| F10 | Shareable links | v1.5 | P1 | URL contains signed session token; recipient sees read-only config view; link expires after 30 days |
| F11 | MSPT/TPS impact estimator | v2 | P2 | Given hardware + config + player count, estimates MSPT range with confidence band; model calibrated from plugin telemetry |
| F12 | A/B config compare | v2 | P2 | Two config sets side-by-side with diff highlighting and estimated performance delta |
| F13 | Guided onboarding wizard | v2 | P2 | Step-by-step flow: server type → hardware → current problems → recommended preset → review → export |
| F14 | Optional telemetry pipeline | v2 | P2 | Privacy policy, opt-in only, anonymized server profiles for model calibration |
| F15 | One-click apply API | v2 | P2 | Plugin exposes HTTP endpoint; web sends signed config patch; plugin validates + applies + reports result |

### 2.2 Canonical Rule Source

Extract from the Java codebase into `shared-rules/` as the single source of truth:

```
shared-rules/
├── src/
│   ├── schema/
│   │   ├── config-keys.ts          # Every config key, its file, type, range, default
│   │   ├── presets.ts              # 36-preset matrix (GameProfile × HardwareTier × Aggressiveness)
│   │   ├── rules.ts                # All rule definitions with metadata
│   │   └── conflicts.ts           # Plugin conflict database
│   ├── engine/
│   │   ├── evaluator.ts           # Deterministic rule evaluator
│   │   ├── preset-generator.ts    # PresetMatrix port
│   │   ├── diff-generator.ts      # Produces PatchProposal[]
│   │   └── impact-estimator.ts    # v2: MSPT/TPS model
│   ├── types/
│   │   ├── config.ts              # Config file types
│   │   ├── hardware.ts            # HardwareProfile type
│   │   ├── rule-result.ts         # RuleResult, PatchProposal, etc.
│   │   └── enums.ts               # GameProfile, HardwareTier, etc.
│   └── index.ts                   # Public API barrel export
├── tests/
│   ├── evaluator.test.ts
│   ├── preset-generator.test.ts
│   ├── diff-generator.test.ts
│   └── golden-files/             # Expected outputs per preset combo
├── package.json
└── tsconfig.json
```

### 2.3 Quality Bars

| Metric | Target | Measurement |
|--------|--------|-------------|
| Rule engine correctness | 100% parity with Java `PresetMatrix` + `RuleEngine` | Golden-file comparison across all 36 preset combos |
| Test coverage (shared-rules) | ≥ 95% line coverage | Vitest + c8 |
| Test coverage (web + api) | ≥ 80% line coverage | Vitest + Playwright |
| YAML export correctness | Round-trip clean (export → import → export = identical) | Automated E2E test |
| p95 page load | < 2s on 3G | Lighthouse CI in preview deploys |
| p95 API response (rule eval) | < 200ms | Cloudflare analytics + synthetic probes |
| Export success rate | > 99.9% | Error tracking (Sentry) |
| Accessibility | WCAG 2.1 AA | axe-core in CI |

---

## 3. Phase 1 — Core Platform Setup (Day 1-3)

### 3.1 Monorepo Setup

```bash
# From project root
mkdir -p lesslag-web && cd lesslag-web
pnpm init
```

**Directory structure:**

```
lesslag-web/                     # monorepo root
├── pnpm-workspace.yaml
├── turbo.json                   # Turborepo for build orchestration
├── .github/
│   └── workflows/
│       ├── ci.yml               # Lint + test + build on every PR
│       ├── preview.yml          # Deploy preview per PR
│       └── release.yml          # Production deploy on main merge
├── packages/
│   └── shared-rules/            # Phase 2 — portable rule engine
├── apps/
│   ├── web/                     # Next.js 15 frontend
│   │   ├── app/                 # App Router pages
│   │   ├── components/          # React components
│   │   ├── lib/                 # Client-side utilities
│   │   └── public/              # Static assets
│   └── api/                     # Hono.js API (Cloudflare Workers)
│       ├── src/
│       │   ├── routes/          # API route handlers
│       │   ├── middleware/      # Auth, rate-limit, validation
│       │   └── index.ts         # Worker entry
│       └── wrangler.toml        # Cloudflare config
└── infra/
    ├── terraform/               # Optional: if self-hosting
    └── scripts/                 # Deploy helpers
```

### 3.2 Tooling Choices

| Concern | Choice | Rationale |
|---------|--------|-----------|
| Package manager | pnpm 9 | Fast, strict, workspace-native |
| Build orchestrator | Turborepo | Caches tasks, parallelizes, monorepo-native |
| Frontend | Next.js 15 (App Router) | SSR for SEO, RSC for shared-rules import, static export possible |
| Styling | Tailwind CSS 4 + shadcn/ui | Fast iteration, accessible components, no CSS-in-JS runtime |
| API | Hono.js | Runs on Workers/Node/Bun; 14KB; middleware ecosystem |
| Database | Cloudflare D1 (SQLite at edge) | Sessions, share links, snapshots; zero-ops |
| KV store | Cloudflare KV | Rate limit counters, feature flags, cache |
| Object store | Cloudflare R2 | Zip exports, telemetry blobs |
| Auth | Anonymous-first; optional GitHub OAuth | No friction for first use; auth for save/share |
| Testing | Vitest + Testing Library + Playwright | Fast unit tests, real browser E2E |
| Linting | Biome | Single tool for format + lint; 100x faster than ESLint+Prettier |
| CI | GitHub Actions | Free for public repos, mature |

### 3.3 Tasks Checklist

```
[ ] Initialize monorepo with pnpm-workspace.yaml + turbo.json
[ ] Scaffold Next.js 15 app in apps/web/
[ ] Scaffold Hono.js Worker in apps/api/
[ ] Scaffold shared-rules package in packages/shared-rules/
[ ] Configure Biome (format + lint)
[ ] Configure Vitest workspace
[ ] Set up GitHub Actions CI (lint → test → build → preview-deploy)
[ ] Configure Cloudflare Pages project (web) + Workers project (api)
[ ] Set up Wrangler for local dev (D1 local, KV local, R2 local)
[ ] Create dev script: `pnpm dev` starts both web + api with hot reload
[ ] Verify: push to branch → CI passes → preview URL live
[ ] Set up Sentry or equivalent error tracking
```

### 3.4 Auth/Session Strategy

**Phase 1 (MVP):** Anonymous sessions stored in browser `localStorage` + optional server-side via D1.

```
Session model:
  - id: ULID (client-generated, no signup required)
  - created_at: timestamp
  - configs: JSON blob (imported + modified configs)
  - preset: { gameProfile, hardwareTier, aggressiveness }
  - hardware: { cpu, ram, players, diskType } (nullable)
  - plugins: string[] (nullable)
  - snapshots: JSON[] (v1.5)
  - share_token: string (v1.5, signed JWT)
```

**Phase 2 (v1.5):** Optional GitHub OAuth for persistent sessions + shareable links.

---

## 4. Phase 2 — Shared Rules Engine (Day 2-6)

This is the **most critical deliverable** — everything depends on it.

### 4.1 Extraction Plan from Java Source

Map each Java class to its TypeScript equivalent:

| Java Source | TypeScript Target | Notes |
|-------------|-------------------|-------|
| `setup/model/GameProfile.java` | `types/enums.ts` | Enum: `SMP, SKYBLOCK, MINIGAME, CREATIVE` |
| `setup/model/HardwareTier.java` | `types/enums.ts` | Enum: `LOW, MID, HIGH` |
| `setup/model/AggressivenessLevel.java` | `types/enums.ts` | Enum: `SAFE, BALANCED, AGGRESSIVE` |
| `setup/model/Severity.java` | `types/enums.ts` | Enum: `INFO, WARNING, CRITICAL` |
| `setup/model/RiskTag.java` | `types/enums.ts` | Enum: `LOW, MEDIUM, HIGH` |
| `setup/model/ApplyScope.java` | `types/enums.ts` | Enum: `RECOMMEND, LESSLAG_APPLY` |
| `setup/rules/RuleResult.java` | `types/rule-result.ts` | Interface with all fields |
| `setup/rules/PatchProposal.java` | `types/rule-result.ts` | Interface with all fields |
| `setup/preset/PresetMatrix.java` | `engine/preset-generator.ts` | Port all formulas exactly |
| `setup/preset/PresetProfile.java` | `types/config.ts` | Type: `Record<string, string>` + label |
| `setup/rules/SafetyRules.java` | `schema/rules.ts` + `engine/evaluator.ts` | 12+ safety checks |
| `setup/rules/ConsistencyRules.java` | `schema/rules.ts` + `engine/evaluator.ts` | 8+ consistency checks |
| `setup/rules/ConflictRules.java` | `schema/conflicts.ts` + `engine/evaluator.ts` | Plugin conflict database |
| `setup/rules/ForkSpecificRules.java` | `schema/rules.ts` + `engine/evaluator.ts` | Paper/Purpur/Pufferfish/Leaf rules |
| `setup/rules/PerformanceTuningRules.java` | `schema/rules.ts` + `engine/evaluator.ts` | LessLag-specific tuning |
| `setup/detect/ConfigAdapter.java` | `schema/config-keys.ts` | Config file definitions + key metadata |

### 4.2 Config Key Schema

Every tunable config key needs a schema entry:

```typescript
// packages/shared-rules/src/schema/config-keys.ts

export interface ConfigKeyDef {
  file: TargetFile;           // e.g. "paper-world-defaults.yml"
  key: string;                // dot-path, e.g. "chunks.max-auto-save-chunks-per-tick"
  type: 'int' | 'float' | 'boolean' | 'string' | 'enum';
  default: string;            // server default value
  min?: number;
  max?: number;
  enumValues?: string[];
  unit?: string;              // "blocks", "ticks", "ms", "chunks"
  category: string;           // UI grouping: "entities", "chunks", "redstone", etc.
  description: string;        // Tooltip text
  riskIfChanged: RiskTag;     // How risky is deviation from default
  serverForks: ServerFork[];  // Which forks have this key
}

export type TargetFile =
  | 'server.properties'
  | 'bukkit.yml'
  | 'spigot.yml'
  | 'paper-global.yml'
  | 'paper-world-defaults.yml'
  | 'purpur.yml'
  | 'pufferfish.yml'
  | 'leaves.yml'
  | 'lesslag/config.yml';

export type ServerFork = 'vanilla' | 'spigot' | 'paper' | 'purpur' | 'pufferfish' | 'leaf';
```

### 4.3 Preset Generator Port

Port `PresetMatrix.java` line-for-line. The existing Java code computes ~40 config values from the 3 axes. Key formulas to preserve:

```typescript
// packages/shared-rules/src/engine/preset-generator.ts

export function generatePreset(
  profile: GameProfile,
  tier: HardwareTier,
  aggressiveness: AggressivenessLevel,
  playerCount?: number
): PresetProfile {
  // 1. Apply load modifier (matches PresetMatrix.applyLoadModifier)
  let effectiveTier = tier;
  if (playerCount) {
    if (tier === 'HIGH' && playerCount >= 80) effectiveTier = 'MID';
    if (tier === 'MID' && playerCount >= 50) effectiveTier = 'LOW';
  }

  // 2. Compute all values using same formulas as Java
  const settings: Record<string, string> = {};

  // workload-budget, redstone-limit, entity-chunk-limit, per-world limits,
  // ai-radius, density-limits, breeding-limits, villager-optimizer,
  // tps-thresholds, view/sim distances, bukkit spawn-limits, mob-spawn-range
  // ... (port each formula exactly from PresetMatrix.java)

  return {
    label: `${profile} / ${effectiveTier} / ${aggressiveness}`,
    settings,
  };
}
```

### 4.4 Rule Evaluator Port

```typescript
// packages/shared-rules/src/engine/evaluator.ts

export interface EvaluationInput {
  platform: {
    fork: ServerFork;
    version: string;        // e.g. "1.20.4"
    hasFolia: boolean;
  };
  configs: Map<TargetFile, Record<string, unknown>>;  // parsed YAML
  plugins: string[];                                   // plugin names
  hardware: HardwareProfile | null;
  profile: GameProfile;
  tier: HardwareTier;
  aggressiveness: AggressivenessLevel;
}

export interface EvaluationOutput {
  results: RuleResult[];
  proposals: PatchProposal[];
  summary: {
    total: number;
    bySeverity: Record<Severity, number>;
    byRisk: Record<RiskTag, number>;
    estimatedImpact: string;     // "Moderate improvement expected"
  };
}

export function evaluate(input: EvaluationInput): EvaluationOutput {
  const results: RuleResult[] = [];
  const proposals: PatchProposal[] = [];
  const seen = new Set<string>(); // dedup key: `${file}:${key}`

  // Run rules in priority order (matches RuleEngine.java)
  for (const ruleFn of [
    evaluateSafetyRules,      // priority 10
    evaluateConsistencyRules, // priority 20
    evaluateConflictRules,    // priority 30
    evaluateForkRules,        // priority 40
    evaluatePerformanceRules, // priority 50
  ]) {
    ruleFn(input, results, proposals, seen);
  }

  return { results, proposals, summary: computeSummary(results, proposals) };
}
```

### 4.5 Diff Generator

```typescript
// packages/shared-rules/src/engine/diff-generator.ts

export interface ConfigDiff {
  file: TargetFile;
  changes: ConfigChange[];
}

export interface ConfigChange {
  key: string;
  before: string | undefined;  // undefined if key didn't exist
  after: string;
  risk: RiskTag;
  ruleId: string;
  rationale: string;
}

export function generateDiffs(
  currentConfigs: Map<TargetFile, Record<string, unknown>>,
  proposals: PatchProposal[]
): ConfigDiff[] { /* ... */ }
```

### 4.6 Validation: Golden-File Tests

For each of the 36 preset combinations (4 × 3 × 3), generate expected output from the Java plugin and store as golden files:

```
packages/shared-rules/tests/golden-files/
├── smp-low-safe.json
├── smp-low-balanced.json
├── smp-low-aggressive.json
├── smp-mid-safe.json
├── ... (36 files total)
```

**Generation process:**
1. Add a test utility to the Java project that serializes `PresetMatrix.generatePreset()` output to JSON for all 36 combos
2. Run once, commit golden files
3. TypeScript tests assert `generatePreset()` output matches golden file byte-for-byte

### 4.7 Tasks Checklist

```
[ ] Create packages/shared-rules/ with tsconfig, vitest, package.json
[ ] Port all model enums to types/enums.ts
[ ] Port RuleResult + PatchProposal types to types/rule-result.ts
[ ] Define ConfigKeyDef schema with all known keys from ConfigAdapter.java
[ ] Port PresetMatrix.java → engine/preset-generator.ts (all 40+ formulas)
[ ] Port SafetyRules.java → engine/evaluator.ts (12+ checks)
[ ] Port ConsistencyRules.java → engine/evaluator.ts (8+ checks)
[ ] Port ConflictRules.java → schema/conflicts.ts + evaluator (6+ conflicts)
[ ] Port ForkSpecificRules.java → engine/evaluator.ts (15+ checks)
[ ] Port PerformanceTuningRules.java → engine/evaluator.ts (5+ checks)
[ ] Build diff-generator.ts
[ ] Generate 36 golden files from Java plugin
[ ] Write golden-file comparison tests (all 36 must pass)
[ ] Write unit tests for each rule category (≥ 95% coverage)
[ ] Add YAML parser (js-yaml) + serializer with comment preservation
[ ] Add rule versioning (schema version field + migration function)
[ ] Export clean public API via index.ts
[ ] Verify: `pnpm test --filter shared-rules` → all green
```

---

## 5. Phase 3 — MVP Features (Day 4-10)

### 5.1 F01: Interactive Config Editor

**Architecture:**

```
app/editor/page.tsx          ← Page component
components/editor/
├── EditorLayout.tsx         ← 3-panel layout (controls | preview | info)
├── ConfigSection.tsx        ← Collapsible section per category
├── controls/
│   ├── SliderControl.tsx    ← Numeric range (view-distance, spawn-limits)
│   ├── ToggleControl.tsx    ← Boolean (online-mode, per-player-mob-spawns)
│   ├── SelectControl.tsx    ← Enum (redstone-implementation: VANILLA|ALTERNATE_CURRENT)
│   ├── TextControl.tsx      ← Free-text (rarely used)
│   └── ControlFactory.tsx   ← Renders correct control from ConfigKeyDef.type
├── YamlPreview.tsx          ← Live YAML output with syntax highlighting
├── DiffPanel.tsx            ← F03: before/after diff
└── RationalePanel.tsx       ← F04: "Why this change?"
```

**State management:** Zustand store (lightweight, no boilerplate):

```typescript
// apps/web/lib/store.ts
interface EditorState {
  // Inputs
  profile: GameProfile;
  tier: HardwareTier;
  aggressiveness: AggressivenessLevel;
  hardware: HardwareProfile | null;
  plugins: string[];
  importedConfigs: Map<TargetFile, Record<string, unknown>>;

  // Computed (derived via shared-rules)
  preset: PresetProfile;
  overrides: Map<string, string>;   // user manual overrides on top of preset
  evaluation: EvaluationOutput;
  diffs: ConfigDiff[];

  // Actions
  setProfile: (p: GameProfile) => void;
  setTier: (t: HardwareTier) => void;
  setAggressiveness: (a: AggressivenessLevel) => void;
  setOverride: (key: string, value: string) => void;
  importConfig: (file: TargetFile, content: string) => void;
  resetToPreset: () => void;
}
```

**Key behavior:**
- Every state change triggers re-evaluation via `shared-rules` engine (runs client-side, <50ms)
- YAML preview updates in real-time (debounced 100ms)
- Controls show current value, preset value, and server default with visual indicators
- Modified values highlighted with colored badge

### 5.2 F02: Preset Selector

```
components/presets/
├── PresetSelector.tsx       ← 3 dropdown/radio groups
├── PresetCard.tsx           ← Summary card per combo
└── PresetComparison.tsx     ← Quick compare between presets
```

**UX flow:**
1. User picks server type → 4 large cards with icons (SMP, Skyblock, Minigame, Creative)
2. Hardware tier → 3 cards (Low/Mid/High) with example specs
3. Aggressiveness → 3-position slider (Safe → Balanced → Aggressive) with risk description
4. Instant preview: summary of key changes + estimated impact
5. "Apply Preset" populates editor with all preset values

### 5.3 F03: Diff Viewer

```
components/diff/
├── DiffViewer.tsx           ← Full diff view
├── FileDiffTab.tsx          ← Per-file tab with change count badge
├── KeyDiff.tsx              ← Single key change row
└── DiffToolbar.tsx          ← Filters: by risk, by file, show unchanged
```

**Implementation:**
- Uses `shared-rules/diff-generator.ts` to compute diffs
- Two modes: **compact** (key-level table) and **unified** (YAML diff with +/- lines)
- Color coding: green = safe, yellow = medium risk, red = high risk
- Click any change → scrolls to "Why this change?" panel

### 5.4 F04: "Why This Change?" Panel

```
components/rationale/
├── RationalePanel.tsx       ← Sidebar panel
├── RuleCard.tsx             ← Single rule explanation
└── RiskBadge.tsx            ← Visual risk indicator
```

**Content per change:**
- Rule ID + group name (e.g., "consistency-03: View ≥ Simulation Distance")
- **Why:** `RuleResult.why` (e.g., "View distance should be ≥ simulation distance to prevent invisible entity behavior")
- **Impact:** `RuleResult.impact` (e.g., "Fixes client rendering artifacts near chunk borders")  
- **Trade-off:** `RuleResult.tradeoff` (e.g., "Higher view distance increases memory usage ~15MB per extra chunk ring")
- **Risk:** `PatchProposal.riskTag` badge
- **Source:** Attribution (e.g., "Paper Chan optimization guide")
- **Manual steps:** If `RuleResult.manualSteps` is non-empty, show collapsible instructions

### 5.5 F05: Export Package

```
components/export/
├── ExportDialog.tsx         ← Modal with export options
├── FileList.tsx             ← Checkboxes per output file
└── ExportButton.tsx         ← Triggers download
```

**Export formats:**
1. **Zip download** — correct directory structure mirroring server layout:
   ```
   lesslag-config-export/
   ├── server.properties
   ├── bukkit.yml
   ├── spigot.yml
   ├── config/
   │   ├── paper-global.yml
   │   └── paper-world-defaults.yml
   ├── plugins/LessLag/
   │   └── config.yml
   ├── CHANGES.md              ← Human-readable summary
   └── session-metadata.json   ← Machine-readable session data
   ```
2. **Per-file copy** — click any file → copies to clipboard with toast notification
3. **Session JSON** — full session state for re-import

**Implementation:** Use `JSZip` library client-side (no server round-trip needed).

### 5.6 F06: Import Existing Config

```
components/import/
├── ImportDialog.tsx          ← Modal with upload/paste
├── FileUpload.tsx           ← Drag-and-drop zone
├── PasteImport.tsx          ← Text area with auto-detect
├── ImportPreview.tsx        ← Shows parsed keys before confirming
└── AnalysisOverlay.tsx      ← Runs rule engine on import, shows findings
```

**Implementation:**
- Accepts: file upload (single or zip), paste, URL fetch
- Auto-detects file type from content structure (Paper vs Spigot vs Bukkit etc.)
- Parses YAML with `js-yaml`, validates against `ConfigKeyDef` schema
- On import: immediately runs `evaluate()` and shows results overlay:
  - "Found 7 issues: 2 critical, 3 warnings, 2 info"
  - "Recommended: Apply SMP/Mid/Balanced preset (12 improvements)"
- Unknown keys preserved but flagged

### 5.7 API Endpoints (MVP)

```
POST /api/evaluate          ← Run rule engine on config
  Body: { configs, plugins, platform, profile, tier, aggressiveness }
  Response: EvaluationOutput

POST /api/export/zip        ← Generate zip (if client-side JSZip too slow)
  Body: { configs, metadata }
  Response: binary zip

POST /api/session           ← Create/save session (anonymous)
  Body: { session }
  Response: { id, created_at }

GET  /api/session/:id       ← Load session
  Response: { session }

GET  /api/presets/:profile/:tier/:aggressiveness
  Response: PresetProfile
```

> **Note:** Most logic runs client-side via `shared-rules`. API is optional for MVP but needed for share links (v1.5) and telemetry (v2).

### 5.8 Tasks Checklist

```
[ ] Set up Zustand store with EditorState
[ ] Build ControlFactory + all control types (Slider, Toggle, Select, Text)
[ ] Build ConfigSection with collapsible groups per category
[ ] Build EditorLayout (3-panel responsive: controls | preview | info)
[ ] Build YamlPreview with syntax highlighting (shiki or prism)
[ ] Build PresetSelector (3-axis picker with cards)
[ ] Wire preset selection → store → editor controls
[ ] Build DiffViewer (compact + unified modes)
[ ] Build RationalePanel with rule explanations
[ ] Build ExportDialog with zip + per-file copy
[ ] Build ImportDialog with upload + paste + auto-detect
[ ] Build AnalysisOverlay for import results
[ ] Implement API routes (evaluate, export, session CRUD)
[ ] Build responsive layout (mobile: stacked panels; desktop: side-by-side)
[ ] Add keyboard shortcuts (Ctrl+S export, Ctrl+Z undo, Ctrl+I import)
[ ] Write component tests for all controls
[ ] Write integration test: import → edit → diff → export → re-import
[ ] Verify: complete flow works end-to-end in preview deployment
```

---

## 6. Phase 4 — v1.5 Features (Day 8-14)

### 6.1 F07: Hardware Profile Wizard

```
components/wizard/
├── HardwareWizard.tsx       ← Multi-step form
├── steps/
│   ├── CpuStep.tsx          ← CPU selection (dropdown of common server CPUs + custom)
│   ├── RamStep.tsx          ← RAM slider (2-64 GB)
│   ├── PlayersStep.tsx      ← Expected player count slider
│   ├── DiskStep.tsx         ← SSD vs HDD toggle
│   └── ResultStep.tsx       ← Recommended tier + explanation
└── hardware-db.ts           ← CPU database with single-thread perf scores
```

**Auto-tune logic:**
1. Map CPU to single-thread performance score (maintain database of common server CPUs: Ryzen 5800X, Xeon E-2388G, i9-13900K, etc.)
2. Combined score: `cpuScore * 0.4 + ramScore * 0.3 + playerModifier * 0.2 + diskScore * 0.1`
3. Map to `HardwareTier`: LOW (<40), MID (40-70), HIGH (>70)
4. Apply `PresetMatrix.applyLoadModifier()` for player count
5. Show confidence level and explanation

### 6.2 F08: Plugin Conflict Detector

```
components/plugins/
├── PluginInput.tsx           ← Multi-select/tag input for plugin names
├── ConflictReport.tsx        ← List of detected conflicts
└── PluginCard.tsx            ← Individual conflict explanation
```

**Data source:** Port `ConflictRules.java` conflict database:

| Plugin(s) | Issue | Severity |
|-----------|-------|----------|
| ClearLag / EntityTrackerFixer | "Inherently flawed approach" | CRITICAL |
| Pufferfish DAB + LessLag frustum culling | Overlap, disable one | WARNING |
| FarmControl / MobFarmManager | Overlap with LessLag entity limits | WARNING |
| Mob stackers (MobStacker, WildStacker, etc.) | "Never let server reach mob cap" | WARNING |
| SilkTouchSpawners | "Built-in lag machines" | WARNING |
| Fabric plugins on Paper | Incompatible | CRITICAL |

### 6.3 F09: Rollback Snapshots/History

**Implementation:**
- Every state change creates a snapshot (debounced 2s)
- Store snapshots in IndexedDB (client) + D1 (server, if session saved)
- Max 50 snapshots per session, FIFO eviction
- UI: timeline slider showing snapshots with timestamps and labels
- "Restore" loads full state from snapshot
- "Compare" opens diff between current state and any snapshot

```typescript
interface Snapshot {
  id: string;                    // ULID
  timestamp: number;
  label: string;                 // Auto-generated: "Changed view-distance to 8"
  state: Omit<EditorState, 'actions'>;  // Full state minus functions
  trigger: 'preset-change' | 'manual-edit' | 'import' | 'user-save';
}
```

### 6.4 F10: Shareable Links

**Implementation:**
1. User clicks "Share" → session saved to D1 with signed token
2. Token: `JWT { sessionId, createdAt, expiresAt, readOnly: true }` signed with Worker secret
3. URL: `https://lesslag.dev/s/{token}`
4. Recipient sees read-only editor with all state loaded
5. "Fork" button creates new editable session from shared state

**Security:**
- Tokens expire after 30 days (configurable)
- Rate limit: 10 shares per session per hour
- No PII in session data (no auth required to view)
- Token rotation on session update

### 6.5 Tasks Checklist

```
[ ] Build HardwareWizard multi-step form
[ ] Create CPU database with performance scores (top 30 server CPUs)
[ ] Implement auto-tune scoring algorithm
[ ] Wire hardware wizard → store → preset generator
[ ] Build PluginInput with autocomplete (known plugin database)
[ ] Port ConflictRules conflict database to shared-rules
[ ] Build ConflictReport UI with severity badges
[ ] Implement IndexedDB snapshot storage
[ ] Build snapshot timeline UI with restore/compare
[ ] Implement D1 session persistence
[ ] Build share link generation (JWT signing in Worker)
[ ] Build share link viewer (read-only mode)
[ ] Build "Fork" button from shared session
[ ] Add rate limiting for share endpoint
[ ] Write tests for snapshot CRUD, share token validation
[ ] Write E2E test: create → share → open → fork → edit → export
```

---

## 7. Phase 5 — v2 Power Features (Day 12-20)

### 7.1 F11: MSPT/TPS Impact Estimator

**Model architecture:**

```
packages/shared-rules/src/engine/impact-estimator.ts
```

**Approach:** Empirical regression model, not simulation.

1. **Baseline:** Known MSPT costs per feature:
   - Entities: ~0.02ms per entity per tick (varies by type)
   - Chunks: ~0.005ms per loaded chunk per tick
   - Redstone: ~0.1ms per active redstone component per tick
   - Villagers: ~0.05ms per villager per tick (with full AI)
   - View distance: exponential cost (chunks = (2r+1)² per world per player)

2. **Inputs:** Hardware tier, player count, world count, entity counts, config values
3. **Output:** 
   ```typescript
   interface ImpactEstimate {
     estimatedMspt: { low: number; mid: number; high: number };
     estimatedTps: { low: number; mid: number; high: number };
     confidence: 'low' | 'medium' | 'high';
     breakdown: { component: string; msptContribution: number }[];
     caveats: string[];
   }
   ```

4. **Confidence bands:** ±20% for LOW hardware, ±15% for MID, ±10% for HIGH (based on CPU variance)
5. **Calibration (v2+):** Opt-in telemetry data improves model coefficients over time

### 7.2 F12: A/B Config Compare

```
components/compare/
├── CompareLayout.tsx        ← Split-screen with two editors
├── ComparePanel.tsx         ← Single config set (reuses editor components)
├── CompareDiff.tsx          ← Diff between A and B
└── CompareImpact.tsx        ← Side-by-side impact estimates
```

**UX:**
- "Compare" button opens split view
- Config A = current editor state; Config B = another preset or imported config
- Diff highlights differences between A and B (not vs defaults)
- Impact estimator shows both estimates side-by-side with delta

### 7.3 F13: Guided Onboarding Wizard

```
components/onboarding/
├── OnboardingFlow.tsx        ← Step container
├── steps/
│   ├── WelcomeStep.tsx       ← "What brings you here?" (new server / optimizing / copying)
│   ├── ServerTypeStep.tsx    ← GameProfile picker (large cards with descriptions)
│   ├── HardwareStep.tsx      ← Simplified hardware questions
│   ├── ProblemsStep.tsx      ← "What issues are you experiencing?" (multi-select: lag spikes, low TPS, entity lag, redstone lag, chunk lag)
│   ├── PluginsStep.tsx       ← Optional plugin list entry
│   ├── ReviewStep.tsx        ← AI-generated summary + recommended preset
│   └── ApplyStep.tsx         ← One-click apply + go to editor
└── onboarding-logic.ts      ← Maps answers → preset + rule evaluation
```

**Flow mapping:**
- Problems → targeted rule evaluation (entity lag → focus on ConsistencyRules entity checks)
- Server type + hardware → preset selection
- Plugins → conflict detection
- Result: pre-configured editor with relevant rules highlighted

### 7.4 F14: Optional Telemetry Pipeline

**Privacy-first design:**

```typescript
interface TelemetryEvent {
  // Anonymous hardware fingerprint (hashed, no PII)
  hardwareHash: string;                    // SHA-256(cpu+ram+os)
  serverFork: ServerFork;
  playerCount: number;                     // Bucketed: 0-10, 10-50, 50-100, 100+
  presetUsed: string;                      // "SMP/MID/BALANCED"
  configKeysModified: string[];            // Which keys were changed from preset
  exportFormat: 'zip' | 'copy' | 'apply';
  // No IPs, no usernames, no server names, no chat logs
}
```

**Pipeline:**
1. Client: opt-in checkbox (unchecked by default) + privacy policy link
2. Events batched locally, sent every 5 min or on export
3. Worker: validates schema, strips any unexpected fields, writes to R2 as NDJSON
4. Weekly aggregation job: compute model coefficients, popular presets, common issues
5. Aggregated stats → public dashboard (optional)

### 7.5 F15: One-Click Apply API

**Requires plugin-side HTTP endpoint** (separate implementation in the Java plugin).

**Web side:**

```
POST /api/apply
  Headers: Authorization: Bearer <server-token>
  Body: {
    serverId: string,
    patches: PatchProposal[],
    dryRun: boolean
  }
  Response: {
    status: 'applied' | 'partial' | 'failed',
    applied: PatchProposal[],
    skipped: PatchProposal[],
    errors: string[]
  }
```

**Plugin side (future work):**
1. New command: `/lg web-link` → generates time-limited API token, stores in config
2. Plugin opens local HTTP listener (configurable port, localhost-only by default)
3. Accepts authenticated PATCH requests with config changes
4. Validates against rule engine, applies to live config, triggers hot-reload
5. Returns result to web UI

**Security:**
- Token: HMAC-signed, 1-hour expiry, single-use per patch set
- Plugin validates token + checksums before applying
- Audit log of all remote applies

### 7.6 Tasks Checklist

```
[ ] Build impact estimator model with baseline coefficients
[ ] Create impact estimate UI component with confidence bands
[ ] Build A/B compare split-screen layout
[ ] Build compare diff view between two configs
[ ] Wire impact estimator to both compare panels
[ ] Build onboarding wizard flow (7 steps)
[ ] Implement problem-to-rule mapping logic
[ ] Build telemetry opt-in UI + privacy policy page
[ ] Implement telemetry event schema + client-side batching
[ ] Build Worker endpoint for telemetry ingestion → R2
[ ] Design apply API contract + token flow
[ ] Build apply API Worker endpoint
[ ] Document plugin-side HTTP endpoint spec for future implementation
[ ] Write tests for impact estimator (known inputs → expected ranges)
[ ] Write E2E test: onboarding → preset → edit → compare → export
```

---

## 8. Phase 6 — Data, Security, Reliability (parallel)

### 8.1 Input Validation & Schema Checks

| Layer | Validation | Tool |
|-------|-----------|------|
| Client | YAML parse + ConfigKeyDef schema check | `js-yaml` + Zod |
| API | Request body validation | Zod + Hono validator middleware |
| Import | File size limit (1MB), key count limit (10K), depth limit (20) | Custom validator |
| Export | Output schema validation before zip creation | Zod |

**Zod schemas** for all API payloads:

```typescript
// apps/api/src/middleware/schemas.ts
import { z } from 'zod';

export const EvaluateRequest = z.object({
  configs: z.record(z.string(), z.record(z.string(), z.unknown())),
  plugins: z.array(z.string()).max(200),
  platform: z.object({
    fork: z.enum(['vanilla', 'spigot', 'paper', 'purpur', 'pufferfish', 'leaf']),
    version: z.string().regex(/^1\.\d+(\.\d+)?$/),
    hasFolia: z.boolean(),
  }),
  profile: z.enum(['SMP', 'SKYBLOCK', 'MINIGAME', 'CREATIVE']),
  tier: z.enum(['LOW', 'MID', 'HIGH']),
  aggressiveness: z.enum(['SAFE', 'BALANCED', 'AGGRESSIVE']),
});
```

### 8.2 Rate Limiting & Abuse Controls

| Endpoint | Limit | Window | Key |
|----------|-------|--------|-----|
| `POST /api/evaluate` | 60 req | 1 min | IP |
| `POST /api/export/zip` | 20 req | 1 min | IP |
| `POST /api/session` | 30 req | 1 min | IP |
| `GET /api/session/:id` | 120 req | 1 min | IP |
| `POST /api/share` | 10 req | 1 hour | session |
| `POST /api/apply` | 5 req | 1 hour | server token |
| `POST /api/telemetry` | 10 req | 5 min | hardware hash |

**Implementation:** Cloudflare KV-based sliding window counter (or Workers Rate Limiting API).

### 8.3 Secret Management

| Secret | Storage | Rotation |
|--------|---------|----------|
| JWT signing key (share links) | Worker secret | 90 days |
| Apply API HMAC key | Worker secret + plugin config | On-demand |
| Sentry DSN | Worker secret | Never |
| D1 connection | Wrangler binding (automatic) | N/A |

### 8.4 Backup & Recovery

- **D1 snapshots:** Daily automated backup via Cloudflare dashboard
- **Session export:** Users can download full session JSON at any time
- **Share link recovery:** Expired links → show "Link expired" with option to create new session

### 8.5 Error Budget & SLOs

| SLO | Target | Error Budget (monthly) |
|-----|--------|----------------------|
| Availability | 99.9% | 43 min downtime |
| p95 page load | < 2s | Alerting via Lighthouse CI |
| p95 API latency | < 200ms | Cloudflare analytics |
| Export success rate | > 99.9% | Sentry error tracking |
| YAML correctness | 100% | Golden-file test failures = P0 |

### 8.6 Tasks Checklist

```
[ ] Define Zod schemas for all API payloads
[ ] Implement Hono validator middleware
[ ] Add YAML import validation (size, depth, key count)
[ ] Implement KV-based rate limiter
[ ] Configure Worker secrets (JWT key, Sentry DSN)
[ ] Set up Sentry error tracking for web + api
[ ] Configure Cloudflare analytics dashboards
[ ] Write rate limit integration tests
[ ] Set up D1 daily backup schedule
[ ] Create privacy policy page
[ ] Create audit log table in D1 for apply actions
[ ] Set up Lighthouse CI in GitHub Actions
[ ] Set up uptime monitoring (Cloudflare health checks)
```

---

## 9. Phase 7 — Testing Strategy (parallel)

### 9.1 Test Pyramid

```
                    ┌─────────┐
                    │  E2E    │  5-10 flows (Playwright)
                   ┌┴─────────┴┐
                   │ Integration│  20-30 tests (API + UI)
                  ┌┴───────────┴┐
                  │ Component    │  50-80 tests (React Testing Lib)
                 ┌┴─────────────┴┐
                 │ Unit tests     │  200+ tests (Vitest)
                 └───────────────┘
```

### 9.2 Unit Tests (shared-rules)

| Test Suite | What it covers | Count |
|-----------|---------------|-------|
| `preset-generator.test.ts` | All 36 combos match golden files | 36 |
| `evaluator-safety.test.ts` | Each SafetyRule check | 12+ |
| `evaluator-consistency.test.ts` | Each ConsistencyRule check | 8+ |
| `evaluator-conflict.test.ts` | Each plugin conflict | 6+ |
| `evaluator-fork.test.ts` | Each fork-specific rule | 15+ |
| `evaluator-performance.test.ts` | Each performance tuning rule | 5+ |
| `diff-generator.test.ts` | Diff correctness, edge cases | 20+ |
| `impact-estimator.test.ts` | Known inputs → expected ranges | 15+ |
| `yaml-parser.test.ts` | Round-trip, comments, edge cases | 10+ |

### 9.3 Contract Tests (UI ↔ API)

Ensure the web app and API agree on payload shapes:

```typescript
// Contract test: same Zod schema validates both sides
import { EvaluateRequest, EvaluateResponse } from '@lesslag/shared-rules';

test('API accepts what UI sends', async () => {
  const payload = buildMockPayload();
  expect(EvaluateRequest.safeParse(payload).success).toBe(true);

  const response = await fetch('/api/evaluate', { body: JSON.stringify(payload) });
  expect(EvaluateResponse.safeParse(await response.json()).success).toBe(true);
});
```

### 9.4 Golden-File Tests

```
packages/shared-rules/tests/golden-files/
├── presets/
│   ├── smp-low-safe.json          # Expected PresetProfile output
│   ├── ... (36 files)
│   └── creative-high-aggressive.json
├── evaluations/
│   ├── default-paper-config.json   # Expected EvaluationOutput for vanilla Paper config
│   ├── badly-tuned-server.json     # Config with many issues
│   └── well-tuned-server.json      # Config with few issues
└── exports/
    ├── smp-mid-balanced.zip        # Expected zip output
    └── smp-mid-balanced/           # Unzipped for diff comparison
        ├── server.properties
        └── ...
```

### 9.5 E2E Tests (Playwright)

| Flow | Steps | Assertions |
|------|-------|-----------|
| Fresh start | Open → select preset → review diff → export zip | Zip downloads, contains correct files |
| Import flow | Open → import config → view analysis → apply fixes → export | Analysis shows correct issues, export reflects fixes |
| Round-trip | Export → re-import → verify identical state | No diff between export and re-import |
| Share flow | Create session → share → open link → verify read-only | Shared view matches original |
| Onboarding | Start wizard → answer questions → arrive at editor | Correct preset selected, relevant rules highlighted |
| Compare | Open two presets → compare → verify diff | Diff shows correct differences |

### 9.6 Load Tests

Target: sustain 100 concurrent users for 5 minutes.

| Endpoint | Target RPS | p95 Latency | Tool |
|----------|-----------|-------------|------|
| `GET /` (page load) | 200 | < 500ms | k6 |
| `POST /api/evaluate` | 100 | < 200ms | k6 |
| `POST /api/export/zip` | 50 | < 1s | k6 |
| `GET /api/session/:id` | 200 | < 100ms | k6 |

### 9.7 Tasks Checklist

```
[ ] Set up Vitest workspace config for all packages
[ ] Write all unit tests for shared-rules (target: 200+)
[ ] Generate golden files from Java plugin
[ ] Write golden-file comparison tests
[ ] Set up React Testing Library for component tests
[ ] Write component tests for editor controls (50+)
[ ] Set up Playwright for E2E tests
[ ] Write 6 E2E flow tests
[ ] Set up contract test infrastructure
[ ] Write contract tests for all API endpoints
[ ] Set up k6 for load testing
[ ] Write load test scenarios
[ ] Configure coverage reporting in CI (c8)
[ ] Add coverage gates: shared-rules ≥ 95%, web ≥ 80%
[ ] Verify: `pnpm test` → all green → coverage meets targets
```

---

## 10. Phase 8 — Release Plan

### 10.1 Alpha (Day 15-16)

**Gate criteria:**
- [ ] All MVP features (F01-F06) acceptance tests passing
- [ ] shared-rules coverage ≥ 95%
- [ ] CI green on main branch
- [ ] Preview deployment accessible via URL
- [ ] Error tracking (Sentry) active
- [ ] Rate limiting active

**Distribution:**
- 5-10 selected Minecraft server owners (recruit from plugin Discord/SpigotMC)
- Feedback form (Google Form or Tally)
- 2-week feedback window

### 10.2 Beta (Day 18-20)

**Gate criteria:**
- [ ] All v1.5 features (F07-F10) acceptance tests passing
- [ ] Alpha feedback incorporated
- [ ] Share links working
- [ ] Load tests pass (100 concurrent users)
- [ ] Privacy policy published
- [ ] Telemetry opt-in working (if F14 ready)

**Distribution:**
- Public URL with "Beta" banner
- Feature flags for v2 features (disabled by default)
- Telemetry collection begins (opt-in)

### 10.3 GA (Day 22-25)

**Gate criteria:**
- [ ] All v2 features (F11-F15) acceptance tests passing
- [ ] Beta feedback incorporated
- [ ] E2E test suite fully green
- [ ] Coverage targets met across all packages
- [ ] Performance targets met (Lighthouse, API latency)
- [ ] Documentation complete:
  - [ ] User guide (how to use each feature)
  - [ ] API documentation (OpenAPI spec)
  - [ ] Operator runbook (deploy, rollback, incident response)
- [ ] At least 1 full real-world workflow validated with live server owner
- [ ] Custom domain configured (e.g., `tune.lesslag.dev`)

**Post-GA:**
- Monitor error rates and performance for 1 week
- Address any P0/P1 issues within 24 hours
- Begin planning v3 features based on telemetry + feedback

### 10.4 Rollback Runbook

1. **Revert deployment:** `wrangler rollback` (Workers) + Cloudflare Pages rollback to previous build
2. **Database migration rollback:** D1 schema changes must be backward-compatible; keep rollback SQL for each migration
3. **Feature flag kill switch:** Any feature can be disabled via KV flag without redeploy
4. **Incident communication:** Status page update + Discord announcement

---

## 11. Appendix

### 11.1 File Map — Java Plugin → TypeScript Web

| Java File | Notes | TS Destination |
|-----------|-------|---------------|
| `setup/model/GameProfile.java` | 4 server types | `shared-rules/types/enums.ts` |
| `setup/model/HardwareTier.java` | LOW/MID/HIGH | `shared-rules/types/enums.ts` |
| `setup/model/AggressivenessLevel.java` | SAFE/BALANCED/AGGRESSIVE | `shared-rules/types/enums.ts` |
| `setup/model/Severity.java` | INFO/WARNING/CRITICAL | `shared-rules/types/enums.ts` |
| `setup/model/RiskTag.java` | LOW/MEDIUM/HIGH | `shared-rules/types/enums.ts` |
| `setup/model/ApplyScope.java` | RECOMMEND/LESSLAG_APPLY | `shared-rules/types/enums.ts` |
| `setup/model/SessionStatus.java` | 7 session states | `shared-rules/types/enums.ts` |
| `setup/rules/RuleResult.java` | Rule output data | `shared-rules/types/rule-result.ts` |
| `setup/rules/PatchProposal.java` | Config change proposal | `shared-rules/types/rule-result.ts` |
| `setup/rules/Rule.java` | Rule interface | `shared-rules/engine/evaluator.ts` |
| `setup/rules/RuleEngine.java` | Orchestrator | `shared-rules/engine/evaluator.ts` |
| `setup/rules/SafetyRules.java` | 12+ safety checks | `shared-rules/engine/rules/safety.ts` |
| `setup/rules/ConsistencyRules.java` | 8+ consistency checks | `shared-rules/engine/rules/consistency.ts` |
| `setup/rules/ConflictRules.java` | 6+ plugin conflicts | `shared-rules/engine/rules/conflicts.ts` |
| `setup/rules/ForkSpecificRules.java` | 15+ fork-specific checks | `shared-rules/engine/rules/fork-specific.ts` |
| `setup/rules/PerformanceTuningRules.java` | 5+ perf checks | `shared-rules/engine/rules/performance.ts` |
| `setup/preset/PresetMatrix.java` | 3-axis preset generation | `shared-rules/engine/preset-generator.ts` |
| `setup/preset/PresetProfile.java` | Immutable config map | `shared-rules/types/config.ts` |
| `setup/detect/ConfigAdapter.java` | Config file discovery | `shared-rules/schema/config-keys.ts` |
| `action/ThresholdConfig.java` | TPS threshold data | `shared-rules/schema/thresholds.ts` |
| `util/CompatibilityManager.java` | Plugin compat DB | `shared-rules/schema/conflicts.ts` |

### 11.2 Dependency Map

```
shared-rules (0 external prod deps, only dev deps: vitest, typescript)
  ↑ imported by
  ├── web (Next.js, Tailwind, shadcn/ui, Zustand, JSZip, js-yaml, shiki)
  └── api (Hono, Zod, jose for JWT)
```

### 11.3 Critical Path

```
Phase 0 ──→ Phase 1 ──→ Phase 2 ──→ Phase 3 (MVP) ──→ Alpha
                              ↑           ↑
                    Phase 6 (security) starts here
                    Phase 7 (testing) starts here

Phase 3 ──→ Phase 4 (v1.5) ──→ Beta
Phase 4 ──→ Phase 5 (v2) ──→ GA
```

**Critical path items** (blocking everything downstream):
1. `shared-rules` preset generator (blocks editor, presets, diff)
2. `shared-rules` rule evaluator (blocks analysis, import, rationale panel)
3. Editor state management (blocks all UI features)
4. Golden-file generation from Java plugin (blocks correctness validation)

### 11.4 Risk Register

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| PresetMatrix port diverges from Java | Medium | High | Golden-file tests catch any divergence; run both and compare |
| Cloudflare D1 size limits (500MB free) | Low | Medium | Session TTL + cleanup job; upgrade to paid if needed |
| YAML parsing edge cases (anchors, merge keys, comments) | Medium | Medium | Use `yaml` package (not `js-yaml`) for full YAML 1.2 support with comment preservation |
| Mobile UX too complex for 3-panel editor | Medium | Low | Progressive disclosure: mobile shows one panel at a time with tab bar |
| One-click apply security vulnerability | Low | Critical | Require localhost-only binding + token + checksum validation; start with dry-run only |
| Rule engine performance on mobile | Low | Low | shared-rules is pure computation; benchmark on low-end Android; memoize if needed |

### 11.5 Estimated Effort (Solo Developer)

| Phase | Estimated Days | Can Overlap With |
|-------|---------------|-----------------|
| Phase 0 (Baseline) | 1 | — |
| Phase 1 (Platform) | 2 | — |
| Phase 2 (Rules Engine) | 4 | Phase 1 (after day 1) |
| Phase 3 (MVP) | 6 | Phase 2 (after day 3) |
| Phase 4 (v1.5) | 5 | Phase 3 (after day 6) |
| Phase 5 (v2) | 6 | Phase 4 (after day 10) |
| Phase 6 (Security) | 3 | Phases 2-5 (continuous) |
| Phase 7 (Testing) | 4 | Phases 2-5 (continuous) |
| Phase 8 (Release) | 3 | After each milestone |
| **Total (sequential)** | **34 days** | |
| **Total (with overlap)** | **~22 days** | |

---

*End of Implementation Plan*
