package com.lesslag.setup;

import com.lesslag.setup.model.*;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SetupSession domain model, enum parsing, and proposal selection.
 */
public class SetupSessionTest {

    // ── Session lifecycle ──────────────────────────────

    @Test
    public void testNewSessionStartsInDiscovery() {
        SetupSession session = createSession();
        assertEquals(SessionStatus.DISCOVERY, session.getStatus());
        assertTrue(session.isActive());
        assertNotNull(session.getCreatedAt());
        assertNotNull(session.getUpdatedAt());
    }

    @Test
    public void testStatusTransitions() {
        SetupSession session = createSession();

        session.setStatus(SessionStatus.PROFILING);
        assertTrue(session.isActive());

        session.setStatus(SessionStatus.REVIEW);
        assertTrue(session.isActive());

        session.setStatus(SessionStatus.CONFIRMED);
        assertFalse(session.isActive());

        session.setStatus(SessionStatus.APPLIED);
        assertFalse(session.isActive());
    }

    @Test
    public void testAbortedSessionIsNotActive() {
        SetupSession session = createSession();
        session.setStatus(SessionStatus.ABORTED);
        assertFalse(session.isActive());
    }

    @Test
    public void testDefaultAggressivenessIsBalanced() {
        SetupSession session = createSession();
        assertEquals(AggressivenessLevel.BALANCED, session.getAggressiveness());
    }

    @Test
    public void testTouchUpdatesTimestamp() throws InterruptedException {
        SetupSession session = createSession();
        var first = session.getUpdatedAt();
        Thread.sleep(5);
        session.touch();
        assertTrue(session.getUpdatedAt().isAfter(first));
    }

    // ── Proposal management ────────────────────────────

    @Test
    public void testEffectiveProposalsReturnsAllWhenNoneSelected() {
        SetupSession session = createSession();
        session.getProposals().add(patch("key1"));
        session.getProposals().add(patch("key2"));
        session.getProposals().add(patch("key3"));

        assertEquals(3, session.getEffectiveProposals().size(),
                "With no explicit selection, all proposals should be effective");
    }

    @Test
    public void testSelectAllIncludesEveryProposal() {
        SetupSession session = createSession();
        session.getProposals().add(patch("a"));
        session.getProposals().add(patch("b"));
        session.selectAll();

        assertEquals(2, session.getSelectedProposalIndices().size());
        assertEquals(2, session.getEffectiveProposals().size());
    }

    @Test
    public void testToggleAddsAndRemovesIndex() {
        SetupSession session = createSession();
        session.getProposals().add(patch("x"));
        session.getProposals().add(patch("y"));

        assertTrue(session.toggle(0));
        assertTrue(session.getSelectedProposalIndices().contains(0));

        assertTrue(session.toggle(0));
        assertFalse(session.getSelectedProposalIndices().contains(0));
    }

    @Test
    public void testToggleOutOfBoundsReturnsFalse() {
        SetupSession session = createSession();
        assertFalse(session.toggle(-1));
        assertFalse(session.toggle(0)); // no proposals
    }

    @Test
    public void testEffectiveProposalsRespectsSelection() {
        SetupSession session = createSession();
        session.getProposals().add(patch("a"));
        session.getProposals().add(patch("b"));
        session.getProposals().add(patch("c"));
        session.toggle(1); // select only index 1

        assertEquals(1, session.getEffectiveProposals().size());
        assertEquals("b", session.getEffectiveProposals().get(0).getConfigKey());
    }

    // ── Enum parsing ──────────────────────────────────

    @Test
    public void testGameProfileFromStringValid() {
        assertEquals(GameProfile.SMP, GameProfile.fromString("smp"));
        assertEquals(GameProfile.SKYBLOCK, GameProfile.fromString("SKYBLOCK"));
        assertEquals(GameProfile.MINIGAME, GameProfile.fromString("Minigame"));
        assertEquals(GameProfile.CREATIVE, GameProfile.fromString("creative"));
    }

    @Test
    public void testGameProfileFromStringInvalidReturnsNull() {
        assertNull(GameProfile.fromString("pvp"));
        assertNull(GameProfile.fromString(null));
        assertNull(GameProfile.fromString(""));
    }

    @Test
    public void testHardwareTierFromStringValid() {
        assertEquals(HardwareTier.LOW, HardwareTier.fromString("low"));
        assertEquals(HardwareTier.MID, HardwareTier.fromString("MID"));
        assertEquals(HardwareTier.HIGH, HardwareTier.fromString("High"));
    }

    @Test
    public void testHardwareTierFromStringInvalidReturnsNull() {
        assertNull(HardwareTier.fromString("ultra"));
        assertNull(HardwareTier.fromString(null));
    }

    @Test
    public void testAggressivenessFromStringValid() {
        assertEquals(AggressivenessLevel.SAFE, AggressivenessLevel.fromString("safe"));
        assertEquals(AggressivenessLevel.BALANCED, AggressivenessLevel.fromString("BALANCED"));
        assertEquals(AggressivenessLevel.AGGRESSIVE, AggressivenessLevel.fromString("aggressive"));
    }

    @Test
    public void testAggressivenessFromStringInvalidReturnsNull() {
        assertNull(AggressivenessLevel.fromString("extreme"));
        assertNull(AggressivenessLevel.fromString(null));
    }

    // ── RuleResult builder ────────────────────────────

    @Test
    public void testRuleResultBuilderSetsAllFields() {
        RuleResult r = RuleResult.builder("test-rule")
                .group("safety")
                .severity(Severity.CRITICAL)
                .confidence(0.8)
                .why("because reason")
                .impact("reduces TPS impact")
                .tradeoff("gameplay change")
                .manualSteps("edit config manually")
                .recommendation("set value to X")
                .impactedKey("modules.redstone.max-activations-per-chunk")
                .build();

        assertEquals("test-rule", r.getRuleId());
        assertEquals("safety", r.getRuleGroup());
        assertEquals(Severity.CRITICAL, r.getSeverity());
        assertEquals(0.8, r.getConfidence(), 0.001);
        assertEquals("because reason", r.getWhy());
        assertEquals("reduces TPS impact", r.getImpact());
        assertEquals("gameplay change", r.getTradeoff());
        assertEquals("edit config manually", r.getManualSteps());
        assertEquals("set value to X", r.getRecommendationText());
        assertEquals(1, r.getImpactedKeys().size());
        assertEquals("modules.redstone.max-activations-per-chunk", r.getImpactedKeys().get(0));
    }

    @Test
    public void testRuleResultBuilderDefaults() {
        RuleResult r = RuleResult.builder("minimal").build();
        assertEquals("minimal", r.getRuleId());
        assertEquals("general", r.getRuleGroup());
        assertEquals(Severity.INFO, r.getSeverity());
        assertEquals(1.0, r.getConfidence(), 0.001);
        assertTrue(r.getImpactedKeys().isEmpty());
    }

    // ── PatchProposal ──────────────────────────────────

    @Test
    public void testPatchProposalAutoApplicable() {
        PatchProposal auto = new PatchProposal("config.yml", "key", "1", "2",
                RiskTag.LOW, ApplyScope.LESSLAG_APPLY, "rule1", "reason");
        assertTrue(auto.isAutoApplicable());

        PatchProposal recommend = new PatchProposal("spigot.yml", "key", "a", "b",
                RiskTag.HIGH, ApplyScope.RECOMMEND, "rule2", "reason");
        assertFalse(recommend.isAutoApplicable());
    }

    // ── HardwareAssessment ─────────────────────────────

    @Test
    public void testHardwareAssessmentNeedsConfirmationBelowThreshold() {
        HardwareAssessment hw = new HardwareAssessment();
        hw.setConfidenceScore(0.5);
        assertTrue(hw.needsUserConfirmation());

        hw.setConfidenceScore(0.6);
        assertFalse(hw.needsUserConfirmation());

        hw.setConfidenceScore(0.9);
        assertFalse(hw.needsUserConfirmation());
    }

    // ── RollbackBundle ─────────────────────────────────

    @Test
    public void testRollbackBundleTracksState() {
        RollbackBundle bundle = new RollbackBundle("session-1", "abc123",
                "/path/to/backup.yml", "sha256-hash");

        assertEquals("session-1", bundle.getSessionId());
        assertEquals("abc123", bundle.getRollbackToken());
        assertEquals("/path/to/backup.yml", bundle.getSnapshotFilePath());
        assertEquals("sha256-hash", bundle.getConfigChecksum());
        assertFalse(bundle.isRestored());
        assertNotNull(bundle.getCreatedAt());

        bundle.getOriginalValues().put("key1", "value1");
        assertEquals(1, bundle.getOriginalValues().size());

        bundle.setRestored(true);
        assertTrue(bundle.isRestored());
    }

    // ── Helpers ────────────────────────────────────────

    private SetupSession createSession() {
        return new SetupSession("test-001", UUID.randomUUID(), "TestPlayer");
    }

    private PatchProposal patch(String key) {
        return new PatchProposal("config.yml", key, "old", "new",
                RiskTag.LOW, ApplyScope.LESSLAG_APPLY, "test-rule", "test reason");
    }
}
