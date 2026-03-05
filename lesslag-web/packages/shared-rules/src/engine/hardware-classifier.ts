import type { HardwareProfile } from '../types/config.js';
import type { HardwareTier } from '../types/enums.js';

/**
 * Detailed breakdown of scores contributing to the final hardware tier.
 */
export interface HardwareScoreBreakdown {
    /** 0–40: Geekbench SC benchmark score contribution */
    benchmarkScore: number;
    /** 0–20: Topology signal (shared vCPU vs dedicated) */
    topologyScore: number;
    /** 0–15: Physical core count */
    coreScore: number;
    /** 0–15: Allocated JVM heap */
    ramScore: number;
    /** -5 to +10: Live MSPT bonus/penalty (requires server payload) */
    msptBonus: number;
}

/**
 * Full result of hardware classification.
 */
export interface HardwareClassification {
    tier: HardwareTier;
    /** Composite score 0–100 (clamped). */
    score: number;
    breakdown: HardwareScoreBreakdown;
    /** Human-readable reason for the assigned tier. Empty string if straightforward. */
    reason: string;
    /**
     * Confidence level:
     * - HIGH: benchmark score available
     * - MED: CPU model recognised but no exact benchmark score
     * - LOW: no benchmark data, classification based on specs only
     */
    confidence: 'HIGH' | 'MED' | 'LOW';
}

// ─── Score tables ────────────────────────────────────────────

/**
 * Map Geekbench single-core score → points (0–40).
 * Buckets based on real-world MC server performance data.
 *
 * Reference ranges (approximate):
 * - <800  → OLD Xeon E5, EPYC Naples shared vCPU (LOW tier)
 * - 800–1200 → EPYC Rome, Graviton 2, Xeon E-2300 (border LOW/MID)
 * - 1200–1800 → EPYC Milan, Graviton 3, Ryzen 5 5000 (MID)
 * - 1800–2500 → Ryzen 7 5000/7000, Core i7 12th+, EPYC Genoa (HIGH)
 * - 2500+ → Ryzen 9 7000, Core i9 14th+, Graviton 4 (ULTRA / top HIGH)
 */
function benchmarkToScore(gbScore: number): number {
    if (gbScore <= 0) return 0;
    if (gbScore < 600) return 2;
    if (gbScore < 800) return 8;
    if (gbScore < 1000) return 13;
    if (gbScore < 1200) return 18;
    if (gbScore < 1500) return 23;
    if (gbScore < 1800) return 28;
    if (gbScore < 2200) return 33;
    if (gbScore < 2600) return 37;
    return 40; // 2600+
}

/**
 * Core count → points (0–15).
 * Minecraft benefits significantly from 4+ cores for networking, GC, and async I/O.
 */
function coreCountToScore(cores: number): number {
    if (cores <= 0) return 0;
    if (cores === 1) return 1;
    if (cores === 2) return 3;
    if (cores === 3) return 6;
    if (cores === 4) return 8;
    if (cores === 5) return 10;
    if (cores === 6) return 11;
    if (cores === 7) return 13;
    return 15; // 8+
}

/**
 * Heap size (MB) → points (0–15).
 */
function ramToScore(maxHeapMB: number): number {
    if (maxHeapMB < 2048) return 0;
    if (maxHeapMB < 4096) return 3;
    if (maxHeapMB < 6144) return 6;
    if (maxHeapMB < 8192) return 9;
    if (maxHeapMB < 12288) return 12;
    return 15; // 12 GB+
}

/**
 * Topology inference from CPU model string (0–20 points).
 *
 * Shared/low-end vCPU signals (penalty): E5-26xx, N100, EPYC Naples/Rome (NUMA patterns).
 * Dedicated/high-end signals (bonus): bare-metal prefixes, EPYC Genoa, Graviton 3/4, Zen 4+.
 * 
 * NOTE: We cannot know true topology without direct system info, so this is heuristic.
 * Without a benchmark score we lean more on this; with one we weight less.
 */
function inferTopologyScore(cpuModel: string, hasBenchmark: boolean): number {
    const m = cpuModel.toLowerCase();

    // Known shared / budget vCPU patterns → low topology
    const sharedSignals = [
        /e5-\d{4}/,        // Xeon E5 (old EPYC-era)
        /e5-v\d/,          // Xeon E5 vN series
        /xeon.*e5/,
        /n100/,            // Intel Alder Lake N-series (NAS/budget)
        /n200/,
        /n4\d{3}/,         // Intel N-series Atom successors
        /j\d{4}/,          // Pentium J series
        /celeron/,
        /atom/,
    ];

    // High-performance / dedicated signals → top topology
    const dedicatedSignals = [
        /epyc.*9/,          // EPYC 9004 (Genoa/Bergamo)
        /ryzen.*9/,
        /ryzen.*7/,
        /i9-\d{4}[k-z]/,   // i9 K/KS/HX
        /i7-\d{4}[k-z]/,   // i7 K/KS editions
        /i9-\d{5}/,
        /graviton.*[34]/,   // AWS Graviton 3/4
        /grace/,            // NVIDIA Grace
        /ampere.*altra/,    // Ampere Altra
    ];

    // Mid-range / standard dedicated
    const midSignals = [
        /ryzen.*5/,
        /ryzen.*3/,
        /i5-\d{4}/,
        /i7-\d{4}/,
        /epyc.*7[0-9]{3}/,  // EPYC 7xxx (Milan/Rome/Naples)
        /xeon.*e-\d{4}/,    // Xeon E-2xxx (Rocket Lake Xeon)
        /graviton.*2/,
    ];

    if (sharedSignals.some(r => r.test(m))) return hasBenchmark ? 4 : 2;
    if (dedicatedSignals.some(r => r.test(m))) return 20;
    if (midSignals.some(r => r.test(m))) return hasBenchmark ? 12 : 10;

    // Unknown CPU, neutral topology
    return hasBenchmark ? 8 : 5;
}

/**
 * Live MSPT → bonus/penalty (-5 to +10).
 * 
 * MSPT reflects actual server performance CURRENTLY, so it's a strong signal.
 * Note: high MSPT might mean the server is under load, not that hardware is bad.
 */
function msptToBonus(mspt: number): number {
    if (mspt <= 0) return 0;
    if (mspt < 15) return 10;  // Server is flying — excellent hardware
    if (mspt < 20) return 7;   // Solid
    if (mspt < 25) return 4;   // Acceptable
    if (mspt < 30) return 1;   // Marginal at current load
    if (mspt < 40) return -2;  // Under pressure
    if (mspt < 50) return -4;  // Struggling
    return -5;                 // Severe lag spike territory
}

/**
 * Total score → HardwareTier.
 *
 * Score thresholds are calibrated so that:
 * - LOW: budget VPS that needs aggressive config (< 35)
 * - MID: typical modern VPS, can support 20–80 players (35–64)
 * - HIGH: high-end VPS or dedicated, 80+ players (65+)
 */
function scoreToTier(score: number): HardwareTier {
    if (score >= 65) return 'HIGH';
    if (score >= 35) return 'MID';
    return 'LOW';
}

// ─── Public API ──────────────────────────────────────────────

/**
 * Classify hardware into a performance tier using a scoring model.
 *
 * The scoring model is designed to be more accurate than simple threshold checks:
 * - A server with 8 vCPUs of Xeon E5 may score LOW despite high core count
 * - A server with 4 dedicated EPYC Genoa cores scores HIGH due to IPC and clock
 * - Live MSPT data further refines the classification
 *
 * @param hardware  Hardware profile from server payload or manual input
 * @param benchmarkScore  Geekbench single-core score (optional, from API lookup)
 * @param mspt  Current MSPT reading from server (optional, from live payload)
 */
export function classifyHardware(
    hardware: HardwareProfile,
    benchmarkScore?: number,
    mspt?: number,
): HardwareClassification {
    const hasBenchmark = benchmarkScore != null && benchmarkScore > 0;

    const bScore = hasBenchmark ? benchmarkToScore(benchmarkScore!) : 0;
    const tScore = inferTopologyScore(hardware.cpuModel, hasBenchmark);
    const cScore = coreCountToScore(hardware.availableProcessors);
    const rScore = ramToScore(hardware.maxHeapMB);
    const mBonus = mspt != null ? msptToBonus(mspt) : 0;

    const rawScore = bScore + tScore + cScore + rScore + mBonus;
    const score = Math.max(0, Math.min(100, rawScore));
    const tier = scoreToTier(score);

    const breakdown: HardwareScoreBreakdown = {
        benchmarkScore: bScore,
        topologyScore: tScore,
        coreScore: cScore,
        ramScore: rScore,
        msptBonus: mBonus,
    };

    const confidence: HardwareClassification['confidence'] = hasBenchmark ? 'HIGH' : (
        hardware.cpuModel && hardware.cpuModel.length > 3 ? 'MED' : 'LOW'
    );

    // Build a human-readable reason when tier doesn't match naive expectations
    const naiveTier = scoreToTier(
        coreCountToScore(hardware.availableProcessors) + ramToScore(hardware.maxHeapMB) + 10,
    );
    let reason = '';
    if (hasBenchmark && tier !== naiveTier) {
        if (tier === 'LOW' && naiveTier !== 'LOW') {
            reason = `CPU benchmark score (${benchmarkScore}) is low despite adequate core/RAM specs — older or shared vCPU architecture`;
        } else if (tier === 'HIGH' && naiveTier !== 'HIGH') {
            reason = `Strong CPU benchmark score (${benchmarkScore}) elevates tier despite modest core count`;
        }
    }
    if (!hasBenchmark) {
        reason = 'No benchmark data available — tier based on CPU model heuristics and specs only';
    }
    if (mBonus <= -4 && mspt != null) {
        reason += (reason ? ' · ' : '') + `High MSPT (${mspt.toFixed(1)}ms) suggests hardware is under load or underperforming`;
    }

    return { tier, score, breakdown, reason: reason.trim(), confidence };
}

/**
 * Derive a HardwareTier directly from specs (for backwards compatibility).
 * Prefer `classifyHardware()` for new code.
 */
export function tierFromSpecs(cores: number, maxHeapMB: number): HardwareTier {
    const cScore = coreCountToScore(cores);
    const rScore = ramToScore(maxHeapMB);
    // No benchmark, use conservative neutral topology
    const tScore = 5;
    return scoreToTier(cScore + rScore + tScore);
}
