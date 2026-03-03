import { describe, it, expect } from 'vitest';
import { decodeServerPayloadSync } from '../decode-payload.js';

// ── Helpers ────────────────────────────────────────────────────────────────

/** Encode a plain JSON string as base64url (uncompressed, for sync decode). */
function toBase64url(json: string): string {
  const b64 = btoa(json);
  return b64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '');
}

/** A minimal valid ServerPayload JSON object. */
const VALID_PAYLOAD_OBJ = {
  cpuModel: 'AMD Ryzen 9 5900X 12-Core',
  cores: 12,
  maxHeapMb: 8192,
  physicalRamMb: 32768,
  javaVersion: 21,
  jvmFlags: ['-XX:+UseG1GC', '-Xmx8192M'],
  fork: 'paper',
  mcVersion: '1.21.4',
  pluginNames: ['LessLag', 'EssentialsX'],
  tps: 19.8,
  mspt: 12.5,
};

const VALID_TOKEN = toBase64url(JSON.stringify(VALID_PAYLOAD_OBJ));

// ── Tests ──────────────────────────────────────────────────────────────────

describe('decodeServerPayloadSync', () => {
  // ── Valid payload ────────────────────────────────────────────────────────

  describe('valid payloads', () => {
    it('decodes a valid base64url token successfully', () => {
      const result = decodeServerPayloadSync(VALID_TOKEN);
      expect(result).not.toBeNull();
    });

    it('maps cpuModel correctly', () => {
      const result = decodeServerPayloadSync(VALID_TOKEN)!;
      expect(result.cpuModel).toBe('AMD Ryzen 9 5900X 12-Core');
    });

    it('maps cores correctly', () => {
      const result = decodeServerPayloadSync(VALID_TOKEN)!;
      expect(result.cores).toBe(12);
    });

    it('maps maxHeapMb correctly', () => {
      const result = decodeServerPayloadSync(VALID_TOKEN)!;
      expect(result.maxHeapMb).toBe(8192);
    });

    it('maps javaVersion correctly', () => {
      const result = decodeServerPayloadSync(VALID_TOKEN)!;
      expect(result.javaVersion).toBe(21);
    });

    it('maps fork correctly', () => {
      const result = decodeServerPayloadSync(VALID_TOKEN)!;
      expect(result.fork).toBe('paper');
    });

    it('maps mcVersion correctly', () => {
      const result = decodeServerPayloadSync(VALID_TOKEN)!;
      expect(result.mcVersion).toBe('1.21.4');
    });

    it('maps tps and mspt correctly', () => {
      const result = decodeServerPayloadSync(VALID_TOKEN)!;
      expect(result.tps).toBe(19.8);
      expect(result.mspt).toBe(12.5);
    });

    it('preserves pluginNames array', () => {
      const result = decodeServerPayloadSync(VALID_TOKEN)!;
      expect(result.pluginNames).toEqual(['LessLag', 'EssentialsX']);
    });

    it('preserves jvmFlags array', () => {
      const result = decodeServerPayloadSync(VALID_TOKEN)!;
      expect(result.jvmFlags).toEqual(['-XX:+UseG1GC', '-Xmx8192M']);
    });

    it('defaults physicalRamMb to 0 when omitted', () => {
      const noRam = { ...VALID_PAYLOAD_OBJ };
      delete (noRam as Record<string, unknown>).physicalRamMb;
      const token = toBase64url(JSON.stringify(noRam));
      const result = decodeServerPayloadSync(token)!;
      expect(result.physicalRamMb).toBe(0);
    });

    it('defaults pluginNames to [] when omitted', () => {
      const noPlugins = { ...VALID_PAYLOAD_OBJ };
      delete (noPlugins as Record<string, unknown>).pluginNames;
      const token = toBase64url(JSON.stringify(noPlugins));
      const result = decodeServerPayloadSync(token)!;
      expect(result.pluginNames).toEqual([]);
    });

    it('defaults jvmFlags to [] when omitted', () => {
      const noFlags = { ...VALID_PAYLOAD_OBJ };
      delete (noFlags as Record<string, unknown>).jvmFlags;
      const token = toBase64url(JSON.stringify(noFlags));
      const result = decodeServerPayloadSync(token)!;
      expect(result.jvmFlags).toEqual([]);
    });
  });

  // ── Invalid payloads ──────────────────────────────────────────────────────

  describe('invalid payloads return null', () => {
    it('returns null for an empty string', () => {
      expect(decodeServerPayloadSync('')).toBeNull();
    });

    it('returns null for random garbage input', () => {
      expect(decodeServerPayloadSync('!!!not-base64!!!')).toBeNull();
    });

    it('returns null when base64 decodes to non-JSON', () => {
      const token = toBase64url('this is not json at all');
      expect(decodeServerPayloadSync(token)).toBeNull();
    });

    it('returns null when cpuModel is missing', () => {
      const bad = { ...VALID_PAYLOAD_OBJ };
      delete (bad as Record<string, unknown>).cpuModel;
      expect(decodeServerPayloadSync(toBase64url(JSON.stringify(bad)))).toBeNull();
    });

    it('returns null when fork is missing', () => {
      const bad = { ...VALID_PAYLOAD_OBJ };
      delete (bad as Record<string, unknown>).fork;
      expect(decodeServerPayloadSync(toBase64url(JSON.stringify(bad)))).toBeNull();
    });

    it('returns null when mcVersion is missing', () => {
      const bad = { ...VALID_PAYLOAD_OBJ };
      delete (bad as Record<string, unknown>).mcVersion;
      expect(decodeServerPayloadSync(toBase64url(JSON.stringify(bad)))).toBeNull();
    });

    it('returns null when cores is a string instead of number', () => {
      const bad = { ...VALID_PAYLOAD_OBJ, cores: '12' };
      expect(decodeServerPayloadSync(toBase64url(JSON.stringify(bad)))).toBeNull();
    });

    it('returns null when maxHeapMb is missing', () => {
      const bad = { ...VALID_PAYLOAD_OBJ };
      delete (bad as Record<string, unknown>).maxHeapMb;
      expect(decodeServerPayloadSync(toBase64url(JSON.stringify(bad)))).toBeNull();
    });

    it('returns null when javaVersion is missing', () => {
      const bad = { ...VALID_PAYLOAD_OBJ };
      delete (bad as Record<string, unknown>).javaVersion;
      expect(decodeServerPayloadSync(toBase64url(JSON.stringify(bad)))).toBeNull();
    });

    it('returns null when tps is missing', () => {
      const bad = { ...VALID_PAYLOAD_OBJ };
      delete (bad as Record<string, unknown>).tps;
      expect(decodeServerPayloadSync(toBase64url(JSON.stringify(bad)))).toBeNull();
    });

    it('returns null when mspt is missing', () => {
      const bad = { ...VALID_PAYLOAD_OBJ };
      delete (bad as Record<string, unknown>).mspt;
      expect(decodeServerPayloadSync(toBase64url(JSON.stringify(bad)))).toBeNull();
    });

    it('returns null for a JSON null value', () => {
      expect(decodeServerPayloadSync(toBase64url('null'))).toBeNull();
    });

    it('returns null for a JSON array (not an object)', () => {
      expect(decodeServerPayloadSync(toBase64url('[]'))).toBeNull();
    });
  });
});
