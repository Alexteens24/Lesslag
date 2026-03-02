package com.lesslag.setup;

import com.lesslag.setup.backup.ConfigBackup;
import com.lesslag.setup.model.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ConfigBackup: snapshot creation, restore, retention, and atomic writes.
 */
public class ConfigBackupTest {

    private static final String CONFIG_CONTENT =
            "workload-limit-ms: 2\n" +
            "modules:\n" +
            "  redstone:\n" +
            "    max-activations-per-chunk: 250\n" +
            "  entities:\n" +
            "    chunk-limiter:\n" +
            "      max-entities-per-chunk: 50\n";

    private File createEnv() throws IOException {
        File dir = Files.createTempDirectory("lesslag-backup-test").toFile();
        File configFile = new File(dir, "config.yml");
        Files.writeString(configFile.toPath(), CONFIG_CONTENT);
        return dir;
    }

    private void cleanUp(File dir) {
        try {
            if (dir != null && dir.exists()) {
                Files.walk(dir.toPath())
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        } catch (IOException ignored) {}
    }

    @Test
    public void testCreateSnapshotReturnsBundle() throws IOException {
        File dir = createEnv();
        try {
            ConfigBackup backup = new ConfigBackup(dir);
            RollbackBundle bundle = backup.createSnapshot("sess-1");
            assertNotNull(bundle, "Snapshot should be created");
            assertEquals("sess-1", bundle.getSessionId());
            assertNotNull(bundle.getRollbackToken());
            assertNotNull(bundle.getSnapshotFilePath());
            assertNotNull(bundle.getConfigChecksum());
            assertFalse(bundle.getConfigChecksum().isEmpty());
        } finally {
            cleanUp(dir);
        }
    }

    @Test
    public void testSnapshotFileContentsMatchOriginal() throws IOException {
        File dir = createEnv();
        try {
            ConfigBackup backup = new ConfigBackup(dir);
            File configFile = new File(dir, "config.yml");
            RollbackBundle bundle = backup.createSnapshot("sess-copy");
            assertNotNull(bundle);

            String original = Files.readString(configFile.toPath());
            String snapshot = Files.readString(new File(bundle.getSnapshotFilePath()).toPath());
            assertEquals(original, snapshot, "Snapshot file should be identical to original");
        } finally {
            cleanUp(dir);
        }
    }

    @Test
    public void testCreateSnapshotReturnsNullWhenConfigMissing() throws IOException {
        File dir = createEnv();
        try {
            File configFile = new File(dir, "config.yml");
            Files.deleteIfExists(configFile.toPath());
            ConfigBackup backup = new ConfigBackup(dir);
            RollbackBundle bundle = backup.createSnapshot("no-config");
            assertNull(bundle, "Should return null when config.yml doesn't exist");
        } finally {
            cleanUp(dir);
        }
    }

    @Test
    public void testMultipleSnapshotsHaveUniqueTokens() throws IOException {
        File dir = createEnv();
        try {
            ConfigBackup backup = new ConfigBackup(dir);
            RollbackBundle b1 = backup.createSnapshot("s1");
            RollbackBundle b2 = backup.createSnapshot("s2");
            assertNotNull(b1);
            assertNotNull(b2);
            assertNotEquals(b1.getRollbackToken(), b2.getRollbackToken());
        } finally {
            cleanUp(dir);
        }
    }

    @Test
    public void testRestoreFromSnapshotWorks() throws IOException {
        File dir = createEnv();
        try {
            ConfigBackup backup = new ConfigBackup(dir);
            File configFile = new File(dir, "config.yml");
            RollbackBundle bundle = backup.createSnapshot("restore-test");
            assertNotNull(bundle);

            Files.writeString(configFile.toPath(), "changed: true\n");
            assertTrue(Files.readString(configFile.toPath()).contains("changed"));

            boolean restored = backup.restore(bundle);
            assertTrue(restored, "Restore should succeed");
            assertTrue(bundle.isRestored());

            String restoredContent = Files.readString(configFile.toPath());
            assertTrue(restoredContent.contains("workload-limit-ms: 2"));
            assertFalse(restoredContent.contains("changed"));
        } finally {
            cleanUp(dir);
        }
    }

    @Test
    public void testRestoreWithNullBundleFails() throws IOException {
        File dir = createEnv();
        try {
            ConfigBackup backup = new ConfigBackup(dir);
            assertFalse(backup.restore(null));
        } finally {
            cleanUp(dir);
        }
    }

    @Test
    public void testRestoreWithMissingSnapshotFileFails() throws IOException {
        File dir = createEnv();
        try {
            ConfigBackup backup = new ConfigBackup(dir);
            RollbackBundle bundle = new RollbackBundle("s", "tok",
                    new File(dir, "nonexistent.yml").getAbsolutePath(), "checksum");
            assertFalse(backup.restore(bundle));
        } finally {
            cleanUp(dir);
        }
    }

    @Test
    public void testApplyPatchesModifiesConfig() throws IOException {
        File dir = createEnv();
        try {
            ConfigBackup backup = new ConfigBackup(dir);
            File configFile = new File(dir, "config.yml");
            RollbackBundle bundle = backup.createSnapshot("patch-test");
            assertNotNull(bundle);

            YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
            PatchProposal patch = new PatchProposal("config.yml",
                    "modules.redstone.max-activations-per-chunk", "250", "150",
                    RiskTag.LOW, ApplyScope.LESSLAG_APPLY, "test-rule", "reduce lag");

            List<PatchProposal> applied = backup.applyPatches(bundle, List.of(patch), config);
            assertEquals(1, applied.size(), "One patch should be applied");
            assertEquals("250", bundle.getOriginalValues().get("modules.redstone.max-activations-per-chunk"));

            YamlConfiguration reloaded = YamlConfiguration.loadConfiguration(configFile);
            assertEquals(150, reloaded.getInt("modules.redstone.max-activations-per-chunk"));
        } finally {
            cleanUp(dir);
        }
    }

    @Test
    public void testApplyPatchesSkipsRecommendOnlyPatches() throws IOException {
        File dir = createEnv();
        try {
            ConfigBackup backup = new ConfigBackup(dir);
            File configFile = new File(dir, "config.yml");
            RollbackBundle bundle = backup.createSnapshot("recommend-test");
            assertNotNull(bundle);

            YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
            PatchProposal recommendOnly = new PatchProposal("spigot.yml",
                    "some.key", "old", "new",
                    RiskTag.HIGH, ApplyScope.RECOMMEND, "ext-rule", "manual change");

            List<PatchProposal> applied = backup.applyPatches(bundle, List.of(recommendOnly), config);
            assertTrue(applied.isEmpty(), "RECOMMEND patches should be skipped");
        } finally {
            cleanUp(dir);
        }
    }

    @Test
    public void testApplyPatchesThenRestore() throws IOException {
        File dir = createEnv();
        try {
            ConfigBackup backup = new ConfigBackup(dir);
            File configFile = new File(dir, "config.yml");
            RollbackBundle bundle = backup.createSnapshot("full-cycle");
            assertNotNull(bundle);

            YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
            PatchProposal patch = new PatchProposal("config.yml",
                    "workload-limit-ms", "2", "1",
                    RiskTag.LOW, ApplyScope.LESSLAG_APPLY, "perf-tune", "tighten budget");

            backup.applyPatches(bundle, List.of(patch), config);
            YamlConfiguration modified = YamlConfiguration.loadConfiguration(configFile);
            assertEquals(1, modified.getInt("workload-limit-ms"));

            assertTrue(backup.restore(bundle));
            YamlConfiguration restored = YamlConfiguration.loadConfiguration(configFile);
            assertEquals(2, restored.getInt("workload-limit-ms"));
        } finally {
            cleanUp(dir);
        }
    }

    @Test
    public void testFindByTokenReturnsBundle() throws IOException {
        File dir = createEnv();
        try {
            ConfigBackup backup = new ConfigBackup(dir);
            RollbackBundle original = backup.createSnapshot("find-test");
            assertNotNull(original);

            RollbackBundle found = backup.findByToken(original.getRollbackToken());
            assertNotNull(found, "Should find bundle by token");
            assertEquals(original.getRollbackToken(), found.getRollbackToken());
        } finally {
            cleanUp(dir);
        }
    }

    @Test
    public void testFindByTokenReturnsNullForUnknown() throws IOException {
        File dir = createEnv();
        try {
            ConfigBackup backup = new ConfigBackup(dir);
            RollbackBundle found = backup.findByToken("nonexistent-token");
            assertNull(found);
        } finally {
            cleanUp(dir);
        }
    }

    @Test
    public void testRetentionEnforcement() throws IOException {
        File dir = createEnv();
        try {
            ConfigBackup backup = new ConfigBackup(dir);
            for (int i = 0; i < 15; i++) {
                RollbackBundle b = backup.createSnapshot("retention-" + i);
                assertNotNull(b, "Snapshot " + i + " should be created");
            }

            File backupDir = new File(dir, "setup-reports" + File.separator + "backups");
            File[] remaining = backupDir.listFiles((d, name) ->
                    name.startsWith("config-backup-") && name.endsWith(".yml"));
            assertNotNull(remaining);
            assertTrue(remaining.length <= 10,
                    "Should retain at most 10 backups, found " + remaining.length);
        } finally {
            cleanUp(dir);
        }
    }

    @Test
    public void testChecksumIsConsistentForSameContent() throws IOException {
        File dir = createEnv();
        try {
            ConfigBackup backup = new ConfigBackup(dir);
            RollbackBundle b1 = backup.createSnapshot("chk1");
            RollbackBundle b2 = backup.createSnapshot("chk2");
            assertNotNull(b1);
            assertNotNull(b2);
            assertEquals(b1.getConfigChecksum(), b2.getConfigChecksum(),
                    "Same config content should produce same checksum");
        } finally {
            cleanUp(dir);
        }
    }

    @Test
    public void testChecksumChangesWhenContentChanges() throws IOException {
        File dir = createEnv();
        try {
            ConfigBackup backup = new ConfigBackup(dir);
            File configFile = new File(dir, "config.yml");
            RollbackBundle before = backup.createSnapshot("before");
            assertNotNull(before);

            Files.writeString(configFile.toPath(), "different-content: true\n");
            RollbackBundle after = backup.createSnapshot("after");
            assertNotNull(after);

            assertNotEquals(before.getConfigChecksum(), after.getConfigChecksum(),
                    "Different content should produce different checksums");
        } finally {
            cleanUp(dir);
        }
    }
}
