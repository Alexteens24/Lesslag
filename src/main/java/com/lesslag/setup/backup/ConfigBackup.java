package com.lesslag.setup.backup;

import com.lesslag.setup.model.PatchProposal;
import com.lesslag.setup.model.RollbackBundle;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.logging.Logger;

/**
 * Manages LessLag config backup, atomic write, and rollback.
 * <ul>
 *   <li>Before apply: snapshot current config + checksum</li>
 *   <li>Write via temp-file + rename (atomic)</li>
 *   <li>On failure: auto-restore from snapshot</li>
 *   <li>Retention: keep last N snapshots</li>
 * </ul>
 */
public class ConfigBackup {

    private static final Logger LOG = Logger.getLogger("LessLag-Setup");
    private static final int MAX_SNAPSHOTS = 10;

    private final File pluginDataFolder;
    private final File backupDir;

    public ConfigBackup(File pluginDataFolder) {
        this.pluginDataFolder = pluginDataFolder;
        this.backupDir = new File(pluginDataFolder, "setup-reports" + File.separator + "backups");
    }

    /**
     * Create a backup snapshot of the current LessLag config.yml.
     *
     * @param sessionId the session creating this backup
     * @return RollbackBundle with snapshot path and checksum, or null on failure
     */
    public RollbackBundle createSnapshot(String sessionId) {
        backupDir.mkdirs();

        File configFile = new File(pluginDataFolder, "config.yml");
        if (!configFile.exists()) {
            LOG.warning("config.yml not found, cannot create backup");
            return null;
        }

        String token = UUID.randomUUID().toString().substring(0, 8);
        String snapshotName = "config-backup-" + sessionId + "-" + token + ".yml";
        File snapshotFile = new File(backupDir, snapshotName);

        try {
            // Copy config to backup
            Files.copy(configFile.toPath(), snapshotFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

            // Compute checksum
            String checksum = sha256(configFile);

            RollbackBundle bundle = new RollbackBundle(sessionId, token,
                snapshotFile.getAbsolutePath(), checksum);

            LOG.info("Config backup created: " + snapshotName + " (SHA-256: " + checksum.substring(0, 12) + "...)");

            // Enforce retention
            enforceRetention();

            return bundle;
        } catch (IOException e) {
            LOG.warning("Failed to create config backup: " + e.getMessage());
            return null;
        }
    }

    /**
     * Apply a list of config changes to LessLag's config.yml using atomic write.
     * Records original values in the rollback bundle.
     *
     * @param bundle    rollback bundle to record original values
     * @param proposals patches to apply
     * @param config    the live plugin config (FileConfiguration)
     * @return list of successfully applied proposals
     */
    public List<PatchProposal> applyPatches(RollbackBundle bundle,
                                             List<PatchProposal> proposals,
                                             org.bukkit.configuration.file.FileConfiguration config) {
        List<PatchProposal> applied = new ArrayList<>();

        for (PatchProposal patch : proposals) {
            if (!patch.isAutoApplicable()) continue;

            String key = patch.getConfigKey();
            Object currentValue = config.get(key);
            String currentStr = currentValue != null ? currentValue.toString() : "";

            // Record original value in bundle
            bundle.getOriginalValues().put(key, currentStr);

            // Apply the new value
            try {
                Object newValue = parseValue(patch.getAfterValue());
                config.set(key, newValue);
                applied.add(patch);
            } catch (Exception e) {
                LOG.warning("Failed to apply patch for " + key + ": " + e.getMessage());
            }
        }

        // Atomic write: temp file → rename
        if (!applied.isEmpty()) {
            File configFile = new File(pluginDataFolder, "config.yml");
            File tempFile = new File(pluginDataFolder, "config.yml.tmp");

            try {
                config.save(tempFile);

                // Verify temp file is valid
                if (tempFile.length() < 100) {
                    throw new IOException("Temp file suspiciously small (" + tempFile.length() + " bytes)");
                }

                // Atomic rename
                Files.move(tempFile.toPath(), configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                LOG.info("Applied " + applied.size() + " config patches atomically");
            } catch (IOException e) {
                LOG.severe("Failed to write config! Attempting rollback: " + e.getMessage());
                // Clean up temp file
                tempFile.delete();
                // Attempt to restore from snapshot
                restore(bundle);
                applied.clear();
            }
        }

        return applied;
    }

    /**
     * Restore config.yml from a rollback bundle's snapshot.
     *
     * @return true if successful
     */
    public boolean restore(RollbackBundle bundle) {
        if (bundle == null || bundle.getSnapshotFilePath() == null) {
            LOG.warning("No rollback bundle available");
            return false;
        }

        File snapshot = new File(bundle.getSnapshotFilePath());
        if (!snapshot.exists()) {
            LOG.warning("Snapshot file not found: " + snapshot.getAbsolutePath());
            return false;
        }

        File configFile = new File(pluginDataFolder, "config.yml");
        try {
            // Verify snapshot integrity
            String currentChecksum = sha256(snapshot);
            if (!currentChecksum.equals(bundle.getConfigChecksum())) {
                LOG.warning("Snapshot checksum mismatch! Expected: " + bundle.getConfigChecksum()
                    + ", Got: " + currentChecksum);
                // Still restore — the file was ours
            }

            Files.copy(snapshot.toPath(), configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            bundle.setRestored(true);
            LOG.info("Config restored from snapshot: " + snapshot.getName());
            return true;
        } catch (IOException e) {
            LOG.severe("CRITICAL: Failed to restore config from backup: " + e.getMessage());
            return false;
        }
    }

    /**
     * Find a rollback bundle by token from the backup directory.
     * Scans for matching backup files.
     */
    public RollbackBundle findByToken(String token) {
        if (!backupDir.exists()) return null;

        File[] files = backupDir.listFiles((dir, name) ->
            name.contains(token) && name.endsWith(".yml"));
        if (files == null || files.length == 0) return null;

        File match = files[0];
        try {
            String checksum = sha256(match);
            // Extract session ID from filename: config-backup-{sessionId}-{token}.yml
            String name = match.getName();
            String sessionId = "unknown";
            if (name.startsWith("config-backup-")) {
                String rest = name.substring("config-backup-".length());
                int lastDash = rest.lastIndexOf('-');
                if (lastDash > 0) {
                    sessionId = rest.substring(0, lastDash);
                }
            }
            return new RollbackBundle(sessionId, token, match.getAbsolutePath(), checksum);
        } catch (Exception e) {
            LOG.warning("Failed to read backup file: " + e.getMessage());
            return null;
        }
    }

    // ── Internal helpers ─────────────────────────────

    private void enforceRetention() {
        File[] backups = backupDir.listFiles((dir, name) ->
            name.startsWith("config-backup-") && name.endsWith(".yml"));
        if (backups == null || backups.length <= MAX_SNAPSHOTS) return;

        // Sort by last modified, oldest first
        Arrays.sort(backups, Comparator.comparingLong(File::lastModified));

        int toRemove = backups.length - MAX_SNAPSHOTS;
        for (int i = 0; i < toRemove; i++) {
            if (backups[i].delete()) {
                LOG.info("Pruned old backup: " + backups[i].getName());
            }
        }
    }

    private static String sha256(File file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream is = Files.newInputStream(file.toPath())) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) != -1) {
                    md.update(buf, 0, len);
                }
            }
            byte[] hash = md.digest();
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }

    /**
     * Parse a string value into an appropriate type for YAML config.
     */
    private static Object parseValue(String value) {
        if (value == null) return null;
        String trimmed = value.trim();

        // Boolean
        if ("true".equalsIgnoreCase(trimmed)) return true;
        if ("false".equalsIgnoreCase(trimmed)) return false;

        // Integer
        try { return Integer.parseInt(trimmed); } catch (NumberFormatException ignored) {}

        // Double
        try { return Double.parseDouble(trimmed); } catch (NumberFormatException ignored) {}

        // String fallback
        return trimmed;
    }
}
