import { describe, it, expect } from 'vitest';
import { evaluate } from '../src/engine/evaluator.js';
import type { EvaluationInput } from '../src/types/config.js';

function makeInput(overrides: Partial<EvaluationInput> = {}): EvaluationInput {
  return {
    profile: 'SMP',
    tier: 'MID',
    aggressiveness: 'BALANCED',
    plugins: [],
    hardware: {
      availableProcessors: 8,
      cpuModel: 'Test CPU',
      maxHeapMB: 8192,
      gcOverheadPercent: 5,
      averageMspt: 30,
    },
    platform: {
      fork: 'paper',
      version: '1.21',
      isPaper: true,
      isPurpur: false,
      isPufferfish: false,
      isLeaf: false,
      hasFolia: false,
      isLuminol: false,
    },
    configs: {
      'server.properties': {
        'online-mode': 'true',
        'view-distance': 10,
        'simulation-distance': 10,
        'allow-flight': 'false',
        'pause-when-empty-seconds': '60',
      },
      'bukkit.yml': {
        'spawn-limits.monsters': 70,
        'spawn-limits.animals': 10,
        'spawn-limits.ambient': 15,
        'ticks-per.animal-spawns': 400,
      },
      'spigot.yml': {
        'world-settings.default.simulation-distance': 10,
        'world-settings.default.mob-spawn-range': 8,
        'world-settings.default.merge-radius.item': -1,
        'world-settings.default.merge-radius.exp': -1,
        'world-settings.default.entity-tracking-range.players': 128,
      },
      'config/paper-world-defaults.yml': {
        'misc.redstone-implementation': 'VANILLA',
        'entities.spawning.per-player-mob-spawns': true,
        'chunks.prevent-moving-into-unloaded-chunks': true,
        'collisions.max-entity-collisions': 8,
        'collisions.fix-climbing-bypassing-cramming-rule': true,
        'environment.optimize-explosions': false,
        'environment.treasure-maps.find-already-discovered.villager-trade': false,
        'feature-seeds.generate-random-seeds-for-all': false,
        'chunks.delay-chunk-unloads-by': '10s',
        'chunks.max-auto-save-chunks-per-tick': 24,
        'entities.spawning.alt-item-despawn-rate.enabled': false,
        'chunks.entity-per-chunk-save-limit.arrow': -1,
        'entities.armor-stands.tick': true,
        'entities.armor-stands.do-collision-entity-lookups': true,
        'entities.tracking-range-y.enabled': false,
      },
      'config/paper-global.yml': {
        'chunk-system.worker-threads': -1,
        'chunk-system.io-threads': -1,
        'item-validation.book-size.page-max': 2560,
        'item-validation.resolve-selectors-in-books': false,
      },
    },
    ...overrides,
  };
}

describe('evaluator', () => {
  describe('safety rules', () => {
    it('flags offline mode', () => {
      const input = makeInput({
        configs: {
          ...makeInput().configs,
          'server.properties': {
            ...makeInput().configs['server.properties'],
            'online-mode': 'false',
          },
        },
      });
      const { results } = evaluate(input);
      const rule = results.find(r => r.ruleId === 'safety-online-mode');
      expect(rule).toBeDefined();
      expect(rule!.severity).toBe('WARNING');
    });

    it('flags low heap', () => {
      const input = makeInput({
        hardware: { ...makeInput().hardware, maxHeapMB: 1024 },
      });
      const { results } = evaluate(input);
      const rule = results.find(r => r.ruleId === 'safety-low-heap');
      expect(rule).toBeDefined();
      expect(rule!.severity).toBe('CRITICAL');
    });

    it('flags moderate heap', () => {
      const input = makeInput({
        hardware: { ...makeInput().hardware, maxHeapMB: 3072 },
      });
      const { results } = evaluate(input);
      const rule = results.find(r => r.ruleId === 'safety-moderate-heap');
      expect(rule).toBeDefined();
      expect(rule!.severity).toBe('WARNING');
    });

    it('flags high GC overhead', () => {
      const input = makeInput({
        hardware: { ...makeInput().hardware, gcOverheadPercent: 25 },
      });
      const { results } = evaluate(input);
      const rule = results.find(r => r.ruleId === 'safety-gc-overhead');
      expect(rule).toBeDefined();
    });

    it('proposes allow-flight=true', () => {
      const { proposals } = evaluate(makeInput());
      const patch = proposals.find(p => p.configKey === 'allow-flight');
      expect(patch).toBeDefined();
      expect(patch!.afterValue).toBe('true');
    });

    it('flags low thread count', () => {
      const input = makeInput({
        hardware: { ...makeInput().hardware, availableProcessors: 2 },
      });
      const { results } = evaluate(input);
      expect(results.find(r => r.ruleId === 'safety-low-threads')).toBeDefined();
    });
  });

  describe('consistency rules', () => {
    it('flags view distance < sim distance', () => {
      const input = makeInput({
        configs: {
          ...makeInput().configs,
          'server.properties': {
            ...makeInput().configs['server.properties'],
            'view-distance': 6,
            'simulation-distance': 10,
          },
          'spigot.yml': {
            ...makeInput().configs['spigot.yml'],
            'world-settings.default.simulation-distance': 10,
          },
        },
      });
      const { results } = evaluate(input);
      expect(results.find(r => r.ruleId === 'consistency-view-sim')).toBeDefined();
    });

    it('flags high monster spawn limits', () => {
      const input = makeInput({
        configs: {
          ...makeInput().configs,
          'bukkit.yml': {
            ...makeInput().configs['bukkit.yml'],
            'spawn-limits.monsters': 100,
          },
        },
      });
      const { results } = evaluate(input);
      const rule = results.find(r => r.ruleId === 'consistency-spawn-limits');
      expect(rule).toBeDefined();
    });

    it('flags mob-spawn-range > sim-dist - 1', () => {
      const input = makeInput({
        configs: {
          ...makeInput().configs,
          'server.properties': {
            ...makeInput().configs['server.properties'],
            'simulation-distance': 6,
          },
          'spigot.yml': {
            ...makeInput().configs['spigot.yml'],
            'world-settings.default.simulation-distance': 6,
            'world-settings.default.mob-spawn-range': 8,
          },
        },
      });
      const { results } = evaluate(input);
      expect(results.find(r => r.ruleId === 'consistency-mob-spawn-range')).toBeDefined();
    });

    it('flags low animal tick interval', () => {
      const input = makeInput({
        configs: {
          ...makeInput().configs,
          'bukkit.yml': {
            ...makeInput().configs['bukkit.yml'],
            'ticks-per.animal-spawns': 100,
          },
        },
      });
      const { results } = evaluate(input);
      expect(results.find(r => r.ruleId === 'consistency-ticks-per-animals')).toBeDefined();
    });
  });

  describe('conflict rules', () => {
    it('flags ClearLag', () => {
      const input = makeInput({ plugins: ['ClearLag'] });
      const { results } = evaluate(input);
      expect(results.find(r => r.ruleId === 'conflict-clearlag')).toBeDefined();
    });

    it('flags mob stacker plugins', () => {
      const input = makeInput({ plugins: ['StackMob'] });
      const { results } = evaluate(input);
      expect(results.find(r => r.ruleId.startsWith('conflict-stacker-'))).toBeDefined();
    });

    it('flags Pufferfish DAB overlap', () => {
      const input = makeInput({ plugins: ['Pufferfish'] });
      const { results } = evaluate(input);
      expect(results.find(r => r.ruleId === 'conflict-pufferfish-dab')).toBeDefined();
    });

    it('flags silk-touch spawner plugins', () => {
      const input = makeInput({ plugins: ['SilkSpawners'] });
      const { results } = evaluate(input);
      expect(results.find(r => r.ruleId === 'conflict-silktouch-spawner')).toBeDefined();
    });

    it('flags farm control plugins', () => {
      const input = makeInput({ plugins: ['FarmControl'] });
      const { results } = evaluate(input);
      expect(results.find(r => r.ruleId.startsWith('conflict-farm-'))).toBeDefined();
    });
  });

  describe('fork-specific rules', () => {
    it('proposes ALTERNATE_CURRENT redstone', () => {
      const { proposals } = evaluate(makeInput());
      expect(proposals.find(p =>
        p.configKey === 'misc.redstone-implementation' && p.afterValue === 'ALTERNATE_CURRENT'
      )).toBeDefined();
    });

    it('proposes treasure maps fix', () => {
      const { proposals } = evaluate(makeInput());
      const patch = proposals.find(p =>
        p.configKey === 'environment.treasure-maps.find-already-discovered.villager-trade');
      expect(patch).toBeDefined();
      expect(patch!.afterValue).toBe('true');
    });

    it('proposes alt-item-despawn-rate', () => {
      const { proposals } = evaluate(makeInput());
      expect(proposals.find(p =>
        p.configKey === 'entities.spawning.alt-item-despawn-rate.enabled'
      )).toBeDefined();
    });

    it('flags despawn ranges when sim-dist < 10', () => {
      const input = makeInput({
        configs: {
          ...makeInput().configs,
          'server.properties': {
            ...makeInput().configs['server.properties'],
            'simulation-distance': 6,
          },
          'spigot.yml': {
            ...makeInput().configs['spigot.yml'],
            'world-settings.default.simulation-distance': 6,
          },
        },
      });
      const { results } = evaluate(input);
      expect(results.find(r => r.ruleId === 'paper-despawn-ranges')).toBeDefined();
    });

    it('flags book page-max > 1280', () => {
      const { proposals } = evaluate(makeInput());
      expect(proposals.find(p =>
        p.configKey === 'item-validation.book-size.page-max'
      )).toBeDefined();
    });
  });

  describe('performance tuning rules', () => {
    it('generates frustum culling proposals', () => {
      const { proposals } = evaluate(makeInput());
      expect(proposals.find(p => p.ruleId === 'perf-frustum-radius')).toBeDefined();
      expect(proposals.find(p => p.ruleId === 'perf-frustum-interval')).toBeDefined();
    });

    it('generates density optimizer proposals', () => {
      const { proposals } = evaluate(makeInput());
      expect(proposals.find(p => p.ruleId === 'perf-density-tuning')).toBeDefined();
    });

    it('generates TPS threshold proposals', () => {
      const { proposals } = evaluate(makeInput());
      const tps = proposals.filter(p => p.ruleId === 'perf-thresholds');
      expect(tps.length).toBeGreaterThanOrEqual(1);
    });
  });

  describe('output structure', () => {
    it('has summary with counts', () => {
      const output = evaluate(makeInput());
      expect(output.summary.totalResults).toBeGreaterThan(0);
      expect(output.summary.totalProposals).toBeGreaterThan(0);
      expect(output.summary.bySeverity).toBeDefined();
      expect(output.summary.byRisk).toBeDefined();
      expect(output.summary.autoApplicable).toBeTypeOf('number');
      expect(output.summary.recommendOnly).toBeTypeOf('number');
    });

    it('deduplicates proposals by file:key', () => {
      const output = evaluate(makeInput());
      const keys = output.proposals.map(p => `${p.targetFile}:${p.configKey}`);
      const unique = new Set(keys);
      expect(keys.length).toBe(unique.size);
    });

    it('all rule results have required fields', () => {
      const output = evaluate(makeInput());
      for (const r of output.results) {
        expect(r.ruleId).toBeTruthy();
        expect(r.ruleGroup).toBeTruthy();
        expect(r.severity).toMatch(/^(INFO|WARNING|CRITICAL)$/);
        expect(r.confidence).toBeGreaterThanOrEqual(0);
        expect(r.confidence).toBeLessThanOrEqual(1);
        expect(r.why).toBeTruthy();
      }
    });

    it('all proposals have required fields', () => {
      const output = evaluate(makeInput());
      for (const p of output.proposals) {
        expect(p.targetFile).toBeTruthy();
        expect(p.configKey).toBeTruthy();
        expect(p.riskTag).toMatch(/^(LOW|MEDIUM|HIGH)$/);
        expect(p.applyScope).toMatch(/^(RECOMMEND|LESSLAG_APPLY)$/);
        expect(p.ruleId).toBeTruthy();
      }
    });
  });
});
