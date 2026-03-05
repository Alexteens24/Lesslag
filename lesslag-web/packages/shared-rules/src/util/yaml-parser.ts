/**
 * Simple YAML parser for Minecraft server config files.
 * Handles the flat key-value pairs commonly found in server.properties,
 * bukkit.yml, spigot.yml, paper-world-defaults.yml, etc.
 *
 * NOT a full YAML parser — just enough for MC server configs.
 */

/**
 * Parse a properties-style file (server.properties).
 * Lines like: key=value
 */
export function parseProperties(text: string): Record<string, string | number | boolean> {
  const result: Record<string, string | number | boolean> = {};
  for (const raw of text.split('\n')) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const eq = line.indexOf('=');
    if (eq < 0) continue;
    const key = line.slice(0, eq).trim();
    const val = line.slice(eq + 1).trim();
    result[key] = coerce(val);
  }
  return result;
}

/**
 * Parse a simplified YAML file. Handles:
 * - root.nested.key: value
 * - indented nested keys (2-space or 4-space)
 * Returns flattened dot-separated keys.
 */
export function parseSimpleYaml(text: string): Record<string, string | number | boolean> {
  const result: Record<string, string | number | boolean> = {};
  const stack: { indent: number; prefix: string }[] = [];

  for (const raw of text.split('\n')) {
    // Skip comments and blank lines
    if (raw.trim().startsWith('#') || !raw.trim()) continue;

    const indent = raw.length - raw.trimStart().length;
    const line = raw.trim();

    // Skip list items for now
    if (line.startsWith('- ')) continue;

    const colon = line.indexOf(':');
    if (colon < 0) continue;

    const key = line.slice(0, colon).trim();
    const valPart = line.slice(colon + 1).trim();

    // Pop stack entries with >= current indent
    while (stack.length > 0 && stack[stack.length - 1].indent >= indent) {
      stack.pop();
    }

    const prefix = stack.length > 0 ? stack[stack.length - 1].prefix + '.' + key : key;

    if (valPart === '' || valPart === '{}') {
      // This is a section header, push onto stack
      stack.push({ indent, prefix });
    } else {
      // Remove inline comments
      let cleanVal = valPart;
      const commentIdx = cleanVal.indexOf(' #');
      if (commentIdx >= 0) cleanVal = cleanVal.slice(0, commentIdx).trim();
      // Strip quotes
      if ((cleanVal.startsWith("'") && cleanVal.endsWith("'")) ||
        (cleanVal.startsWith('"') && cleanVal.endsWith('"'))) {
        cleanVal = cleanVal.slice(1, -1);
      }
      result[prefix] = coerce(cleanVal);
      // Also push onto stack in case nested keys follow
      stack.push({ indent, prefix: prefix });
    }
  }

  return result;
}

/**
 * Serialize a flat key/value map to properties format.
 */
export function serializeProperties(config: Record<string, string | number | boolean>): string {
  return Object.entries(config)
    .map(([k, v]) => `${k}=${v}`)
    .join('\n');
}

/**
 * Serialize a flat dot-key map to proper nested YAML.
 *
 * Input:  { "entities.spawning.per-player-mob-spawns": true, "misc.redstone-implementation": "ALTERNATE_CURRENT" }
 * Output:
 *   entities:
 *     spawning:
 *       per-player-mob-spawns: true
 *   misc:
 *     redstone-implementation: ALTERNATE_CURRENT
 *
 * This is the correct reverse of `parseSimpleYaml()`.
 */
export function serializeYaml(config: Record<string, string | number | boolean>): string {
  // Build nested object tree from flat dot-keys
  const tree: Record<string, unknown> = {};
  for (const [dotKey, value] of Object.entries(config)) {
    const parts = dotKey.split('.');
    let node: Record<string, unknown> = tree;
    for (let i = 0; i < parts.length - 1; i++) {
      const part = parts[i];
      if (node[part] == null || typeof node[part] !== 'object') {
        node[part] = {};
      }
      node = node[part] as Record<string, unknown>;
    }
    node[parts[parts.length - 1]] = value;
  }

  // Recursively render the tree to YAML
  return renderYamlNode(tree, 0);
}

function needsQuotes(val: string): boolean {
  // Quote values that contain YAML special chars or could be misinterpreted
  return (
    val.includes(':') ||
    val.includes('#') ||
    val.startsWith('{') ||
    val.startsWith('[') ||
    val.startsWith('*') ||
    val.startsWith('&') ||
    val.startsWith('!') ||
    val.startsWith('>') ||
    val.startsWith('|') ||
    val.startsWith('%') ||
    val === '' ||
    val === 'true' ||
    val === 'false' ||
    val === 'null' ||
    !isNaN(Number(val))
  );
}

function renderYamlValue(val: unknown): string {
  if (typeof val === 'boolean') return String(val);
  if (typeof val === 'number') return String(val);
  if (typeof val === 'string') {
    if (needsQuotes(val)) return `"${val.replace(/"/g, '\\"')}"`;
    return val;
  }
  return String(val);
}

function renderYamlNode(node: Record<string, unknown>, depth: number): string {
  const indent = '  '.repeat(depth);
  const lines: string[] = [];
  for (const [key, value] of Object.entries(node)) {
    if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
      lines.push(`${indent}${key}:`);
      lines.push(renderYamlNode(value as Record<string, unknown>, depth + 1));
    } else if (Array.isArray(value)) {
      lines.push(`${indent}${key}:`);
      for (const item of value) {
        lines.push(`${indent}  - ${renderYamlValue(item)}`);
      }
    } else {
      lines.push(`${indent}${key}: ${renderYamlValue(value)}`);
    }
  }
  return lines.join('\n');
}

/**
 * Detect whether a string looks like server.properties or YAML.
 */
export function detectFormat(text: string): 'properties' | 'yaml' {
  // server.properties uses = for assignment, YAML uses :
  const lines = text.split('\n').filter(l => l.trim() && !l.trim().startsWith('#'));
  let eqCount = 0;
  let colonCount = 0;
  for (const line of lines.slice(0, 20)) {
    if (line.includes('=')) eqCount++;
    if (line.includes(':')) colonCount++;
  }
  return eqCount > colonCount ? 'properties' : 'yaml';
}

/**
 * Auto-parse a config file based on format detection.
 */
export function parseConfig(text: string, filename?: string): Record<string, string | number | boolean> {
  if (filename?.endsWith('.properties')) return parseProperties(text);
  if (filename?.endsWith('.yml') || filename?.endsWith('.yaml')) return parseSimpleYaml(text);
  const format = detectFormat(text);
  return format === 'properties' ? parseProperties(text) : parseSimpleYaml(text);
}

function coerce(val: string): string | number | boolean {
  if (val === 'true') return true;
  if (val === 'false') return false;
  const n = Number(val);
  if (!isNaN(n) && val !== '') return n;
  return val;
}
