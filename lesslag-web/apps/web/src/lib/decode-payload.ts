import type { ServerPayload } from '@lesslag/shared-rules';

/**
 * Decode a server payload from a base64url-encoded, gzipped JSON string.
 *
 * The plugin builds payload as: JSON → gzip → base64url.
 * This function reverses that: base64url → gunzip → JSON → validate.
 */
export async function decodeServerPayload(token: string): Promise<ServerPayload | null> {
    try {
        // base64url → base64 → binary
        const base64 = token.replace(/-/g, '+').replace(/_/g, '/');
        const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4);
        const binary = atob(padded);
        const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0));

        // Try gunzip first; if that fails, try plain JSON (uncompressed fallback)
        let jsonStr: string;
        try {
            const ds = new DecompressionStream('gzip');
            const writer = ds.writable.getWriter();
            writer.write(bytes);
            writer.close();
            const reader = ds.readable.getReader();
            const chunks: Uint8Array[] = [];
            let done = false;
            while (!done) {
                const result = await reader.read();
                if (result.value) chunks.push(result.value);
                done = result.done;
            }
            const totalLength = chunks.reduce((sum, c) => sum + c.length, 0);
            const merged = new Uint8Array(totalLength);
            let offset = 0;
            for (const chunk of chunks) {
                merged.set(chunk, offset);
                offset += chunk.length;
            }
            jsonStr = new TextDecoder().decode(merged);
        } catch {
            // Fallback: assume it's uncompressed JSON
            jsonStr = new TextDecoder().decode(bytes);
        }

        const parsed = JSON.parse(jsonStr);
        return validatePayload(parsed);
    } catch {
        return null;
    }
}

/**
 * Synchronous decode for uncompressed base64url payloads (no gzip).
 * Falls back to this when DecompressionStream is not available.
 */
export function decodeServerPayloadSync(token: string): ServerPayload | null {
    try {
        const base64 = token.replace(/-/g, '+').replace(/_/g, '/');
        const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4);
        const jsonStr = atob(padded);
        const parsed = JSON.parse(jsonStr);
        return validatePayload(parsed);
    } catch {
        return null;
    }
}

/** Validate the shape of a parsed payload object. */
function validatePayload(obj: unknown): ServerPayload | null {
    if (!obj || typeof obj !== 'object') return null;
    const p = obj as Record<string, unknown>;

    // Required string fields
    if (typeof p.cpuModel !== 'string') return null;
    if (typeof p.fork !== 'string') return null;
    if (typeof p.mcVersion !== 'string') return null;

    // Required number fields
    if (typeof p.cores !== 'number') return null;
    if (typeof p.maxHeapMb !== 'number') return null;
    if (typeof p.javaVersion !== 'number') return null;
    if (typeof p.tps !== 'number') return null;
    if (typeof p.mspt !== 'number') return null;

    // Optional but typed fields
    const physicalRamMb = typeof p.physicalRamMb === 'number' ? p.physicalRamMb : 0;
    const jvmFlags = Array.isArray(p.jvmFlags) ? p.jvmFlags.filter((f): f is string => typeof f === 'string') : [];
    const pluginNames = Array.isArray(p.pluginNames) ? p.pluginNames.filter((n): n is string => typeof n === 'string') : [];

    return {
        cpuModel: p.cpuModel as string,
        cores: p.cores as number,
        maxHeapMb: p.maxHeapMb as number,
        physicalRamMb,
        javaVersion: p.javaVersion as number,
        jvmFlags,
        fork: p.fork as string,
        mcVersion: p.mcVersion as string,
        pluginNames,
        tps: p.tps as number,
        mspt: p.mspt as number,
    };
}
