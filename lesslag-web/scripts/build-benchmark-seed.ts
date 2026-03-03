/**
 * Build a curated Geekbench 6 Single-Core benchmark seed database.
 *
 * Scores are representative averages from browser.geekbench.com.
 * Used when the live scraper hasn't run yet, or to supplement it.
 *
 * Tier thresholds (Geekbench 6 SC):
 *   HIGH  ≥ 2400  — fast desktop / top-tier dedicated / Gen-4+ EPYC with high boost
 *   MID   1300–2399 — standard VPS, modern Xeon, EPYC Milan/Genoa base, Graviton 3
 *   LOW  < 1300  — entry VPS, old Xeons, Naples/Rome EPYC, budget shared hosting
 *
 * Run: pnpm tsx scripts/build-benchmark-seed.ts
 */

import { writeFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const OUTPUT = resolve(__dirname, '../packages/shared-rules/src/data/geekbench-sc.json');

interface Entry { model: string; normalized: string; score: number; tier: 'LOW' | 'MID' | 'HIGH' }

function norm(s: string): string {
  return s
    .replace(/\(R\)/gi, '').replace(/\(TM\)/gi, '').replace(/\bCPU\b/gi, '')
    .replace(/@\s*[\d.]+\s*GHz/gi, '').replace(/[\d.]+\s*GHz/gi, '')
    .replace(/\s+/g, ' ').trim().toLowerCase();
}

function tier(score: number): 'LOW' | 'MID' | 'HIGH' {
  if (score >= 2400) return 'HIGH';
  if (score >= 1300) return 'MID';
  return 'LOW';
}

function e(model: string, score: number): Entry {
  return { model, normalized: norm(model), score, tier: tier(score) };
}

// ─────────────────────────────────────────────────────────────────────────────
// AMD Ryzen 9000 (Zen 5) — flagship desktop / small dedicated
// ─────────────────────────────────────────────────────────────────────────────
const zen5 = [
  e('AMD Ryzen 9 9950X', 3900),
  e('AMD Ryzen 9 9950X 16-Core Processor', 3900),
  e('AMD Ryzen 9 9900X', 3700),
  e('AMD Ryzen 7 9700X', 3550),
  e('AMD Ryzen 5 9600X', 3400),
];

// ─────────────────────────────────────────────────────────────────────────────
// AMD Ryzen 7000 (Zen 4)
// ─────────────────────────────────────────────────────────────────────────────
const zen4 = [
  e('AMD Ryzen 9 7950X', 3200),
  e('AMD Ryzen 9 7950X3D', 3100),
  e('AMD Ryzen 9 7900X', 3050),
  e('AMD Ryzen 9 7900X3D', 2950),
  e('AMD Ryzen 9 7900', 2850),
  e('AMD Ryzen 7 7700X', 2950),
  e('AMD Ryzen 7 7700', 2800),
  e('AMD Ryzen 7 7800X3D', 2800),
  e('AMD Ryzen 5 7600X', 2800),
  e('AMD Ryzen 5 7600', 2700),
];

// ─────────────────────────────────────────────────────────────────────────────
// AMD Ryzen 5000 (Zen 3)
// ─────────────────────────────────────────────────────────────────────────────
const zen3 = [
  e('AMD Ryzen 9 5950X', 2450),
  e('AMD Ryzen 9 5900X', 2350),
  e('AMD Ryzen 9 5900', 2250),
  e('AMD Ryzen 7 5800X3D', 2300),
  e('AMD Ryzen 7 5800X', 2200),
  e('AMD Ryzen 7 5800', 2100),
  e('AMD Ryzen 5 5600X', 2100),
  e('AMD Ryzen 5 5600', 2000),
  e('AMD Ryzen 5 5500', 1850),
];

// ─────────────────────────────────────────────────────────────────────────────
// AMD Ryzen 3000 / 2000 (Zen 2 / Zen+)
// ─────────────────────────────────────────────────────────────────────────────
const zen2 = [
  e('AMD Ryzen 9 3900X', 1750),
  e('AMD Ryzen 9 3900XT', 1800),
  e('AMD Ryzen 7 3700X', 1650),
  e('AMD Ryzen 5 3600X', 1650),
  e('AMD Ryzen 5 3600', 1600),
  e('AMD Ryzen 7 2700X', 1350),
  e('AMD Ryzen 5 2600X', 1300),
  e('AMD Ryzen 5 2600', 1250),
];

// ─────────────────────────────────────────────────────────────────────────────
// Intel Core 14th gen (Raptor Lake Refresh)
// ─────────────────────────────────────────────────────────────────────────────
const intel14 = [
  e('Intel Core i9-14900KS', 3550),
  e('Intel Core i9-14900K', 3400),
  e('Intel Core i9-14900F', 3300),
  e('Intel Core i7-14700K', 3100),
  e('Intel Core i7-14700KF', 3050),
  e('Intel Core i5-14600K', 2750),
  e('Intel Core i5-14600KF', 2700),
  e('Intel Core i5-14400', 2300),
];

// ─────────────────────────────────────────────────────────────────────────────
// Intel Core 13th gen (Raptor Lake)
// ─────────────────────────────────────────────────────────────────────────────
const intel13 = [
  e('Intel Core i9-13900KS', 3250),
  e('Intel Core i9-13900K', 3100),
  e('Intel Core i9-13900KF', 3100),
  e('Intel Core i9-13900F', 2950),
  e('Intel Core i7-13700K', 2850),
  e('Intel Core i7-13700KF', 2800),
  e('Intel Core i5-13600K', 2650),
  e('Intel Core i5-13600KF', 2600),
  e('Intel Core i5-13400', 2150),
  e('Intel Core i5-13400F', 2100),
  e('Intel Core i3-13100', 1950),
];

// ─────────────────────────────────────────────────────────────────────────────
// Intel Core 12th gen (Alder Lake)
// ─────────────────────────────────────────────────────────────────────────────
const intel12 = [
  e('Intel Core i9-12900KS', 2800),
  e('Intel Core i9-12900K', 2700),
  e('Intel Core i9-12900KF', 2650),
  e('Intel Core i9-12900F', 2550),
  e('Intel Core i7-12700K', 2500),
  e('Intel Core i7-12700KF', 2450),
  e('Intel Core i7-12700', 2350),
  e('Intel Core i5-12600K', 2350),
  e('Intel Core i5-12600KF', 2300),
  e('Intel Core i5-12400', 2000),
  e('Intel Core i5-12400F', 1950),
  e('Intel Core i3-12100', 1900),
  e('Intel Core i3-12100F', 1850),
];

// ─────────────────────────────────────────────────────────────────────────────
// Intel Core 11th gen (Rocket Lake)
// ─────────────────────────────────────────────────────────────────────────────
const intel11 = [
  e('Intel Core i9-11900K', 2250),
  e('Intel Core i9-11900KF', 2200),
  e('Intel Core i7-11700K', 2150),
  e('Intel Core i5-11600K', 2100),
  e('Intel Core i5-11400', 1900),
];

// ─────────────────────────────────────────────────────────────────────────────
// Intel Core 10th gen (Comet Lake)
// ─────────────────────────────────────────────────────────────────────────────
const intel10 = [
  e('Intel Core i9-10900K', 2000),
  e('Intel Core i9-10900KF', 1950),
  e('Intel Core i7-10700K', 1950),
  e('Intel Core i5-10600K', 1850),
  e('Intel Core i5-10400', 1650),
];

// ─────────────────────────────────────────────────────────────────────────────
// AMD EPYC Genoa (Zen 4 server) — common in Hetzner Dedicated, OVH Advance
// ─────────────────────────────────────────────────────────────────────────────
const epycGenoa = [
  e('AMD EPYC 9654', 2250),
  e('AMD EPYC 9554', 2400),
  e('AMD EPYC 9474F', 2500),
  e('AMD EPYC 9374F', 2550),
  e('AMD EPYC 9354P', 2600),
  e('AMD EPYC 9354', 2600),
  e('AMD EPYC 9254', 2350),
  e('AMD EPYC 9124', 2150),
];

// ─────────────────────────────────────────────────────────────────────────────
// AMD EPYC Milan (Zen 3 server) — very common in VPS providers
// Hetzner Cloud, Contabo (some), OVH Scale, DigitalOcean premium AMD
// ─────────────────────────────────────────────────────────────────────────────
const epycMilan = [
  e('AMD EPYC 7763', 1650),
  e('AMD EPYC 7713', 1600),
  e('AMD EPYC 7663', 1600),
  e('AMD EPYC 7543', 1700),
  e('AMD EPYC 7443', 1700),
  e('AMD EPYC 7443P', 1700),
  e('AMD EPYC 7313', 1650),
  e('AMD EPYC 7313P', 1650),
  e('AMD EPYC 75F3', 1800),
  e('AMD EPYC 74F3', 1850),
  e('AMD EPYC 73F3', 1900),
  e('AMD EPYC 72F3', 1950),
];

// ─────────────────────────────────────────────────────────────────────────────
// AMD EPYC Rome (Zen 2 server) — older Hetzner/OVH/Contabo VPS
// ─────────────────────────────────────────────────────────────────────────────
const epycRome = [
  e('AMD EPYC 7742', 1100),
  e('AMD EPYC 7702', 1100),
  e('AMD EPYC 7552', 1100),
  e('AMD EPYC 7532', 1050),
  e('AMD EPYC 7452', 1050),
  e('AMD EPYC 7351P', 1000),
  e('AMD EPYC 7282', 1050),
  // Rome "F" high-frequency variants
  e('AMD EPYC 7F72', 1400),
  e('AMD EPYC 7F52', 1450),
  e('AMD EPYC 7F32', 1500),
];

// ─────────────────────────────────────────────────────────────────────────────
// AMD EPYC Naples (Zen 1 server) — legacy
// ─────────────────────────────────────────────────────────────────────────────
const epycNaples = [
  e('AMD EPYC 7601', 850),
  e('AMD EPYC 7501', 800),
  e('AMD EPYC 7351', 950),
  e('AMD EPYC 7301', 900),
];

// ─────────────────────────────────────────────────────────────────────────────
// AMD EPYC 7B12 / 7B13 — cloud instance variants (AWS, GCP, Azure AMD)
// ─────────────────────────────────────────────────────────────────────────────
const epycCloud = [
  e('AMD EPYC 7B13', 1750),  // Hetzner CCX (dedicated vCPU)
  e('AMD EPYC 7B12', 1600),  // Contabo VPS
  e('AMD EPYC 7R52', 1300),  // AWS c5a
  e('AMD EPYC 7R32', 1250),  // AWS c5a small
];

// ─────────────────────────────────────────────────────────────────────────────
// Intel Xeon Scalable (Ice Lake / Sapphire Rapids) — enterprise VPS/dedicated
// ─────────────────────────────────────────────────────────────────────────────
const xeonScalable = [
  e('Intel Xeon Platinum 8490H', 2000),  // Sapphire Rapids
  e('Intel Xeon Platinum 8380', 1850),   // Ice Lake
  e('Intel Xeon Platinum 8358', 1800),
  e('Intel Xeon Gold 6438N', 2050),      // Sapphire Rapids
  e('Intel Xeon Gold 6354', 1900),
  e('Intel Xeon Gold 6338', 1900),
  e('Intel Xeon Gold 6314U', 1800),
  e('Intel Xeon Gold 6248R', 1350),
  e('Intel Xeon Gold 6226R', 1300),
  e('Intel Xeon Gold 6154', 1200),
  e('Intel Xeon Silver 4316', 1550),
  e('Intel Xeon Silver 4214R', 1150),
  e('Intel Xeon Silver 4210R', 1100),
];

// ─────────────────────────────────────────────────────────────────────────────
// Intel Xeon E-2400 / E-2300 series — popular for dedicated hosting (SYS, OVH)
// ─────────────────────────────────────────────────────────────────────────────
const xeonE2000 = [
  e('Intel Xeon E-2488G', 2000),
  e('Intel Xeon E-2468', 1950),
  e('Intel Xeon E-2388G', 1900),
  e('Intel Xeon E-2386G', 1850),
  e('Intel Xeon E-2378G', 1750),
  e('Intel Xeon E-2374G', 1700),
  e('Intel Xeon E-2356G', 1700),
  e('Intel Xeon E-2336', 1600),
  e('Intel Xeon E-2334', 1600),
  e('Intel Xeon E-2314', 1200),
  e('Intel Xeon E-2224G', 1150),
];

// ─────────────────────────────────────────────────────────────────────────────
// Intel Xeon E5 v3/v4 — still common in budget dedicated (OVH Eco, Hetzner EX)
// ─────────────────────────────────────────────────────────────────────────────
const xeonE5 = [
  e('Intel Xeon E5-2690 v4', 850),
  e('Intel Xeon E5-2680 v4', 800),
  e('Intel Xeon E5-2650 v4', 780),
  e('Intel Xeon E5-2640 v4', 760),
  e('Intel Xeon E5-2620 v4', 720),
  e('Intel Xeon E5-2697 v3', 830),
  e('Intel Xeon E5-2690 v3', 810),
  e('Intel Xeon E5-2680 v3', 790),
  e('Intel Xeon E5-2650 v3', 760),
];

// ─────────────────────────────────────────────────────────────────────────────
// Intel Xeon E3 v5/v6 — older budget dedicated
// ─────────────────────────────────────────────────────────────────────────────
const xeonE3 = [
  e('Intel Xeon E3-1270 v6', 1500),
  e('Intel Xeon E3-1245 v6', 1450),
  e('Intel Xeon E3-1240 v6', 1430),
  e('Intel Xeon E3-1231 v3', 1200),
  e('Intel Xeon E3-1246 v3', 1220),
];

// ─────────────────────────────────────────────────────────────────────────────
// Intel Xeon W (workstation) — high-end dedicated
// ─────────────────────────────────────────────────────────────────────────────
const xeonW = [
  e('Intel Xeon w9-3595X', 2800),
  e('Intel Xeon w7-3465X', 2500),
  e('Intel Xeon w5-3435X', 2400),
  e('Intel Xeon W-3375', 2000),
  e('Intel Xeon W-2295', 1950),
  e('Intel Xeon W-2275', 1850),
];

// ─────────────────────────────────────────────────────────────────────────────
// ARM / Graviton — common in AWS, Hetzner Ampere
// ─────────────────────────────────────────────────────────────────────────────
const arm = [
  e('AWS Graviton4 (Neoverse V2)', 2450),
  e('AWS Graviton3 (Neoverse V1)', 1950),
  e('AWS Graviton2 (Neoverse N1)', 1150),
  e('Ampere Altra Q80-30', 1600),
  e('Ampere Altra Q64-22', 1550),
  e('Ampere AmpereOne A192-32', 1700),
  e('Hetzner Ampere Altra (CAX)', 1600),
];

// ─────────────────────────────────────────────────────────────────────────────
// Apple Silicon — used in some Mac Studio servers
// ─────────────────────────────────────────────────────────────────────────────
const appleSilicon = [
  e('Apple M3 Max', 3900),
  e('Apple M3 Pro', 3800),
  e('Apple M3', 3500),
  e('Apple M2 Ultra', 3400),
  e('Apple M2 Pro', 3200),
  e('Apple M2 Max', 3100),
  e('Apple M2', 2900),
  e('Apple M1 Ultra', 2800),
  e('Apple M1 Pro', 2700),
  e('Apple M1 Max', 2650),
  e('Apple M1', 2400),
];

// ─────────────────────────────────────────────────────────────────────────────
// Common VPS CPU strings as reported by /proc/cpuinfo inside VMs
// ─────────────────────────────────────────────────────────────────────────────
const vpsStrings = [
  // Hetzner Cloud CX/CPX (shared EPYC)
  e('AMD EPYC 7003 Series (shared vCPU)', 1450),
  // Hetzner CCX dedicated VPS
  e('AMD EPYC-Milan Processor', 1650),
  e('AMD EPYC-Rome Processor', 1050),
  // Contabo
  e('AMD EPYC 7B12 Processor', 1600),
  // OVH / SYS
  e('Intel Xeon Gold 6230R', 1300),
  e('Intel Xeon Gold 6140', 1100),
  e('Intel Xeon E5645', 600),
  // DigitalOcean premium AMD
  e('DO-Premium-AMD', 1800),
  // Vultr high-frequency
  e('Intel Core Processor (Skylake, IBRS)', 1150),
];

// ─────────────────────────────────────────────────────────────────────────────
// AMD Threadripper (workstation/dedicated)
// ─────────────────────────────────────────────────────────────────────────────
const threadripper = [
  e('AMD Ryzen Threadripper PRO 7995WX', 3250),
  e('AMD Ryzen Threadripper PRO 7985WX', 3200),
  e('AMD Ryzen Threadripper PRO 5995WX', 2500),
  e('AMD Ryzen Threadripper PRO 5975WX', 2450),
  e('AMD Ryzen Threadripper PRO 3995WX', 1950),
  e('AMD Ryzen Threadripper 3990X', 1900),
  e('AMD Ryzen Threadripper 3970X', 1900),
  e('AMD Ryzen Threadripper 3960X', 1850),
];

// ─────────────────────────────────────────────────────────────────────────────
// Merge, deduplicate, sort
// ─────────────────────────────────────────────────────────────────────────────
const all = [
  ...zen5, ...zen4, ...zen3, ...zen2,
  ...intel14, ...intel13, ...intel12, ...intel11, ...intel10,
  ...epycGenoa, ...epycMilan, ...epycRome, ...epycNaples, ...epycCloud,
  ...xeonScalable, ...xeonE2000, ...xeonE5, ...xeonE3, ...xeonW,
  ...arm, ...appleSilicon, ...vpsStrings, ...threadripper,
];

const deduped = new Map<string, Entry>();
for (const entry of all) {
  const existing = deduped.get(entry.normalized);
  if (!existing || entry.score > existing.score) {
    deduped.set(entry.normalized, entry);
  }
}

const result = Array.from(deduped.values()).sort((a, b) => b.score - a.score);

const tierCounts = result.reduce((acc, e) => {
  acc[e.tier] = (acc[e.tier] ?? 0) + 1;
  return acc;
}, {} as Record<string, number>);

console.log(`Built ${result.length} unique CPU entries.`);
console.log('Tier distribution:', tierCounts);
console.log('Score range — HIGH:', Math.min(...result.filter(e => e.tier === 'HIGH').map(e => e.score)), '–',
  Math.max(...result.filter(e => e.tier === 'HIGH').map(e => e.score)));
console.log('Score range — MID: ', Math.min(...result.filter(e => e.tier === 'MID').map(e => e.score)), '–',
  Math.max(...result.filter(e => e.tier === 'MID').map(e => e.score)));
console.log('Score range — LOW: ', Math.min(...result.filter(e => e.tier === 'LOW').map(e => e.score)), '–',
  Math.max(...result.filter(e => e.tier === 'LOW').map(e => e.score)));

writeFileSync(OUTPUT, JSON.stringify(result, null, 2) + '\n');
console.log(`Written to ${OUTPUT}`);
