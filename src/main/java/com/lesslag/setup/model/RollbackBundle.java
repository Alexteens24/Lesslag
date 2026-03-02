package com.lesslag.setup.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Backup manifest for rollback support.
 * Stores checksums and original values before apply.
 */
public class RollbackBundle {

    private final String sessionId;
    private final String rollbackToken;
    private final Instant createdAt;
    private final String snapshotFilePath;      // path to backed-up config file
    private final String configChecksum;        // SHA-256 of original config
    private final Map<String, String> originalValues = new LinkedHashMap<>(); // key → old value
    private boolean restored;

    public RollbackBundle(String sessionId, String rollbackToken,
                           String snapshotFilePath, String configChecksum) {
        this.sessionId = sessionId;
        this.rollbackToken = rollbackToken;
        this.snapshotFilePath = snapshotFilePath;
        this.configChecksum = configChecksum;
        this.createdAt = Instant.now();
        this.restored = false;
    }

    public String getSessionId() { return sessionId; }
    public String getRollbackToken() { return rollbackToken; }
    public Instant getCreatedAt() { return createdAt; }
    public String getSnapshotFilePath() { return snapshotFilePath; }
    public String getConfigChecksum() { return configChecksum; }
    public Map<String, String> getOriginalValues() { return originalValues; }
    public boolean isRestored() { return restored; }
    public void setRestored(boolean restored) { this.restored = restored; }
}
