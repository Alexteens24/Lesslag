import type { EvaluationInput } from '../../types/config.js';
import type { RuleResult, PatchProposal } from '../../types/rule-result.js';
import { buildRuleResult, buildPatch } from '../../types/rule-result.js';

function getString(configs: EvaluationInput['configs'], file: string, key: string, def: string): string {
    const fileConfig = configs[file];
    if (!fileConfig) return def;
    const val = fileConfig[key];
    return val != null ? String(val) : def;
}

function getInt(configs: EvaluationInput['configs'], file: string, key: string, def: number): number {
    const fileConfig = configs[file];
    if (!fileConfig) return def;
    const val = fileConfig[key];
    if (val == null) return def;
    const n = typeof val === 'number' ? val : parseInt(String(val).trim(), 10);
    return isNaN(n) ? def : n;
}

function formatMB(mb: number): string {
    if (mb >= 1024) return `${(mb / 1024).toFixed(1)} GB`;
    return `${mb} MB`;
}

export function evaluateSafetyRules(
    input: EvaluationInput,
    results: RuleResult[],
    proposals: PatchProposal[],
    _seen: Set<string>,
): void {
    const { configs, hardware, profile, tier, aggressiveness: level } = input;

    // ── online-mode ──────────────────────────────────────────────
    const onlineMode = getString(configs, 'server.properties', 'online-mode', 'true');
    if (onlineMode.toLowerCase() === 'false') {
        results.push(buildRuleResult('safety-online-mode', {
            group: 'safety', severity: 'WARNING', confidence: 1.0,
            why: 'Server is running in offline mode (online-mode=false)',
            impact: 'Players can join without Mojang authentication — security risk',
            tradeoff: 'Required for BungeeCord/Velocity proxied setups; otherwise a vulnerability',
            recommendation: 'Ensure this is intentional. Use a proxy with ip-forwarding if behind Bungee/Velocity.',
            manualSteps: 'If using a proxy, verify ip-forwarding is correctly configured in the proxy config.',
            impactedKeys: ['server.properties:online-mode'],
        }));
    }

    // ── world-guard defaults info ────────────────────────────────
    results.push(buildRuleResult('safety-world-guard-defaults', {
        group: 'safety', severity: 'INFO', confidence: 0.9,
        why: 'LessLag World Chunk Guard has safe defaults (disabled by default)',
        impact: 'When enabled, aggressive chunk unloading can cause brief visual artifacts',
        tradeoff: 'Keep disabled unless experiencing chunk overload issues',
        recommendation: 'Leave world-guard disabled unless specifically needed for chunk overload',
        impactedKeys: ['modules.chunks.world-guard.enabled'],
    }));

    // ── heap size ────────────────────────────────────────────────
    const maxMb = hardware.maxHeapMB;
    if (maxMb < 2048) {
        results.push(buildRuleResult('safety-low-heap', {
            group: 'safety', severity: 'CRITICAL', confidence: 0.95,
            why: `Server heap is only ${formatMB(maxMb)} — critically low`,
            impact: 'Frequent GC pauses, out-of-memory crashes, poor TPS under any load',
            tradeoff: 'Increasing heap requires more physical RAM on the host',
            recommendation: 'Allocate at least 4GB heap (-Xmx4G). Paper Chan: 10GB is sufficient for most servers. Set -Xms equal to -Xmx',
            manualSteps: 'Edit your startup script: change -Xmx to at least 4G and set -Xms equal to -Xmx',
        }));
    } else if (maxMb < 4096) {
        results.push(buildRuleResult('safety-moderate-heap', {
            group: 'safety', severity: 'WARNING', confidence: 0.8,
            why: `Server heap is ${formatMB(maxMb)} — sufficient for small servers only`,
            impact: 'May experience GC pressure with 20+ players or large worlds',
            tradeoff: 'More heap = better headroom but requires available host RAM. Paper Chan: 10GB is sufficient for most servers',
            recommendation: 'Consider 6-10GB for 20+ concurrent players. Set -Xms equal to -Xmx',
            manualSteps: 'Edit your startup script: change -Xmx to 6G-10G and set -Xms to the same value',
        }));
    }

    // ── GC overhead ──────────────────────────────────────────────
    if (hardware.gcOverheadPercent > 15) {
        results.push(buildRuleResult('safety-gc-overhead', {
            group: 'safety', severity: 'WARNING', confidence: 0.85,
            why: `GC overhead is ${hardware.gcOverheadPercent.toFixed(1)}% — high`,
            impact: 'Server spending significant time on garbage collection instead of ticking',
            tradeoff: 'Switching GC algorithm may require JDK 17+ features',
            recommendation: "Paper Chan: use Aikar's flags for G1GC, or ZGC (-XX:+UseZGC) for Java 21+ (no extra tuning needed). Set -Xms equal to -Xmx",
            manualSteps: "For G1GC: use Aikar's flags (https://docs.papermc.io/paper/aikars-flags)\nFor ZGC (Java 21+): add -XX:+UseZGC -XX:+ZGenerational to start script\nAlways set -Xms equal to -Xmx",
        }));
    }

    // ── allow-flight ─────────────────────────────────────────────
    const allowFlight = getString(configs, 'server.properties', 'allow-flight', 'false');
    if (allowFlight.toLowerCase() === 'false') {
        results.push(buildRuleResult('safety-allow-flight', {
            group: 'safety', severity: 'WARNING', confidence: 0.9,
            why: 'allow-flight is false — Vanilla flight detection is unreliable',
            impact: "Players get kicked for 'flying' during normal gameplay (lag, elytra, jumping on boats/slimes). Paper Chan recommends always true",
            tradeoff: 'Use a proper anti-cheat plugin instead of Vanilla flight detection',
            recommendation: 'Set allow-flight=true in server.properties',
            manualSteps: 'In server.properties, set allow-flight=true',
            impactedKeys: ['server.properties:allow-flight'],
        }));
        proposals.push(buildPatch('server.properties', 'allow-flight', 'false', 'true',
            'LOW', 'RECOMMEND', 'safety-allow-flight',
            'Enable allow-flight to prevent false kicks (Paper Chan recommended)'));
    }

    // ── pause-when-empty ─────────────────────────────────────────
    const pause = getString(configs, 'server.properties', 'pause-when-empty-seconds', '60');
    if (pause !== '-1') {
        const val = parseInt(pause.trim(), 10) || 60;
        if (val >= 0) {
            results.push(buildRuleResult('safety-pause-when-empty', {
                group: 'safety', severity: 'INFO', confidence: 0.8,
                why: `pause-when-empty-seconds is ${val} — server pauses when empty`,
                impact: 'Can cause issues with scheduled tasks, cron-based backups, and plugins that expect the server to always be running',
                tradeoff: 'Saves resources when no players are online, but breaks some functionality',
                recommendation: 'Set pause-when-empty-seconds=-1 to disable',
                manualSteps: 'In server.properties, set pause-when-empty-seconds=-1',
                impactedKeys: ['server.properties:pause-when-empty-seconds'],
            }));
            proposals.push(buildPatch('server.properties', 'pause-when-empty-seconds', pause, '-1',
                'LOW', 'RECOMMEND', 'safety-pause-when-empty',
                'Disable pause-when-empty to prevent task/plugin issues (Paper Chan recommended)'));
        }
    }

    // ── low thread count ─────────────────────────────────────────
    if (hardware.availableProcessors > 0 && hardware.availableProcessors < 4) {
        results.push(buildRuleResult('safety-low-threads', {
            group: 'safety', severity: 'WARNING', confidence: 0.85,
            why: `Server has only ${hardware.availableProcessors} available processor(s) — Paper Chan recommends a minimum of 4 threads/cores`,
            impact: 'Modern Minecraft servers need at least 4 threads for main thread, chunk loading, networking, and GC',
            tradeoff: 'Consider upgrading hosting plan or dedicating more cores',
            recommendation: 'Use a host with at least 4 threads/cores',
        }));
    }

    // ── redstone max activations ─────────────────────────────────
    let recMaxActivations: number;
    switch (tier) {
        case 'LOW': recMaxActivations = 150; break;
        case 'HIGH': recMaxActivations = 350; break;
        default: recMaxActivations = 250; break;
    }
    if (level === 'AGGRESSIVE') recMaxActivations = Math.trunc(recMaxActivations * 0.6);
    proposals.push(buildPatch('config.yml', 'modules.redstone.max-activations-per-chunk',
        '250', String(recMaxActivations),
        'MEDIUM', 'LESSLAG_APPLY', 'safety-redstone',
        `Tune redstone activation limit for ${input.tier} hardware`));

    // ── breeding limits ───────────────────────────────────────────
    let recBreeding: number;
    switch (tier) {
        case 'LOW': recBreeding = 10; break;
        case 'HIGH': recBreeding = 25; break;
        default: recBreeding = 20; break;
    }
    if (profile === 'SKYBLOCK') recBreeding = Math.trunc(recBreeding * 0.7);
    proposals.push(buildPatch('config.yml', 'modules.breeding-limiter.max-animals-per-chunk',
        '20', String(recBreeding),
        'LOW', 'LESSLAG_APPLY', 'safety-breeding',
        `Set breeding limit for ${profile} / ${tier}`));
}
