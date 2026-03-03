import { Hono } from 'hono';
import type { Env } from '../index';

// Bundled at build time by wrangler — resolveJsonModule is enabled in tsconfig
import benchmarkData from '../../../../packages/shared-rules/src/data/geekbench-sc.json';

interface BenchmarkEntry {
    model: string;
    normalized: string;
    score: number;
    tier: string;
}

const benchmarks: BenchmarkEntry[] = benchmarkData as BenchmarkEntry[];

// ─── Normalization ──────────────────────────────────────────

/** Strip junk from CPU model strings for fuzzy matching. */
function normalizeCpuModel(raw: string): string {
    return raw
        .replace(/\(R\)/gi, '')
        .replace(/\(TM\)/gi, '')
        .replace(/\bCPU\b/gi, '')
        .replace(/@\s*[\d.]+\s*GHz/gi, '')
        .replace(/[\d.]+\s*GHz/gi, '')
        .replace(/\s+/g, ' ')
        .trim()
        .toLowerCase();
}

// ─── Trigram Similarity ─────────────────────────────────────

function buildTrigrams(s: string): Set<string> {
    const set = new Set<string>();
    const padded = ` ${s} `;
    for (let i = 0; i < padded.length - 2; i++) {
        set.add(padded.slice(i, i + 3));
    }
    return set;
}

function jaccardSimilarity(a: Set<string>, b: Set<string>): number {
    let intersection = 0;
    for (const t of a) if (b.has(t)) intersection++;
    const union = a.size + b.size - intersection;
    return union === 0 ? 0 : intersection / union;
}

// ─── Route ──────────────────────────────────────────────────

export const benchmarksRoute = new Hono<{ Bindings: Env }>();

benchmarksRoute.get('/benchmarks/search', (c) => {
    const q = c.req.query('q');
    if (!q || q.trim().length < 2) {
        return c.json({ results: [] });
    }

    const normalizedQuery = normalizeCpuModel(q);
    const queryTrigrams = buildTrigrams(normalizedQuery);

    const scored = benchmarks.map((entry) => ({
        model: entry.model,
        score: entry.score,
        tier: entry.tier,
        similarity: jaccardSimilarity(queryTrigrams, buildTrigrams(entry.normalized)),
    }));

    const threshold = 0.2;
    const results = scored
        .filter((r) => r.similarity >= threshold)
        .sort((a, b) => b.similarity - a.similarity)
        .slice(0, 5);

    return c.json({ results });
});
