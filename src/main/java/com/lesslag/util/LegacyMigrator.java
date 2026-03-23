package com.lesslag.util;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Migrates legacy Bukkit color codes ({@code &x} / {@code §x}) in YAML config
 * files to their MiniMessage equivalents.
 *
 * <p>Runs automatically on plugin start when legacy codes are detected.
 * A {@code .bak} backup is created before any file is modified.
 */
public final class LegacyMigrator {

    /** Detects any {@code &x} or {@code §x} color/format code. */
    private static final Pattern LEGACY_PATTERN =
            Pattern.compile("[&§][0-9a-fk-orA-FK-OR]");

    /** Ordered map of legacy code → MiniMessage tag (lowercase codes). */
    private static final Map<String, String> CODE_MAP = new LinkedHashMap<>();

    static {
        // Colors
        CODE_MAP.put("&0", "<black>");
        CODE_MAP.put("&1", "<dark_blue>");
        CODE_MAP.put("&2", "<dark_green>");
        CODE_MAP.put("&3", "<dark_aqua>");
        CODE_MAP.put("&4", "<dark_red>");
        CODE_MAP.put("&5", "<dark_purple>");
        CODE_MAP.put("&6", "<gold>");
        CODE_MAP.put("&7", "<gray>");
        CODE_MAP.put("&8", "<dark_gray>");
        CODE_MAP.put("&9", "<blue>");
        CODE_MAP.put("&a", "<green>");
        CODE_MAP.put("&b", "<aqua>");
        CODE_MAP.put("&c", "<red>");
        CODE_MAP.put("&d", "<light_purple>");
        CODE_MAP.put("&e", "<yellow>");
        CODE_MAP.put("&f", "<white>");
        // Formatting
        CODE_MAP.put("&k", "<obfuscated>");
        CODE_MAP.put("&l", "<bold>");
        CODE_MAP.put("&m", "<strikethrough>");
        CODE_MAP.put("&n", "<underlined>");
        CODE_MAP.put("&o", "<italic>");
        CODE_MAP.put("&r", "<reset>");
    }

    private LegacyMigrator() {}

    /**
     * Scans all {@code .yml} files in {@code dataFolder} for legacy color codes.
     * Files containing them are backed up ({@code <name>.yml.bak}) and rewritten
     * with MiniMessage equivalents.
     *
     * @param dataFolder plugin data folder (e.g. {@code plugins/LessLag/})
     * @param logger     plugin logger for reporting
     * @return number of files that were migrated
     */
    public static int migrateFolder(File dataFolder, Logger logger) {
        if (!dataFolder.isDirectory()) return 0;

        File[] ymls = dataFolder.listFiles(f -> f.isFile() && f.getName().endsWith(".yml"));
        if (ymls == null) return 0;

        int migratedCount = 0;
        for (File yml : ymls) {
            try {
                if (migrateFile(yml, logger)) {
                    migratedCount++;
                }
            } catch (IOException e) {
                logger.warning("[LegacyMigrator] Failed to migrate " + yml.getName() + ": " + e.getMessage());
            }
        }
        return migratedCount;
    }

    /**
     * Migrates a single YAML file in-place.
     *
     * @return {@code true} if the file was modified
     */
    public static boolean migrateFile(File file, Logger logger) throws IOException {
        String original = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

        if (!LEGACY_PATTERN.matcher(original).find()) {
            return false; // nothing to do
        }

        // Backup before touching
        File backup = new File(file.getParent(), file.getName() + ".bak");
        Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);

        String migrated = convertString(original);
        Files.write(file.toPath(), migrated.getBytes(StandardCharsets.UTF_8));

        // Count replacements for the log message
        int changes = countReplacements(original, migrated);
        logger.info("[LegacyMigrator] Migrated " + file.getName()
                + " (" + changes + " replacement(s); backup: " + backup.getName() + ")");
        return true;
    }

    /**
     * Converts all legacy {@code &x} / {@code §x} codes in {@code input} to
     * their MiniMessage equivalents.
     */
    public static String convertString(String input) {
        if (input == null || input.isEmpty()) return input;

        // Normalise § → & first
        String s = input.replace('§', '&');

        // Replace case-insensitively (e.g. &B as well as &b)
        for (Map.Entry<String, String> entry : CODE_MAP.entrySet()) {
            String code = entry.getKey();          // e.g. "&b"
            String upper = "&" + code.charAt(1);  // e.g. "&B" (no-op for digits)
            s = s.replace(code, entry.getValue())
                 .replace(upper.toUpperCase(), entry.getValue());
        }
        return s;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    public static boolean containsLegacy(String value) {
        return value != null && LEGACY_PATTERN.matcher(value).find();
    }

    private static int countReplacements(String before, String after) {
        // Rough count: difference in occurrences of "&" / "§"
        int beforeCount = countChar(before, '&') + countChar(before, '§');
        int afterCount  = countChar(after,  '&');
        return Math.max(0, beforeCount - afterCount);
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }
}
