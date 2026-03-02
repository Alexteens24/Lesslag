package com.lesslag.setup.model;

import java.time.Instant;
import java.util.*;

/**
 * Tracks a single Setup Advisor wizard session.
 * Created on {@code /lg setup start}, lives until confirm/abort/expire.
 */
public class SetupSession {

    private final String sessionId;
    private final UUID creatorUuid;
    private final String creatorName;
    private final Instant createdAt;
    private Instant updatedAt;

    private SessionStatus status;
    private GameProfile selectedProfile;
    private HardwareTier selectedTier;
    private AggressivenessLevel aggressiveness;

    private EnvironmentSnapshot environment;
    private HardwareAssessment hardwareAssessment;

    /** All generated rule results. */
    private final List<RuleResult> ruleResults = new ArrayList<>();
    /** All generated patch proposals. */
    private final List<PatchProposal> proposals = new ArrayList<>();
    /** User-selected proposal indices (subset of proposals). */
    private final Set<Integer> selectedProposalIndices = new LinkedHashSet<>();

    private RollbackBundle rollbackBundle;

    public SetupSession(String sessionId, UUID creatorUuid, String creatorName) {
        this.sessionId = sessionId;
        this.creatorUuid = creatorUuid;
        this.creatorName = creatorName;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.status = SessionStatus.DISCOVERY;
        this.aggressiveness = AggressivenessLevel.BALANCED;
    }

    // ── Lifecycle helpers ────────────────────────────

    public void touch() { this.updatedAt = Instant.now(); }

    public boolean isActive() {
        return status == SessionStatus.DISCOVERY
            || status == SessionStatus.PROFILING
            || status == SessionStatus.REVIEW;
    }

    // ── Getters / Setters ────────────────────────────

    public String getSessionId() { return sessionId; }
    public UUID getCreatorUuid() { return creatorUuid; }
    public String getCreatorName() { return creatorName; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public SessionStatus getStatus() { return status; }
    public void setStatus(SessionStatus status) { this.status = status; touch(); }

    public GameProfile getSelectedProfile() { return selectedProfile; }
    public void setSelectedProfile(GameProfile profile) { this.selectedProfile = profile; touch(); }

    public HardwareTier getSelectedTier() { return selectedTier; }
    public void setSelectedTier(HardwareTier tier) { this.selectedTier = tier; touch(); }

    public AggressivenessLevel getAggressiveness() { return aggressiveness; }
    public void setAggressiveness(AggressivenessLevel level) { this.aggressiveness = level; touch(); }

    public EnvironmentSnapshot getEnvironment() { return environment; }
    public void setEnvironment(EnvironmentSnapshot environment) { this.environment = environment; touch(); }

    public HardwareAssessment getHardwareAssessment() { return hardwareAssessment; }
    public void setHardwareAssessment(HardwareAssessment assessment) { this.hardwareAssessment = assessment; touch(); }

    public List<RuleResult> getRuleResults() { return ruleResults; }
    public List<PatchProposal> getProposals() { return proposals; }
    public Set<Integer> getSelectedProposalIndices() { return selectedProposalIndices; }

    public RollbackBundle getRollbackBundle() { return rollbackBundle; }
    public void setRollbackBundle(RollbackBundle bundle) { this.rollbackBundle = bundle; touch(); }

    /** Returns proposals the user has selected (or all if none explicitly selected). */
    public List<PatchProposal> getEffectiveProposals() {
        if (selectedProposalIndices.isEmpty()) {
            return Collections.unmodifiableList(proposals);
        }
        List<PatchProposal> selected = new ArrayList<>();
        for (int idx : selectedProposalIndices) {
            if (idx >= 0 && idx < proposals.size()) {
                selected.add(proposals.get(idx));
            }
        }
        return selected;
    }

    /** Select all proposals by default. */
    public void selectAll() {
        selectedProposalIndices.clear();
        for (int i = 0; i < proposals.size(); i++) {
            selectedProposalIndices.add(i);
        }
    }

    /** Toggle a specific proposal index. */
    public boolean toggle(int index) {
        if (index < 0 || index >= proposals.size()) return false;
        if (selectedProposalIndices.contains(index)) {
            selectedProposalIndices.remove(index);
        } else {
            selectedProposalIndices.add(index);
        }
        touch();
        return true;
    }
}
