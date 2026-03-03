import type { StartupCommandResult } from '@lesslag/shared-rules';

/**
 * Aikar's G1GC flags template.
 * Heap size placeholders are replaced at build time.
 */
const AIKAR_FLAGS = [
    '-Xms{heap}M',
    '-Xmx{heap}M',
    '-XX:+UseG1GC',
    '-XX:+ParallelRefProcEnabled',
    '-XX:MaxGCPauseMillis=200',
    '-XX:+UnlockExperimentalVMOptions',
    '-XX:+DisableExplicitGC',
    '-XX:+AlwaysPreTouch',
    '-XX:G1NewSizePercent=30',
    '-XX:G1MaxNewSizePercent=40',
    '-XX:G1HeapRegionSize=8M',
    '-XX:G1ReservePercent=20',
    '-XX:G1MixedGCCountTarget=4',
    '-XX:InitiatingHeapOccupancyPercent=15',
    '-XX:G1MixedGCLiveThresholdPercent=90',
    '-XX:G1RSetUpdatingPauseTimePercent=5',
    '-XX:SurvivorRatio=32',
    '-XX:+PerfDisableSharedMem',
    '-XX:MaxTenuringThreshold=1',
].join(' ');

/**
 * ZGC flags for Java 21+ with large heaps and fast CPUs.
 */
const ZGC_FLAGS = [
    '-Xms{heap}M',
    '-Xmx{heap}M',
    '-XX:+UseZGC',
    '-XX:+ZGenerational',
    '-XX:+AlwaysPreTouch',
    '-XX:+DisableExplicitGC',
    '-XX:+PerfDisableSharedMem',
].join(' ');

/** Minimum thresholds for recommending ZGC over G1GC. */
const ZGC_MIN_JAVA = 21;
const ZGC_MIN_HEAP_MB = 16384; // 16 GB
const ZGC_MIN_BENCHMARK = 2000;

/**
 * Build a recommended JVM startup command based on server hardware.
 *
 * @param javaVersion  Major Java version (e.g. 21, 17)
 * @param heapMb       Max heap in MB
 * @param benchmarkScore Geekbench single-core score (0 if unknown)
 * @returns StartupCommandResult with the command string, GC type, and reasoning
 */
export function buildStartupCommand(
    javaVersion: number,
    heapMb: number,
    benchmarkScore: number,
): StartupCommandResult {
    const useZGC =
        javaVersion >= ZGC_MIN_JAVA &&
        heapMb >= ZGC_MIN_HEAP_MB &&
        benchmarkScore >= ZGC_MIN_BENCHMARK;

    if (useZGC) {
        const command = `java ${ZGC_FLAGS.replace(/\{heap\}/g, String(heapMb))} -jar server.jar --nogui`;
        return {
            command,
            gcType: 'ZGC',
            reason:
                `Using ZGC — Java ${javaVersion} (≥${ZGC_MIN_JAVA}), ` +
                `heap ${Math.round(heapMb / 1024)} GB (≥${ZGC_MIN_HEAP_MB / 1024} GB), ` +
                `benchmark score ${benchmarkScore} (≥${ZGC_MIN_BENCHMARK}). ` +
                `ZGC provides ultra-low pause times ideal for large servers.`,
        };
    }

    // G1GC with Aikar's flags
    const reasons: string[] = [];
    if (javaVersion < ZGC_MIN_JAVA) {
        reasons.push(`Java ${javaVersion} < ${ZGC_MIN_JAVA}`);
    }
    if (heapMb < ZGC_MIN_HEAP_MB) {
        reasons.push(`heap ${Math.round(heapMb / 1024)} GB < ${ZGC_MIN_HEAP_MB / 1024} GB`);
    }
    if (benchmarkScore > 0 && benchmarkScore < ZGC_MIN_BENCHMARK) {
        reasons.push(`benchmark ${benchmarkScore} < ${ZGC_MIN_BENCHMARK}`);
    }

    // For G1GC with large heaps (12GB+), adjust specific flags
    let flags = AIKAR_FLAGS;
    if (heapMb >= 12288) {
        flags = flags
            .replace('G1NewSizePercent=30', 'G1NewSizePercent=40')
            .replace('G1MaxNewSizePercent=40', 'G1MaxNewSizePercent=50')
            .replace('G1HeapRegionSize=8M', 'G1HeapRegionSize=16M')
            .replace('G1ReservePercent=20', 'G1ReservePercent=15')
            .replace('InitiatingHeapOccupancyPercent=15', 'InitiatingHeapOccupancyPercent=20');
    }

    const command = `java ${flags.replace(/\{heap\}/g, String(heapMb))} -jar server.jar --nogui`;
    return {
        command,
        gcType: 'G1GC',
        reason:
            `Using Aikar's G1GC flags — ${reasons.join(', ')}. ` +
            `G1GC with Aikar's tuning is the battle-tested default for Minecraft servers.` +
            (heapMb >= 12288 ? ' Large-heap adjustments applied (12 GB+).' : ''),
    };
}
