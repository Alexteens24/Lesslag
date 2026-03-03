/**
 * Semantic version of the shared rule set.
 *
 * Kept in sync with {@code RuleEngine.RULES_VERSION} in the Java plugin.
 * The API health endpoint exposes this so the plugin can detect drift at
 * startup and warn operators when the major version differs.
 *
 * Increment rules:
 *   MAJOR – rule IDs renamed, removed, or evaluation semantics changed in a
 *            breaking way (plugin and API produce incompatible outputs)
 *   MINOR – new rules added, or non-breaking behaviour changes
 *   PATCH – documentation, comments, or cosmetic-only changes
 */
export const RULES_VERSION = '1.0.0';
