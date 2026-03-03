/**
 * Geekbench Single-Core Benchmark Scraper
 *
 * Fetches the processor benchmarks page from Geekbench Browser,
 * parses the HTML table, normalizes CPU model names, derives
 * hardware tiers, and writes the result to geekbench-sc.json.
 *
 * Usage: pnpm tsx scripts/scrape-geekbench.ts
 */

import { writeFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));

const GEEKBENCH_URL = 'https://browser.geekbench.com/processor-benchmarks';
const OUTPUT_PATH = resolve(__dirname, '../packages/shared-rules/src/data/geekbench-sc.json');
const MIN_ENTRIES = 500;

interface BenchmarkEntry {
    model: string;
    normalized: string;
    score: number;
    tier: 'LOW' | 'MID' | 'HIGH';
}

/** Strip common junk from CPU model names for fuzzy matching. */
function normalizeModel(raw: string): string {
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

function deriveTier(score: number): 'LOW' | 'MID' | 'HIGH' {
    if (score >= 2000) return 'HIGH';
    if (score >= 1200) return 'MID';
    return 'LOW';
}

async function main() {
    console.log('Fetching Geekbench processor benchmarks...');
    const res = await fetch(GEEKBENCH_URL);
    if (!res.ok) {
        throw new Error(`Failed to fetch: ${res.status} ${res.statusText}`);
    }
    const html = await res.text();

    // Parse rows: each benchmark row is a <tr> with <td> cells
    // Pattern: <td class="name"><a ...>CPU Name</a></td> ... <td class="score">1234</td>
    const entries: BenchmarkEntry[] = [];
    const rowRegex = /<tr[^>]*>[\s\S]*?<td[^>]*class="name"[^>]*>[\s\S]*?<a[^>]*>([\s\S]*?)<\/a>[\s\S]*?<td[^>]*class="score"[^>]*>([\d,]+)<\/td>[\s\S]*?<\/tr>/gi;

    let match: RegExpExecArray | null;
    while ((match = rowRegex.exec(html)) !== null) {
        const model = match[1].replace(/<[^>]+>/g, '').trim();
        const score = parseInt(match[2].replace(/,/g, ''), 10);
        if (!model || isNaN(score)) continue;

        entries.push({
            model,
            normalized: normalizeModel(model),
            score,
            tier: deriveTier(score),
        });
    }

    // Deduplicate by normalized name, keeping highest score
    const deduped = new Map<string, BenchmarkEntry>();
    for (const entry of entries) {
        const existing = deduped.get(entry.normalized);
        if (!existing || entry.score > existing.score) {
            deduped.set(entry.normalized, entry);
        }
    }

    const result = Array.from(deduped.values()).sort((a, b) => b.score - a.score);

    console.log(`Parsed ${result.length} unique CPU entries.`);

    if (result.length < MIN_ENTRIES) {
        console.error(
            `ERROR: Only ${result.length} entries found (minimum ${MIN_ENTRIES}). ` +
            `Geekbench HTML structure may have changed. Aborting.`
        );
        process.exit(1);
    }

    writeFileSync(OUTPUT_PATH, JSON.stringify(result, null, 2) + '\n');
    console.log(`Written to ${OUTPUT_PATH}`);
}

main().catch((err) => {
    console.error('Scraper failed:', err);
    process.exit(1);
});
