import { describe, it, expect } from 'vitest';
import { generatePreset, applyLoadModifier } from '../src/engine/preset-generator';
import type { GameProfile, HardwareTier, AggressivenessLevel } from '../src/types/enums';
import { GameProfiles, HardwareTiers, AggressivenessLevels, GameProfileMeta, HardwareTierMeta, AggressivenessLevelMeta } from '../src/types/enums';

describe('preset-generator', () => {
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
        expect(preset.label).toContain(HardwareTierMeta[tier].displayName);
        expect(preset.label).toContain(AggressivenessLevelMeta[aggressiveness].displayName);
        expect(preset.description).toBeTruthy();
        expect(preset.gameProfile).toBe(profile);
        expect(preset.hardwareTier).toBe(tier);
        expect(preset.aggressiveness).toBe(aggressiveness);

        // Validate all expected keys exist
        const s = preset.settings;
        expect(s['server.view-distance']).toBeDefined();
        expect(s['server.simulation-distance']).toBeDefined();
        expect(s['bukkit.spawn-limits.monsters']).toBeDefined();
        expect(s['bukkit.spawn-limits.animals']).toBeDefined();
        expect(s['bukkit.spawn-limits.ambient']).toBeDefined();
        expect(s['spigot.mob-spawn-range']).toBeDefined();
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

    it('LOW tier is more aggressive than HIGH tier at same aggressiveness', () => {
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
        }
      }
    });

    it('AGGRESSIVE aggressiveness is more restrictive than SAFE', () => {
      for (const p of GameProfiles) {
        for (const t of HardwareTiers) {
          const safe = generatePreset(p, t, 'SAFE').settings;
          const aggressive = generatePreset(p, t, 'AGGRESSIVE').settings;
          expect(Number(aggressive['bukkit.spawn-limits.monsters'])).toBeLessThanOrEqual(
            Number(safe['bukkit.spawn-limits.monsters'])
          );
        }
      }
    });
  });

  describe('specific value checks', () => {
    it('SMP/MID/BALANCED matches expected baseline', () => {
      const s = generatePreset('SMP', 'MID', 'BALANCED').settings;
      expect(Number(s['server.view-distance'])).toBe(10);
      expect(Number(s['server.simulation-distance'])).toBe(8);
      expect(Number(s['bukkit.spawn-limits.monsters'])).toBe(35);
      expect(Number(s['bukkit.spawn-limits.animals'])).toBe(8);
      expect(Number(s['bukkit.spawn-limits.ambient'])).toBe(1);
    });

    it('SKYBLOCK profile has lower density limits', () => {
      const skyblock = generatePreset('SKYBLOCK', 'MID', 'BALANCED').settings;
      const smp = generatePreset('SMP', 'MID', 'BALANCED').settings;
      expect(Number(skyblock['modules.density-optimizer.limits.COW'])).toBeLessThanOrEqual(
        Number(smp['modules.density-optimizer.limits.COW'])
      );
    });
  });

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
