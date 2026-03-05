/**
 * Geekbench Single-Core Benchmark Scraper (v2)
 *
 * Parses the Processor Benchmarks page from Geekbench Browser.
 * Can read from a local downloaded file OR fetch directly from the web.
 *
 * The page contains a static HTML table (rendered server-side), so no JS execution needed.
 * Table format:
 *   <td class='name'><a href='...'>CPU Name</a><div class='description'>3.8 GHz (8 cores)</div></td>
 *   <td class='score'>3347</td>
 *
 * Usage:
 *   # Parse a locally downloaded file:
 *   pnpm tsx scripts/scrape-geekbench.ts --file /path/to/processor-benchmarks
 *
 *   # Fetch directly from web:
 *   pnpm tsx scripts/scrape-geekbench.ts
 *
 *   # Fetch and save to a custom output path:
 *   pnpm tsx scripts/scrape-geekbench.ts --out /tmp/benchmarks.json
 */

import { readFileSync, writeFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));

const GEEKBENCH_URL = 'https://browser.geekbench.com/processor-benchmarks';
const DEFAULT_OUTPUT = resolve(__dirname, '../packages/shared-rules/src/data/geekbench-sc.json');

// ─── CLI args ────────────────────────────────────────────────
const args = process.argv.slice(2);
const fileArgIdx = args.indexOf('--file');
const outArgIdx = args.indexOf('--out');
const INPUT_FILE: string | null = fileArgIdx >= 0 ? args[fileArgIdx + 1] : null;
const OUTPUT_PATH: string = outArgIdx >= 0 ? args[outArgIdx + 1] : DEFAULT_OUTPUT;

// ─── Types ───────────────────────────────────────────────────
interface BenchmarkEntry {
    model: string;
    normalized: string;
    sc: number;
    mc: number | null;
    cores: number | null;
    clockGhz: number | null;
    tier: 'LOW' | 'MID' | 'HIGH';
}

// ─── Helpers ─────────────────────────────────────────────────
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

/**
 * Tier thresholds calibrated from actual Geekbench 6 SC scores (2025):
 *  - LOW  < 1300   → old Xeon E5/EPYC Naples/Rome, budget VPS
 *  - MID  1300–2399 → EPYC Milan, Graviton3, Zen3/Zen4 VPS, Rocket Lake
 *  - HIGH ≥ 2400   → Zen4/Zen5 desktop, Raptor Lake, i9-13900K+, Apple Silicon, Graviton4
 */
function deriveTier(sc: number): 'LOW' | 'MID' | 'HIGH' {
    if (sc >= 2400) return 'HIGH';
    if (sc >= 1300) return 'MID';
    return 'LOW';
}

function parseDescription(desc: string): { cores: number | null; clockGhz: number | null } {
    // "3.8 GHz (8 cores)" or "3.8 GHz (8 cores, 16 threads)"
    const clockMatch = desc.match(/([\d.]+)\s*GHz/i);
    const coresMatch = desc.match(/(\d+)\s*cores?/i);
    return {
        cores: coresMatch ? parseInt(coresMatch[1], 10) : null,
        clockGhz: clockMatch ? parseFloat(clockMatch[1]) : null,
    };
}

// ─── Parser ──────────────────────────────────────────────────

/**
 * Parse the single-core section of the Geekbench processor benchmarks HTML.
 *
 * The page structure is:
 *   <div id='single-core'>
 *     <table class='benchmark-chart-table' id='pc'>
 *       <tbody>
 *         <tr>
 *           <td class='name'>
 *             <a href='...'>CPU Name</a>
 *             <div class='description'>3.8 GHz (8 cores)</div>
 *           </td>
 *           <td class='score'>3347</td>
 *         </tr>
 *         ...
 *       </tbody>
 *     </table>
 *   </div>
 */
function parseHtml(html: string): BenchmarkEntry[] {
    const entries: BenchmarkEntry[] = [];

    // Extract the single-core section only
    const scSectionMatch = html.match(/id=['"]single-core['"][^>]*>([\s\S]*?)id=['"]multi-core['"]/);
    const mcSectionMatch = html.match(/id=['"]multi-core['"][^>]*>([\s\S]*)/);

    const scHtml = scSectionMatch ? scSectionMatch[1] : html;

    // Build multi-core score lookup by CPU name
    const mcScores = new Map<string, number>();
    if (mcSectionMatch) {
        const mcRowRegex = /<td\s+class=['"]name['"]>([\s\S]*?)<\/td>\s*<td\s+class=['"]score['"]>\s*(\d[\d,]*)\s*<\/td>/gi;
        let mcMatch: RegExpExecArray | null;
        while ((mcMatch = mcRowRegex.exec(mcSectionMatch[1])) !== null) {
            const nameHtml = mcMatch[1];
            const cleanName = nameHtml
                .replace(/<[^>]+>/g, ' ')
                .replace(/\s+/g, ' ')
                .trim()
                .split('\n')[0]
                .trim();
            const score = parseInt(mcMatch[2].replace(/,/g, ''), 10);
            if (cleanName && !isNaN(score)) {
                mcScores.set(cleanName, score);
            }
        }
    }

    // Parse single-core rows
    // Each row: <td class='name'>...<a href='...'>NAME</a>...<div class='description'>DESC</div>...</td><td class='score'>SCORE</td>
    const rowRegex = /<td\s+class=['"]name['"]>([\s\S]*?)<\/td>\s*<td\s+class=['"]score['"]>\s*(\d[\d,]*)\s*<\/td>/gi;
    let match: RegExpExecArray | null;

    while ((match = rowRegex.exec(scHtml)) !== null) {
        const nameHtml = match[1];
        const scoreStr = match[2];

        // Extract CPU name from <a> tag
        const aMatch = nameHtml.match(/<a[^>]*>\s*([\s\S]*?)\s*<\/a>/i);
        if (!aMatch) continue;
        const model = aMatch[1].replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').trim();
        if (!model) continue;

        // Extract description (clock + cores)
        const descMatch = nameHtml.match(/<div\s+class=['"]description['"]>([\s\S]*?)<\/div>/i);
        const desc = descMatch ? descMatch[1].replace(/<[^>]+>/g, '').trim() : '';
        const { cores, clockGhz } = parseDescription(desc);

        const sc = parseInt(scoreStr.replace(/,/g, ''), 10);
        if (isNaN(sc)) continue;

        entries.push({
            model,
            normalized: normalizeModel(model),
            sc,
            mc: mcScores.get(model) ?? null,
            cores,
            clockGhz,
            tier: deriveTier(sc),
        });
    }

    return entries;
}

function deduplicate(entries: BenchmarkEntry[]): BenchmarkEntry[] {
    const map = new Map<string, BenchmarkEntry>();
    for (const e of entries) {
        const existing = map.get(e.normalized);
        if (!existing || e.sc > existing.sc) {
            map.set(e.normalized, e);
        }
    }
    return Array.from(map.values()).sort((a, b) => b.sc - a.sc);
}

// ─── Main ────────────────────────────────────────────────────
async function main() {
    let html: string;

    if (INPUT_FILE) {
        console.log(`Reading from file: ${INPUT_FILE}`);
        html = readFileSync(INPUT_FILE, 'utf-8');
    } else {
        console.log(`Fetching ${GEEKBENCH_URL} ...`);
        const res = await fetch(GEEKBENCH_URL, {
            headers: {
                'User-Agent': 'Mozilla/5.0 (compatible; LessLag-Scraper/2.0)',
                'Accept-Language': 'en-US,en;q=0.9',
                'Accept': 'text/html,application/xhtml+xml',
            },
        });
        if (!res.ok) throw new Error(`HTTP ${res.status} ${res.statusText}`);
        html = await res.text();
        console.log(`Fetched ${html.length.toLocaleString()} bytes`);
    }

    const rawEntries = parseHtml(html);
    console.log(`Parsed ${rawEntries.length} raw entries from HTML`);

    if (rawEntries.length === 0) {
        console.error('ERROR: No entries parsed — HTML structure may have changed.');
        process.exit(1);
    }

    const result = deduplicate(rawEntries);
    console.log(`\nDeduped to ${result.length} unique CPUs`);

    // Print tier distribution
    const dist = { HIGH: 0, MID: 0, LOW: 0 };
    for (const e of result) dist[e.tier]++;
    console.log(`  HIGH (≥2400): ${dist.HIGH}`);
    console.log(`  MID  (1300–2399): ${dist.MID}`);
    console.log(`  LOW  (<1300): ${dist.LOW}`);

    // Show top 10 by SC
    console.log('\nTop 10 by Single-Core:');
    for (const e of result.slice(0, 10)) {
        const mc = e.mc != null ? ` | MC: ${e.mc}` : '';
        console.log(`  ${e.sc.toString().padStart(4)} SC${mc}  ${e.model}`);
    }

    writeFileSync(OUTPUT_PATH, JSON.stringify(result, null, 2) + '\n');
    console.log(`\nWritten to ${OUTPUT_PATH}`);
}

main().catch((err) => {
    console.error('Scraper failed:', err);
    process.exit(1);
});
