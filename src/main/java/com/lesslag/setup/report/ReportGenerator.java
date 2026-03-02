package com.lesslag.setup.report;

import com.lesslag.setup.model.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.logging.Logger;

/**
 * Generates all session report files into plugins/LessLag/setup-reports/.
 * <ul>
 *   <li>session-{id}-summary.md — executive summary</li>
 *   <li>session-{id}-recommendations.yml — machine-readable proposals</li>
 *   <li>session-{id}-manual-patches.md — copy-ready server config changes</li>
 *   <li>session-{id}-lesslag-applied.diff — diff of applied LessLag changes</li>
 * </ul>
 */
public class ReportGenerator {

    private static final Logger LOG = Logger.getLogger("LessLag-Setup");
    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());

    private final File reportsDir;

    public ReportGenerator(File pluginDataFolder) {
        this.reportsDir = new File(pluginDataFolder, "setup-reports");
    }

    /**
     * Generate all report files for a completed session.
     * Safe to call from async thread.
     */
    public void generate(SetupSession session, String pluginListHash) {
        reportsDir.mkdirs();

        String id = session.getSessionId();

        try {
            writeSummary(session, pluginListHash, new File(reportsDir, "session-" + id + "-summary.md"));
            writeRecommendationsYml(session, new File(reportsDir, "session-" + id + "-recommendations.yml"));
            writeManualPatches(session, new File(reportsDir, "session-" + id + "-manual-patches.md"));
        } catch (IOException e) {
            LOG.warning("Failed to write setup report: " + e.getMessage());
        }
    }

    /**
     * Write the applied-changes diff after config apply.
     */
    public void writeAppliedDiff(SetupSession session, List<PatchProposal> applied) {
        File diffFile = new File(reportsDir, "session-" + session.getSessionId() + "-lesslag-applied.diff");
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                Files.newOutputStream(diffFile.toPath()), StandardCharsets.UTF_8))) {
            pw.println("# LessLag Config Changes Applied");
            pw.println("# Session: " + session.getSessionId());
            pw.println("# Timestamp: " + FMT.format(session.getUpdatedAt()));
            pw.println();

            for (PatchProposal p : applied) {
                pw.println("--- a/" + p.getTargetFile());
                pw.println("+++ b/" + p.getTargetFile());
                pw.println("@@ " + p.getConfigKey() + " @@");
                pw.println("- " + p.getConfigKey() + ": " + p.getBeforeValue());
                pw.println("+ " + p.getConfigKey() + ": " + p.getAfterValue());
                pw.println();
            }
        } catch (IOException e) {
            LOG.warning("Failed to write applied diff: " + e.getMessage());
        }
    }

    // ── Internal writers ─────────────────────────────

    private void writeSummary(SetupSession session, String pluginListHash, File file) throws IOException {
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8))) {

            pw.println("# LessLag Setup Advisor — Session Summary");
            pw.println();
            pw.println("| Field | Value |");
            pw.println("|-------|-------|");
            pw.println("| Session ID | `" + session.getSessionId() + "` |");
            pw.println("| Creator | " + session.getCreatorName() + " |");
            pw.println("| Created | " + FMT.format(session.getCreatedAt()) + " |");
            pw.println("| Status | " + session.getStatus() + " |");

            if (session.getSelectedProfile() != null) {
                pw.println("| Game Profile | " + session.getSelectedProfile().getDisplayName() + " |");
            }
            if (session.getSelectedTier() != null) {
                pw.println("| Hardware Tier | " + session.getSelectedTier().getDisplayName() + " |");
            }
            pw.println("| Aggressiveness | " + session.getAggressiveness().getDisplayName() + " |");
            pw.println("| Plugin List Hash | `" + pluginListHash + "` |");

            // Environment
            EnvironmentSnapshot env = session.getEnvironment();
            if (env != null) {
                pw.println();
                pw.println("## Environment");
                pw.println();
                pw.println("- **Platform:** " + env.getPlatformName() + " " + env.getPlatformVersion());
                pw.println("- **Minecraft:** " + env.getMinecraftVersion());
                pw.println("- **Folia:** " + (env.isFoliaDetected() ? "Yes" : "No"));
                pw.println("- **Players:** " + env.getOnlinePlayers());
                pw.println("- **Loaded Chunks:** " + env.getLoadedChunks());
                pw.println("- **Total Entities:** " + env.getTotalEntities());
                pw.println("- **TPS:** " + String.format("%.1f", env.getCurrentTps()));
                pw.println("- **MSPT:** " + String.format("%.1f", env.getCurrentMspt()) + "ms");
            }

            // Hardware
            HardwareAssessment hw = session.getHardwareAssessment();
            if (hw != null) {
                pw.println();
                pw.println("## Hardware Assessment");
                pw.println();
                pw.println("- **CPU:** " + hw.getCpuModel() + " (" + hw.getAvailableProcessors() + " threads)");
                pw.println("- **Heap:** " + hw.getUsedHeapFormatted() + " / " + hw.getMaxHeapFormatted());
                pw.println("- **GC:** " + hw.getGcName());
                pw.println("- **Detected Tier:** " + hw.getDetectedTier().getDisplayName()
                    + " (confidence: " + String.format("%.0f%%", hw.getConfidenceScore() * 100) + ")");
                for (String reason : hw.getReasons()) {
                    pw.println("  - " + reason);
                }
            }

            // Key findings
            pw.println();
            pw.println("## Findings");
            pw.println();
            long critical = session.getRuleResults().stream()
                .filter(r -> r.getSeverity() == Severity.CRITICAL).count();
            long warnings = session.getRuleResults().stream()
                .filter(r -> r.getSeverity() == Severity.WARNING).count();
            long info = session.getRuleResults().stream()
                .filter(r -> r.getSeverity() == Severity.INFO).count();
            pw.println("- **Critical:** " + critical);
            pw.println("- **Warnings:** " + warnings);
            pw.println("- **Info:** " + info);
            pw.println("- **Total Proposals:** " + session.getProposals().size());
            pw.println("- **Auto-Applicable:** " + session.getProposals().stream()
                .filter(PatchProposal::isAutoApplicable).count());
            pw.println("- **Manual/Recommend-Only:** " + session.getProposals().stream()
                .filter(p -> !p.isAutoApplicable()).count());

            // Critical findings
            if (critical > 0) {
                pw.println();
                pw.println("### Critical Issues");
                pw.println();
                for (RuleResult r : session.getRuleResults()) {
                    if (r.getSeverity() == Severity.CRITICAL) {
                        pw.println("- **" + r.getRuleId() + "**: " + r.getWhy());
                        if (r.getManualSteps() != null) {
                            pw.println("  - Action: " + r.getManualSteps());
                        }
                    }
                }
            }

            pw.println();
            pw.println("---");
            pw.println("*Generated by LessLag Setup Advisor*");
        }
    }

    private void writeRecommendationsYml(SetupSession session, File file) throws IOException {
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8))) {
            pw.println("# LessLag Setup Advisor — Machine-Readable Recommendations");
            pw.println("# Session: " + session.getSessionId());
            pw.println("# Generated: " + FMT.format(session.getUpdatedAt()));
            pw.println();
            pw.println("session-id: \"" + session.getSessionId() + "\"");
            if (session.getSelectedProfile() != null) {
                pw.println("profile: " + session.getSelectedProfile().name());
            }
            if (session.getSelectedTier() != null) {
                pw.println("tier: " + session.getSelectedTier().name());
            }
            pw.println("aggressiveness: " + session.getAggressiveness().name());
            pw.println("status: " + session.getStatus().name());
            pw.println();

            pw.println("rules:");
            for (RuleResult r : session.getRuleResults()) {
                pw.println("  - id: \"" + r.getRuleId() + "\"");
                pw.println("    group: \"" + r.getRuleGroup() + "\"");
                pw.println("    severity: " + r.getSeverity().name());
                pw.println("    confidence: " + r.getConfidence());
                pw.println("    why: \"" + escapeYml(r.getWhy()) + "\"");
                pw.println("    impact: \"" + escapeYml(r.getImpact()) + "\"");
                pw.println("    tradeoff: \"" + escapeYml(r.getTradeoff()) + "\"");
                pw.println("    recommendation: \"" + escapeYml(r.getRecommendationText()) + "\"");
                if (r.getManualSteps() != null) {
                    pw.println("    manual-steps: \"" + escapeYml(r.getManualSteps()) + "\"");
                }
                pw.println();
            }

            pw.println("proposals:");
            for (int i = 0; i < session.getProposals().size(); i++) {
                PatchProposal p = session.getProposals().get(i);
                pw.println("  - index: " + i);
                pw.println("    target: \"" + p.getTargetFile() + "\"");
                pw.println("    key: \"" + p.getConfigKey() + "\"");
                pw.println("    before: \"" + p.getBeforeValue() + "\"");
                pw.println("    after: \"" + p.getAfterValue() + "\"");
                pw.println("    risk: " + p.getRiskTag().name());
                pw.println("    scope: " + p.getApplyScope().name());
                pw.println("    rule: \"" + p.getRuleId() + "\"");
                pw.println("    rationale: \"" + escapeYml(p.getRationale()) + "\"");
                pw.println("    selected: " + session.getSelectedProposalIndices().contains(i));
                pw.println();
            }
        }
    }

    private void writeManualPatches(SetupSession session, File file) throws IOException {
        List<PatchProposal> manualPatches = session.getProposals().stream()
            .filter(p -> !p.isAutoApplicable())
            .collect(java.util.stream.Collectors.toList());

        if (manualPatches.isEmpty()) return;

        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8))) {
            pw.println("# LessLag Setup Advisor — Manual Server Config Changes");
            pw.println();
            pw.println("These changes must be applied manually to your server configuration files.");
            pw.println("LessLag cannot modify these files directly.");
            pw.println();
            pw.println("Session: `" + session.getSessionId() + "`");
            pw.println("Generated: " + FMT.format(session.getUpdatedAt()));
            pw.println();

            // Group by target file
            java.util.Map<String, java.util.List<PatchProposal>> byFile = new java.util.LinkedHashMap<>();
            for (PatchProposal p : manualPatches) {
                byFile.computeIfAbsent(p.getTargetFile(), k -> new java.util.ArrayList<>()).add(p);
            }

            for (java.util.Map.Entry<String, java.util.List<PatchProposal>> entry : byFile.entrySet()) {
                pw.println("## `" + entry.getKey() + "`");
                pw.println();
                for (PatchProposal p : entry.getValue()) {
                    pw.println("### " + p.getConfigKey());
                    pw.println();
                    pw.println("- **Current:** `" + p.getBeforeValue() + "`");
                    pw.println("- **Recommended:** `" + p.getAfterValue() + "`");
                    pw.println("- **Risk:** " + p.getRiskTag().name());
                    pw.println("- **Why:** " + p.getRationale());
                    pw.println();
                }
            }

            pw.println("---");
            pw.println("*Apply these changes, then restart your server for them to take effect.*");
        }
    }

    private static String escapeYml(String s) {
        if (s == null) return "";
        return s.replace("\"", "\\\"").replace("\n", " ");
    }
}
