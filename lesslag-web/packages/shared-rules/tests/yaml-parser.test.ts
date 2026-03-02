import { describe, it, expect } from 'vitest';
import {
  parseProperties,
  parseSimpleYaml,
  parseConfig,
  serializeProperties,
  detectFormat,
} from '../src/util/yaml-parser';

describe('yaml-parser', () => {
  describe('parseProperties', () => {
    it('parses key=value pairs', () => {
      const text = `
# Minecraft server properties
view-distance=10
simulation-distance=8
online-mode=true
max-players=20
motd=A Minecraft Server
`;
      const result = parseProperties(text);
      expect(result['view-distance']).toBe(10);
      expect(result['simulation-distance']).toBe(8);
      expect(result['online-mode']).toBe(true);
      expect(result['max-players']).toBe(20);
      expect(result['motd']).toBe('A Minecraft Server');
    });

    it('skips comments and blank lines', () => {
      const result = parseProperties('# comment\n\nkey=value\n');
      expect(Object.keys(result)).toEqual(['key']);
    });

    it('handles value with = sign', () => {
      const result = parseProperties('motd=Welcome = Server');
      expect(result['motd']).toBe('Welcome = Server');
    });
  });

  describe('parseSimpleYaml', () => {
    it('parses flat YAML', () => {
      const text = `
spawn-limits:
  monsters: 70
  animals: 10
  ambient: 15
`;
      const result = parseSimpleYaml(text);
      expect(result['spawn-limits.monsters']).toBe(70);
      expect(result['spawn-limits.animals']).toBe(10);
      expect(result['spawn-limits.ambient']).toBe(15);
    });

    it('preserves nested paths', () => {
      const text = `
world-settings:
  default:
    simulation-distance: 10
    mob-spawn-range: 8
`;
      const result = parseSimpleYaml(text);
      expect(result['world-settings.default.simulation-distance']).toBe(10);
      expect(result['world-settings.default.mob-spawn-range']).toBe(8);
    });

    it('handles booleans', () => {
      const result = parseSimpleYaml('enabled: true\ndisabled: false');
      expect(result['enabled']).toBe(true);
      expect(result['disabled']).toBe(false);
    });

    it('handles quoted values', () => {
      const result = parseSimpleYaml("name: 'hello'\nother: \"world\"");
      expect(result['name']).toBe('hello');
      expect(result['other']).toBe('world');
    });

    it('strips inline comments', () => {
      const result = parseSimpleYaml('value: 10 # this is a comment');
      expect(result['value']).toBe(10);
    });
  });

  describe('detectFormat', () => {
    it('detects properties format', () => {
      expect(detectFormat('key=value\nkey2=value2')).toBe('properties');
    });

    it('detects YAML format', () => {
      expect(detectFormat('key: value\nkey2: value2')).toBe('yaml');
    });
  });

  describe('parseConfig', () => {
    it('auto-detects properties via filename', () => {
      const result = parseConfig('key=10', 'server.properties');
      expect(result['key']).toBe(10);
    });

    it('auto-detects YAML via filename', () => {
      const result = parseConfig('key: 10', 'bukkit.yml');
      expect(result['key']).toBe(10);
    });
  });

  describe('serializeProperties', () => {
    it('serializes config to key=value lines', () => {
      const text = serializeProperties({ 'view-distance': 10, 'online-mode': true });
      expect(text).toContain('view-distance=10');
      expect(text).toContain('online-mode=true');
    });
  });
});
