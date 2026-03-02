import { describe, it, expect } from 'vitest';
import {
  generateDiffs,
  groupDiffsByFile,
  renderFileDiff,
  renderFullDiff,
  applyDiffsToConfig,
} from '../src/engine/diff-generator';
import { buildPatch } from '../src/types/rule-result';

const proposals = [
  buildPatch('server.properties', 'view-distance', '10', '8', 'LOW', 'RECOMMEND', 'test-1', 'Reduce view distance'),
  buildPatch('server.properties', 'allow-flight', 'false', 'true', 'LOW', 'RECOMMEND', 'test-2', 'Enable allow flight'),
  buildPatch('bukkit.yml', 'spawn-limits.monsters', '70', '35', 'MEDIUM', 'RECOMMEND', 'test-3', 'Reduce monsters'),
  buildPatch('config.yml', 'modules.mob-ai.active-radius', '40', '40', 'LOW', 'LESSLAG_APPLY', 'test-same', 'No change'),
];

describe('diff-generator', () => {
  describe('generateDiffs', () => {
    it('filters out unchanged values', () => {
      const diffs = generateDiffs(proposals);
      expect(diffs.length).toBe(3);
      expect(diffs.find(d => d.ruleId === 'test-same')).toBeUndefined();
    });

    it('maps all fields correctly', () => {
      const diffs = generateDiffs(proposals);
      const d = diffs.find(d => d.ruleId === 'test-1')!;
      expect(d.file).toBe('server.properties');
      expect(d.key).toBe('view-distance');
      expect(d.before).toBe('10');
      expect(d.after).toBe('8');
      expect(d.risk).toBe('LOW');
      expect(d.scope).toBe('RECOMMEND');
      expect(d.rationale).toBe('Reduce view distance');
    });
  });

  describe('groupDiffsByFile', () => {
    it('groups diffs by target file', () => {
      const diffs = generateDiffs(proposals);
      const grouped = groupDiffsByFile(diffs);
      expect(grouped.size).toBe(2);
      expect(grouped.get('server.properties')!.length).toBe(2);
      expect(grouped.get('bukkit.yml')!.length).toBe(1);
    });
  });

  describe('renderFileDiff', () => {
    it('renders diff text with + and - markers', () => {
      const diffs = generateDiffs(proposals).filter(d => d.file === 'server.properties');
      const text = renderFileDiff('server.properties', diffs);
      expect(text).toContain('# server.properties');
      expect(text).toContain('- view-distance: 10');
      expect(text).toContain('+ view-distance: 8');
      expect(text).toContain('- allow-flight: false');
      expect(text).toContain('+ allow-flight: true');
    });
  });

  describe('renderFullDiff', () => {
    it('renders all files separated by ---', () => {
      const text = renderFullDiff(proposals);
      expect(text).toContain('# server.properties');
      expect(text).toContain('# bukkit.yml');
      expect(text).toContain('---');
    });
  });

  describe('applyDiffsToConfig', () => {
    it('applies diffs to config map', () => {
      const configs = {
        'server.properties': { 'view-distance': 10, 'allow-flight': false },
        'bukkit.yml': { 'spawn-limits.monsters': 70 },
      };
      const diffs = generateDiffs(proposals);
      const result = applyDiffsToConfig(configs, diffs);
      expect(result['server.properties']['view-distance']).toBe(8);
      expect(result['server.properties']['allow-flight']).toBe(true);
      expect(result['bukkit.yml']['spawn-limits.monsters']).toBe(35);
    });

    it('preserves original configs', () => {
      const configs = {
        'server.properties': { 'view-distance': 10 },
      };
      const diffs = generateDiffs(proposals);
      applyDiffsToConfig(configs, diffs);
      expect(configs['server.properties']['view-distance']).toBe(10);
    });
  });
});
