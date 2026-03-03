import { describe, it, expect } from 'vitest';
import { buildStartupCommand } from '../startup-command.js';

// ── ZGC threshold constants (must match startup-command.ts) ───────────────
const ZGC_MIN_JAVA = 21;
const ZGC_MIN_HEAP_MB = 16384;
const ZGC_MIN_BENCHMARK = 2000;

describe('buildStartupCommand', () => {
  // ── ZGC path ──────────────────────────────────────────────────────────

  describe('ZGC selection', () => {
    it('selects ZGC when all three thresholds are met', () => {
      const result = buildStartupCommand(ZGC_MIN_JAVA, ZGC_MIN_HEAP_MB, ZGC_MIN_BENCHMARK);
      expect(result.gcType).toBe('ZGC');
    });

    it('selects ZGC with headroom above thresholds', () => {
      const result = buildStartupCommand(23, 32768, 3500);
      expect(result.gcType).toBe('ZGC');
    });

    it('ZGC command contains -XX:+UseZGC', () => {
      const result = buildStartupCommand(21, 16384, 2000);
      expect(result.command).toContain('-XX:+UseZGC');
    });

    it('ZGC command contains -XX:+ZGenerational', () => {
      const result = buildStartupCommand(21, 16384, 2000);
      expect(result.command).toContain('-XX:+ZGenerational');
    });

    it('ZGC reason mentions Java version, heap and score', () => {
      const result = buildStartupCommand(21, 16384, 2000);
      expect(result.reason).toContain('21');
      expect(result.reason).toContain('16 GB');
      expect(result.reason).toContain('2000');
    });

    it('ZGC command includes heap size in -Xms and -Xmx', () => {
      const result = buildStartupCommand(21, 16384, 2000);
      expect(result.command).toContain('-Xms16384M');
      expect(result.command).toContain('-Xmx16384M');
    });
  });

  // ── G1GC fallback ──────────────────────────────────────────────────────

  describe('G1GC fallback', () => {
    it('falls back to G1GC when Java version is below threshold', () => {
      const result = buildStartupCommand(17, ZGC_MIN_HEAP_MB, ZGC_MIN_BENCHMARK);
      expect(result.gcType).toBe('G1GC');
    });

    it('falls back to G1GC when heap is below threshold', () => {
      const result = buildStartupCommand(ZGC_MIN_JAVA, 8192, ZGC_MIN_BENCHMARK);
      expect(result.gcType).toBe('G1GC');
    });

    it('falls back to G1GC when benchmark score is below threshold', () => {
      const result = buildStartupCommand(ZGC_MIN_JAVA, ZGC_MIN_HEAP_MB, 1500);
      expect(result.gcType).toBe('G1GC');
    });

    it('falls back to G1GC when benchmark score is 0 (unknown)', () => {
      const result = buildStartupCommand(ZGC_MIN_JAVA, ZGC_MIN_HEAP_MB, 0);
      expect(result.gcType).toBe('G1GC');
    });

    it('G1GC command contains -XX:+UseG1GC', () => {
      const result = buildStartupCommand(17, 8192, 0);
      expect(result.command).toContain('-XX:+UseG1GC');
    });

    it('G1GC command includes heap size in -Xms and -Xmx', () => {
      const result = buildStartupCommand(17, 8192, 0);
      expect(result.command).toContain('-Xms8192M');
      expect(result.command).toContain('-Xmx8192M');
    });

    it('G1GC reason explains why ZGC was not chosen', () => {
      const result = buildStartupCommand(17, 8192, 0);
      expect(result.reason).toContain('17 <');
      expect(result.reason).toContain('G1GC');
    });
  });

  // ── Large-heap G1GC adjustments ────────────────────────────────────────

  describe('large-heap G1GC adjustments (>= 12 GB)', () => {
    it('applies large-heap adjustments when heap >= 12288 MB', () => {
      const result = buildStartupCommand(17, 12288, 0);
      expect(result.gcType).toBe('G1GC');
      // Large-heap: G1HeapRegionSize goes from 8M → 16M
      expect(result.command).toContain('G1HeapRegionSize=16M');
    });

    it('does NOT apply large-heap adjustments when heap < 12288 MB', () => {
      const result = buildStartupCommand(17, 8192, 0);
      expect(result.command).toContain('G1HeapRegionSize=8M');
      expect(result.command).not.toContain('G1HeapRegionSize=16M');
    });

    it('reason mentions large-heap adjustments for 12 GB+', () => {
      const result = buildStartupCommand(17, 12288, 0);
      expect(result.reason).toContain('12 GB');
    });
  });

  // ── Command format ─────────────────────────────────────────────────────

  describe('command format', () => {
    it('command starts with "java "', () => {
      const gcResult = buildStartupCommand(17, 8192, 0);
      const zgcResult = buildStartupCommand(21, 16384, 2000);
      expect(gcResult.command.startsWith('java ')).toBe(true);
      expect(zgcResult.command.startsWith('java ')).toBe(true);
    });

    it('command ends with "-jar server.jar --nogui"', () => {
      const gcResult = buildStartupCommand(17, 8192, 0);
      const zgcResult = buildStartupCommand(21, 16384, 2000);
      expect(gcResult.command.endsWith('-jar server.jar --nogui')).toBe(true);
      expect(zgcResult.command.endsWith('-jar server.jar --nogui')).toBe(true);
    });

    it('result has command, gcType, and reason fields', () => {
      const result = buildStartupCommand(17, 8192, 0);
      expect(typeof result.command).toBe('string');
      expect(typeof result.gcType).toBe('string');
      expect(typeof result.reason).toBe('string');
      expect(result.command.length).toBeGreaterThan(0);
      expect(result.reason.length).toBeGreaterThan(0);
    });
  });
});
