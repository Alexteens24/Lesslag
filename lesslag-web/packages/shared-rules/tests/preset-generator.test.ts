import { describe, it, expect } from 'vitest';
import { generatePreset, applyLoadModifier } from '../src/engine/preset-generator.js';
import type { GameProfile, HardwareTier, AggressivenessLevel, ServerFork } from '../src/types/enums.js';
import { GameProfiles, HardwareTiers, AggressivenessLevels, GameProfileMeta, HardwareTierMeta, AggressivenessLevelMeta } from '../src/types/enums.js';

describe('preset-generator', () => {

  // ── All 36 base combos ──────────────────────────────────

  describe('generatePreset - all 36 combos', () => {
    const combos: [GameProfile, HardwareTier, AggressivenessLevel][] = [];
    for (const p of GameProfiles) {
      for (const t of HardwareTiers) {
        for (const a of AggressivenessLevels) {
          combos.push([p, t, a]);
        }
      }
    }

    for (const [profile, tier, aggressiveness] of combos) {
      it(`generates preset for ${profile}/${tier}/${aggressiveness}`, () => {
        const preset = generatePreset(profile, tier, aggressiveness);

        expect(preset).toBeDefined();
        expect(preset.label).toContain(GameProfileMeta[profile].displayName);
        expect(preset.label).toContain(AggressivenessLevelMeta[aggressiveness].displayName);
        expect(preset.description).toBeTruthy();
        expect(preset.gameProfile).toBe(profile);
        expect(preset.aggressiveness).toBe(aggressiveness);

        // ── Core keys ──
        const s = preset.settings;
        expect(s['server.view-distance']).toBeDefined();
        expect(s['server.simulation-distance']).toBeDefined();

        // Bukkit
        expect(s['bukkit.spawn-limits.monsters']).toBeDefined();
        expect(s['bukkit.spawn-limits.animals']).toBeDefined();
        expect(s['bukkit.spawn-limits.water-animals']).toBeDefined();
        expect(s['bukkit.spawn-limits.water-ambient']).toBeDefined();
        expect(s['bukkit.spawn-limits.ambient']).toBeDefined();
        expect(s['bukkit.ticks-per.monster-spawns']).toBeDefined();
        expect(s['bukkit.ticks-per.animal-spawns']).toBeDefined();

        // Spigot
        expect(s['spigot.mob-spawn-range']).toBeDefined();
        expect(s['spigot.entity-tracking-range.players']).toBeDefined();
        expect(s['spigot.entity-tracking-range.monsters']).toBeDefined();
        expect(s['spigot.merge-radius.item']).toBeDefined();
        expect(s['spigot.merge-radius.exp']).toBeDefined();
        expect(s['spigot.nerf-spawner-mobs']).toBeDefined();

        // Paper world defaults (default fork is paper)
        expect(s['paper-world.misc.redstone-implementation']).toBe('ALTERNATE_CURRENT');
        expect(s['paper-world.entities.spawning.per-player-mob-spawns']).toBe('true');
        expect(s['paper-world.entities.spawning.alt-item-despawn-rate.enabled']).toBe('true');
        expect(s['paper-world.chunks.prevent-moving-into-unloaded-chunks']).toBe('true');
        expect(s['paper-world.collisions.max-entity-collisions']).toBeDefined();
        expect(s['paper-world.collisions.fix-climbing-bypassing-cramming-rule']).toBe('true');
        expect(s['paper-world.environment.optimize-explosions']).toBeDefined();
        expect(s['paper-world.chunks.entity-per-chunk-save-limit.arrow']).toBeDefined();
        expect(s['paper-world.entities.tracking-range-y.enabled']).toBe('true');

        // Paper global
        expect(s['paper-global.chunk-system.worker-threads']).toBe('-1');
        expect(s['paper-global.item-validation.book-size.page-max']).toBeDefined();

        // LessLag
        expect(s['workload-limit-ms']).toBeDefined();
        expect(s['modules.redstone.max-activations-per-chunk']).toBeDefined();
        expect(s['modules.entities.chunk-limiter.max-entities-per-chunk']).toBeDefined();
        expect(s['modules.mob-ai.active-radius']).toBeDefined();
        expect(s['modules.mob-ai.update-interval']).toBeDefined();
        expect(s['modules.density-optimizer.limits.COW']).toBeDefined();
        expect(s['modules.density-optimizer.limits.SHEEP']).toBeDefined();
        expect(s['modules.density-optimizer.limits.PIG']).toBeDefined();
        expect(s['modules.density-optimizer.limits.CHICKEN']).toBeDefined();
        expect(s['modules.density-optimizer.limits.VILLAGER']).toBeDefined();
        expect(s['modules.breeding-limiter.max-animals-per-chunk']).toBeDefined();
        expect(s['modules.villager-optimizer.ai-restore-duration']).toBeDefined();
        expect(s['automation.thresholds.minor.tps']).toBeDefined();
        expect(s['automation.thresholds.moderate.tps']).toBeDefined();
        expect(s['automation.thresholds.critical.tps']).toBeDefined();
      });
    }
  });

  // ── Constraint validation ─────────────────────────────────

  describe('constraint validation', () => {
    it('view-distance >= simulation-distance', () => {
      for (const p of GameProfiles) {
        for (const t of HardwareTiers) {
          for (const a of AggressivenessLevels) {
            const s = generatePreset(p, t, a).settings;
            expect(Number(s['server.view-distance'])).toBeGreaterThanOrEqual(
              Number(s['server.simulation-distance'])
            );
          }
        }
      }
    });

    it('view-distance >= 5', () => {
      for (const p of GameProfiles) {
        for (const t of HardwareTiers) {
          for (const a of AggressivenessLevels) {
            const s = generatePreset(p, t, a).settings;
            expect(Number(s['server.view-distance'])).toBeGreaterThanOrEqual(5);
          }
        }
      }
    });

    it('simulation-distance >= 5', () => {
      for (const p of GameProfiles) {
        for (const t of HardwareTiers) {
          for (const a of AggressivenessLevels) {
            const s = generatePreset(p, t, a).settings;
            expect(Number(s['server.simulation-distance'])).toBeGreaterThanOrEqual(5);
          }
        }
      }
    });

    it('mob-spawn-range <= simulation-distance - 1', () => {
      for (const p of GameProfiles) {
        for (const t of HardwareTiers) {
          for (const a of AggressivenessLevels) {
            const s = generatePreset(p, t, a).settings;
            expect(Number(s['spigot.mob-spawn-range'])).toBeLessThanOrEqual(
              Number(s['server.simulation-distance']) - 1
            );
          }
        }
      }
    });

    it('max-entity-collisions >= 3 (Paper Chan minimum)', () => {
      for (const p of GameProfiles) {
        for (const t of HardwareTiers) {
          for (const a of AggressivenessLevels) {
            const s = generatePreset(p, t, a).settings;
            expect(Number(s['paper-world.collisions.max-entity-collisions'])).toBeGreaterThanOrEqual(3);
          }
        }
      }
    });

    it('LOW tier produces more aggressive values than HIGH tier', () => {
      for (const p of GameProfiles) {
        for (const a of AggressivenessLevels) {
          const low = generatePreset(p, 'LOW', a).settings;
          const high = generatePreset(p, 'HIGH', a).settings;
          expect(Number(low['server.view-distance'])).toBeLessThanOrEqual(
            Number(high['server.view-distance'])
          );
          expect(Number(low['bukkit.spawn-limits.monsters'])).toBeLessThanOrEqual(
            Number(high['bukkit.spawn-limits.monsters'])
          );
          expect(Number(low['modules.mob-ai.active-radius'])).toBeLessThanOrEqual(
            Number(high['modules.mob-ai.active-radius'])
          );
        }
      }
    });

    it('AGGRESSIVE is more restrictive than SAFE', () => {
      for (const p of GameProfiles) {
        for (const t of HardwareTiers) {
          const safe = generatePreset(p, t, 'SAFE').settings;
          const aggressive = generatePreset(p, t, 'AGGRESSIVE').settings;
          expect(Number(aggressive['bukkit.spawn-limits.monsters'])).toBeLessThanOrEqual(
            Number(safe['bukkit.spawn-limits.monsters'])
          );
          expect(Number(aggressive['modules.entities.chunk-limiter.max-entities-per-chunk'])).toBeLessThanOrEqual(
            Number(safe['modules.entities.chunk-limiter.max-entities-per-chunk'])
          );
        }
      }
    });

    it('despawn-ranges.hard tracks sim-dist when sim-dist < 10', () => {
      // LOW/AGGRESSIVE has sim-dist 5, should produce despawn-ranges
      const s = generatePreset('SMP', 'LOW', 'AGGRESSIVE').settings;
      const simDist = Number(s['server.simulation-distance']);
      if (simDist < 10) {
        const hard = Number(s['paper-world.entities.spawning.despawn-ranges.monster.hard']);
        expect(hard).toBe((simDist - 1) * 16);
      }
    });
  });

  // ── Specific value checks ─────────────────────────────────

  describe('specific value checks', () => {
    it('SMP/MID/BALANCED matches expected baseline', () => {
      const s = generatePreset('SMP', 'MID', 'BALANCED').settings;
      expect(Number(s['server.view-distance'])).toBe(10);
      expect(Number(s['server.simulation-distance'])).toBe(8);
      expect(Number(s['bukkit.spawn-limits.monsters'])).toBe(35);
      expect(Number(s['bukkit.spawn-limits.animals'])).toBe(8);
      expect(Number(s['bukkit.spawn-limits.ambient'])).toBe(1);
      expect(s['bukkit.ticks-per.animal-spawns']).toBe('400');
      expect(s['spigot.merge-radius.item']).toBe('-1');
    });

    it('SKYBLOCK profile has lower density limits than SMP', () => {
      const skyblock = generatePreset('SKYBLOCK', 'MID', 'BALANCED').settings;
      const smp = generatePreset('SMP', 'MID', 'BALANCED').settings;
      expect(Number(skyblock['modules.density-optimizer.limits.COW'])).toBeLessThanOrEqual(
        Number(smp['modules.density-optimizer.limits.COW'])
      );
    });

    it('SKYBLOCK enables nerf-spawner-mobs', () => {
      const s = generatePreset('SKYBLOCK', 'MID', 'BALANCED').settings;
      expect(s['spigot.nerf-spawner-mobs']).toBe('true');
    });

    it('CREATIVE profile has higher redstone limit', () => {
      const creative = generatePreset('CREATIVE', 'MID', 'BALANCED').settings;
      const smp = generatePreset('SMP', 'MID', 'BALANCED').settings;
      expect(Number(creative['modules.redstone.max-activations-per-chunk'])).toBeGreaterThan(
        Number(smp['modules.redstone.max-activations-per-chunk'])
      );
    });

    it('CREATIVE profile has higher view-distance', () => {
      const creative = generatePreset('CREATIVE', 'MID', 'BALANCED').settings;
      const smp = generatePreset('SMP', 'MID', 'BALANCED').settings;
      expect(Number(creative['server.view-distance'])).toBeGreaterThanOrEqual(
        Number(smp['server.view-distance'])
      );
    });

    it('Paper world defaults always include critical safety settings', () => {
      const s = generatePreset('SMP', 'MID', 'BALANCED').settings;
      expect(s['paper-world.entities.armor-stands.tick']).toBe('true');
      expect(s['paper-world.entities.armor-stands.do-collision-entity-lookups']).toBe('true');
      expect(s['paper-world.chunks.delay-chunk-unloads-by']).toBe('10s');
      expect(s['paper-world.chunks.max-auto-save-chunks-per-tick']).toBe('24');
    });

    it('description includes section headers', () => {
      const p = generatePreset('SMP', 'MID', 'BALANCED');
      expect(p.description).toContain('Server');
      expect(p.description).toContain('Bukkit');
      expect(p.description).toContain('Spigot');
      expect(p.description).toContain('Paper');
      expect(p.description).toContain('LessLag');
      expect(p.description).toContain('settings generated');
    });

    it('total settings count is substantial (>40)', () => {
      const p = generatePreset('SMP', 'MID', 'BALANCED');
      expect(Object.keys(p.settings).length).toBeGreaterThan(40);
    });
  });

  // ── Fork-specific generation ──────────────────────────────

  describe('fork-specific generation', () => {
    it('vanilla fork produces no Paper/Purpur/Pufferfish settings', () => {
      const s = generatePreset('SMP', 'MID', 'BALANCED', undefined, 'vanilla').settings;
      const paperKeys = Object.keys(s).filter(k => k.startsWith('paper-'));
      const purpurKeys = Object.keys(s).filter(k => k.startsWith('purpur.'));
      const puffKeys = Object.keys(s).filter(k => k.startsWith('pufferfish.'));
      expect(paperKeys.length).toBe(0);
      expect(purpurKeys.length).toBe(0);
      expect(puffKeys.length).toBe(0);
    });

    it('spigot fork produces no Paper settings', () => {
      const s = generatePreset('SMP', 'MID', 'BALANCED', undefined, 'spigot').settings;
      const paperKeys = Object.keys(s).filter(k => k.startsWith('paper-'));
      expect(paperKeys.length).toBe(0);
    });

    it('paper fork includes Paper world + global settings', () => {
      const s = generatePreset('SMP', 'MID', 'BALANCED', undefined, 'paper').settings;
      const paperWorld = Object.keys(s).filter(k => k.startsWith('paper-world.'));
      const paperGlobal = Object.keys(s).filter(k => k.startsWith('paper-global.'));
      expect(paperWorld.length).toBeGreaterThan(10);
      expect(paperGlobal.length).toBeGreaterThan(0);
    });

    it('purpur fork includes Purpur + Paper settings', () => {
      const s = generatePreset('SMP', 'MID', 'BALANCED', undefined, 'purpur').settings;
      expect(s['purpur.mobs.villager.lobotomize.enabled']).toBeDefined();
      // Purpur is Paper-like, so Paper settings should be present
      expect(s['paper-world.misc.redstone-implementation']).toBe('ALTERNATE_CURRENT');
    });

    it('pufferfish fork includes Pufferfish + Paper settings', () => {
      const s = generatePreset('SMP', 'MID', 'BALANCED', undefined, 'pufferfish').settings;
      expect(s['pufferfish.dab.enabled']).toBe('true');
      expect(s['pufferfish.dab.start-distance']).toBeDefined();
      expect(s['paper-world.misc.redstone-implementation']).toBe('ALTERNATE_CURRENT');
    });

    it('default fork (omitted) behaves like paper', () => {
      const withFork = generatePreset('SMP', 'MID', 'BALANCED', undefined, 'paper');
      const withoutFork = generatePreset('SMP', 'MID', 'BALANCED');
      expect(Object.keys(withFork.settings).length).toBe(Object.keys(withoutFork.settings).length);
    });
  });

  // ── Player count scaling ──────────────────────────────────

  describe('player count scaling', () => {
    it('high player count reduces spawn limits', () => {
      const low = generatePreset('SMP', 'MID', 'BALANCED', 20).settings;
      const high = generatePreset('SMP', 'MID', 'BALANCED', 150).settings;
      expect(Number(high['bukkit.spawn-limits.monsters'])).toBeLessThanOrEqual(
        Number(low['bukkit.spawn-limits.monsters'])
      );
    });

    it('high player count reduces entity limits', () => {
      const low = generatePreset('SMP', 'HIGH', 'BALANCED', 20).settings;
      const high = generatePreset('SMP', 'HIGH', 'BALANCED', 100).settings;
      expect(Number(high['modules.entities.chunk-limiter.max-entities-per-chunk'])).toBeLessThanOrEqual(
        Number(low['modules.entities.chunk-limiter.max-entities-per-chunk'])
      );
    });

    it('description includes player count info', () => {
      const p = generatePreset('SMP', 'MID', 'BALANCED', 100);
      expect(p.description).toContain('100 players');
      expect(p.description).toContain('scale');
    });

    it('constraints hold at extreme player counts', () => {
      for (const count of [1, 50, 100, 200]) {
        const s = generatePreset('SMP', 'MID', 'BALANCED', count).settings;
        expect(Number(s['server.view-distance'])).toBeGreaterThanOrEqual(5);
        expect(Number(s['server.simulation-distance'])).toBeGreaterThanOrEqual(5);
        expect(Number(s['server.view-distance'])).toBeGreaterThanOrEqual(
          Number(s['server.simulation-distance'])
        );
        expect(Number(s['spigot.mob-spawn-range'])).toBeLessThanOrEqual(
          Number(s['server.simulation-distance']) - 1
        );
      }
    });
  });

  // ── Load modifier ─────────────────────────────────────────

  describe('applyLoadModifier', () => {
    it('returns same tier for moderate player count', () => {
      expect(applyLoadModifier('MID', 20)).toBe('MID');
    });

    it('downgrades HIGH to MID at 80+ players', () => {
      expect(applyLoadModifier('HIGH', 80)).toBe('MID');
    });

    it('downgrades MID to LOW at 50+ players', () => {
      expect(applyLoadModifier('MID', 50)).toBe('LOW');
    });

    it('generatePreset with playerCount applies load modifier', () => {
      const base = generatePreset('SMP', 'HIGH', 'BALANCED');
      const loaded = generatePreset('SMP', 'HIGH', 'BALANCED', 100);
      // With 100 players, HIGH should downgrade to MID
      expect(loaded.hardwareTier).toBe('MID');
      expect(Number(loaded.settings['server.view-distance'])).toBeLessThanOrEqual(
        Number(base.settings['server.view-distance'])
      );
    });
  });
});
