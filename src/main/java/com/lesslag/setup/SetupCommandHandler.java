package com.lesslag.setup;

import com.lesslag.LessLag;
import com.lesslag.setup.model.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles all {@code /lg setup ...} subcommands for the Setup Advisor wizard.
 *
 * <pre>
 * /lg setup start           — run discovery + create session
 * /lg setup profile <type>  — choose game profile
 * /lg setup tier [override] — auto-suggest + optional override
 * /lg setup level <level>   — set aggressiveness (safe/balanced/aggressive)
 * /lg setup review          — show grouped recommendations
 * /lg setup select <spec>   — include/exclude groups/items
 * /lg setup confirm         — single aggregate approval + apply
 * /lg setup abort           — cancel session
 * /lg setup rollback <tok>  — restore previous config snapshot
 * </pre>
 */
public class SetupCommandHandler {

    private final LessLag plugin;
    private final SetupAdvisor advisor;

    public SetupCommandHandler(LessLag plugin, SetupAdvisor advisor) {
        this.plugin = plugin;
        this.advisor = advisor;
    }

    /**
     * Handle a /lg setup subcommand. Returns true if handled.
     */
    public boolean handle(CommandSender sender, String[] args) {
        if (args.length < 2) {
            showSetupHelp(sender);
            return true;
        }

        String sub = args[1].toLowerCase();
        switch (sub) {
            case "start":
                handleStart(sender);
                break;
            case "profile":
                handleProfile(sender, args);
                break;
            case "tier":
                handleTier(sender, args);
                break;
            case "level":
                handleLevel(sender, args);
                break;
            case "review":
                handleReview(sender);
                break;
            case "select":
                handleSelect(sender, args);
                break;
            case "confirm":
                handleConfirm(sender, args);
                break;
            case "abort":
                handleAbort(sender);
                break;
            case "rollback":
                handleRollback(sender, args);
                break;
            default:
                showSetupHelp(sender);
                break;
        }
        return true;
    }

    /**
     * Tab-complete for setup subcommands.
     */
    public List<String> tabComplete(String[] args) {
        if (args.length == 2) {
            List<String> subs = Arrays.asList(
                "start", "profile", "tier", "level", "review", "select", "confirm", "abort", "rollback");
            return subs.stream()
                .filter(s -> s.startsWith(args[1].toLowerCase()))
                .collect(Collectors.toList());
        }
        if (args.length == 3) {
            switch (args[1].toLowerCase()) {
                case "profile":
                    return Arrays.stream(GameProfile.values())
                        .map(p -> p.name().toLowerCase())
                        .filter(s -> s.startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
                case "tier":
                    return Arrays.stream(HardwareTier.values())
                        .map(t -> t.name().toLowerCase())
                        .filter(s -> s.startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
                case "level":
                    return Arrays.stream(AggressivenessLevel.values())
                        .map(l -> l.name().toLowerCase())
                        .filter(s -> s.startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
                case "select":
                    List<String> opts = new ArrayList<>(Arrays.asList("all", "none"));
                    // Add group names
                    opts.addAll(Arrays.asList("safety", "consistency", "conflict", "fork-specific", "performance"));
                    return opts.stream()
                        .filter(s -> s.startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }
        return Collections.emptyList();
    }

    // ── Subcommand Handlers ────────────────────────────

    private void handleStart(CommandSender sender) {
        UUID uuid = sender instanceof Player ? ((Player) sender).getUniqueId() : null;
        String name = sender.getName();

        send(sender, "");
        send(sender, "&b&l  ≡ LessLag Setup Advisor ≡");
        send(sender, "&7  Starting server discovery...");
        send(sender, "");

        advisor.startSession(uuid, name, session -> {
            if (session.getStatus() == SessionStatus.FAILED) {
                send(sender, "&c  Discovery failed! Check console for details.");
                return;
            }

            // Show discovery results
            EnvironmentSnapshot env = session.getEnvironment();
            HardwareAssessment hw = session.getHardwareAssessment();

            send(sender, "&a  ✔ Discovery complete!");
            send(sender, "");
            send(sender, "  &eSession: &7" + session.getSessionId());
            if (env != null) {
                send(sender, "  &ePlatform: &7" + env.getPlatformName() + " &8(" + env.getMinecraftVersion() + ")");
                send(sender, "  &eFolia: &7" + (env.isFoliaDetected() ? "&aYes" : "&7No"));
                send(sender, "  &ePlayers: &7" + env.getOnlinePlayers()
                    + " &8| &eChunks: &7" + env.getLoadedChunks()
                    + " &8| &eEntities: &7" + env.getTotalEntities());
                if (env.getCurrentTps() > 0) {
                    send(sender, "  &eTPS: &7" + String.format("%.1f", env.getCurrentTps())
                        + " &8| &eMSPT: &7" + String.format("%.1f", env.getCurrentMspt()) + "ms");
                }
                send(sender, "  &eConfig files: &7" + env.getConfigFilesPresent().values().stream()
                    .filter(Boolean::booleanValue).count() + " found");
                send(sender, "  &ePlugins: &7" + env.getPlugins().size() + " loaded");
            }
            if (hw != null) {
                send(sender, "");
                send(sender, "  &eCPU: &7" + hw.getCpuModel() + " &8(" + hw.getAvailableProcessors() + " threads)");
                send(sender, "  &eHeap: &7" + hw.getUsedHeapFormatted() + " / " + hw.getMaxHeapFormatted());
                send(sender, "  &eGC: &7" + hw.getGcName());

                String tierColor = hw.getDetectedTier() == HardwareTier.HIGH ? "&a"
                    : hw.getDetectedTier() == HardwareTier.MID ? "&e" : "&c";
                send(sender, "  &eDetected Tier: " + tierColor + hw.getDetectedTier().getDisplayName()
                    + " &8(confidence: " + String.format("%.0f%%", hw.getConfidenceScore() * 100) + ")");

                if (hw.needsUserConfirmation()) {
                    send(sender, "");
                    send(sender, "  &6⚠ Low confidence — please confirm tier:");
                    send(sender, "    &e/lg setup tier low &8| &e/lg setup tier mid &8| &e/lg setup tier high");
                }
            }

            send(sender, "");
            send(sender, "  &7Next steps:");
            send(sender, "    &e/lg setup profile <smp|skyblock|minigame|creative>");
            if (hw == null || !hw.needsUserConfirmation()) {
                send(sender, "    &e/lg setup tier &8(optional override)");
            }
            send(sender, "    &e/lg setup level <safe|balanced|aggressive> &8(default: balanced)");
            send(sender, "");
        });
    }

    private void handleProfile(CommandSender sender, String[] args) {
        SetupSession session = getActiveSession(sender);
        if (session == null) return;

        if (args.length < 3) {
            send(sender, "&cUsage: /lg setup profile <smp|skyblock|minigame|creative>");
            send(sender, "");
            for (GameProfile p : GameProfile.values()) {
                send(sender, "  &e" + p.name().toLowerCase() + " &8- &7" + p.getDescription());
            }
            return;
        }

        GameProfile profile = GameProfile.fromString(args[2]);
        if (profile == null) {
            send(sender, "&cUnknown profile: " + args[2]);
            send(sender, "&7Available: smp, skyblock, minigame, creative");
            return;
        }

        advisor.setProfile(session, profile);
        send(sender, "&a  ✔ Profile set to: &e" + profile.getDisplayName());
        send(sender, "  &7" + profile.getDescription());

        autoAdvance(sender, session);
    }

    private void handleTier(CommandSender sender, String[] args) {
        SetupSession session = getActiveSession(sender);
        if (session == null) return;

        if (args.length < 3) {
            HardwareTier current = session.getSelectedTier() != null
                ? session.getSelectedTier()
                : (session.getHardwareAssessment() != null
                    ? session.getHardwareAssessment().getDetectedTier() : null);

            send(sender, "&eCurrent tier: &7" + (current != null ? current.getDisplayName() : "not set"));
            send(sender, "&7Usage: /lg setup tier <low|mid|high>");
            return;
        }

        HardwareTier tier = HardwareTier.fromString(args[2]);
        if (tier == null) {
            send(sender, "&cUnknown tier: " + args[2] + " &7(use: low, mid, high)");
            return;
        }

        advisor.setTier(session, tier);
        send(sender, "&a  ✔ Hardware tier set to: &e" + tier.getDisplayName());

        autoAdvance(sender, session);
    }

    private void handleLevel(CommandSender sender, String[] args) {
        SetupSession session = getActiveSession(sender);
        if (session == null) return;

        if (args.length < 3) {
            send(sender, "&cUsage: /lg setup level <safe|balanced|aggressive>");
            for (AggressivenessLevel l : AggressivenessLevel.values()) {
                String marker = l == session.getAggressiveness() ? " &a← current" : "";
                send(sender, "  &e" + l.name().toLowerCase() + " &8- &7" + l.getDescription() + marker);
            }
            return;
        }

        AggressivenessLevel level = AggressivenessLevel.fromString(args[2]);
        if (level == null) {
            send(sender, "&cUnknown level: " + args[2] + " &7(use: safe, balanced, aggressive)");
            return;
        }

        advisor.setAggressiveness(session, level);
        send(sender, "&a  ✔ Aggressiveness set to: &e" + level.getDisplayName());

        autoAdvance(sender, session);
    }

    /**
     * If profile is set (and tier is auto or set), auto-generate recommendations.
     */
    private void autoAdvance(CommandSender sender, SetupSession session) {
        if (session.getSelectedProfile() != null) {
            // Auto-use detected tier if not overridden
            if (session.getSelectedTier() == null && session.getHardwareAssessment() != null
                    && !session.getHardwareAssessment().needsUserConfirmation()) {
                session.setSelectedTier(session.getHardwareAssessment().getDetectedTier());
            }

            if (session.getSelectedTier() != null) {
                send(sender, "");
                send(sender, "  &7Generating recommendations...");

                advisor.generateRecommendations(session, s -> {
                    send(sender, "&a  ✔ Recommendations ready! (" + s.getProposals().size() + " proposals)");
                    send(sender, "  &7Use &e/lg setup review &7to see them.");
                });
            }
        }
    }

    private void handleReview(CommandSender sender) {
        SetupSession session = getActiveSession(sender);
        if (session == null) return;

        if (session.getStatus() != SessionStatus.REVIEW) {
            send(sender, "&cNo recommendations generated yet.");
            send(sender, "&7Complete profile and tier selection first.");
            return;
        }

        send(sender, "");
        send(sender, "&b&l  ≡ Setup Recommendations ≡");
        send(sender, "&7  " + session.getSelectedProfile().getDisplayName()
            + " / " + session.getSelectedTier().getDisplayName()
            + " / " + session.getAggressiveness().getDisplayName());
        send(sender, "");

        // Group rule results by group
        Map<String, List<RuleResult>> byGroup = new LinkedHashMap<>();
        for (RuleResult r : session.getRuleResults()) {
            byGroup.computeIfAbsent(r.getRuleGroup(), k -> new ArrayList<>()).add(r);
        }

        // Display findings
        String[] groupOrder = {"safety", "consistency", "conflict", "fork-specific", "performance", "internal"};
        String[] groupColors = {"&c", "&e", "&6", "&d", "&a", "&8"};
        String[] groupIcons = {"⚠", "⚙", "⚡", "⚒", "▲", "●"};

        for (int g = 0; g < groupOrder.length; g++) {
            List<RuleResult> groupResults = byGroup.get(groupOrder[g]);
            if (groupResults == null || groupResults.isEmpty()) continue;

            send(sender, "  " + groupColors[g] + "&l" + groupIcons[g] + " "
                + groupOrder[g].toUpperCase().replace("-", " ")
                + " &8(" + groupResults.size() + ")");

            for (RuleResult r : groupResults) {
                String icon = r.getSeverity() == Severity.CRITICAL ? "&c✘"
                    : r.getSeverity() == Severity.WARNING ? "&6!" : "&7ℹ";
                send(sender, "    " + icon + " &7" + r.getWhy());
                if (r.getRecommendationText() != null && !r.getRecommendationText().isEmpty()) {
                    send(sender, "      &8→ " + r.getRecommendationText());
                }
            }
            send(sender, "");
        }

        // Show proposals summary
        long autoCount = session.getProposals().stream().filter(PatchProposal::isAutoApplicable).count();
        long manualCount = session.getProposals().stream().filter(p -> !p.isAutoApplicable()).count();
        int selected = session.getSelectedProposalIndices().size();

        send(sender, "  &eProposals: &7" + session.getProposals().size() + " total");
        send(sender, "    &a▪ Auto-apply (LessLag config): &7" + autoCount);
        send(sender, "    &6▪ Manual (server configs): &7" + manualCount);
        send(sender, "    &b▪ Selected: &7" + selected + "/" + session.getProposals().size());
        send(sender, "");

        // Show proposals list
        for (int i = 0; i < session.getProposals().size(); i++) {
            PatchProposal p = session.getProposals().get(i);
            boolean sel = session.getSelectedProposalIndices().contains(i);
            String check = sel ? "&a[✔]" : "&8[  ]";
            String scope = p.isAutoApplicable() ? "&a●" : "&6○";
            String risk = p.getRiskTag() == RiskTag.HIGH ? "&c▲"
                : p.getRiskTag() == RiskTag.MEDIUM ? "&6■" : "&a▼";

            send(sender, "  " + check + " " + scope + " &7#" + i + " &f"
                + p.getConfigKey() + " &8(" + p.getTargetFile() + ")");
            send(sender, "      &7" + p.getBeforeValue() + " → &e" + p.getAfterValue()
                + " " + risk + " &8" + p.getRationale());
        }

        send(sender, "");
        send(sender, "  &7Commands:");
        send(sender, "    &e/lg setup select all|none|<#> &8- toggle proposals");
        send(sender, "    &e/lg setup confirm &8- apply selected changes");
        send(sender, "    &e/lg setup abort &8- cancel session");
        send(sender, "");
    }

    private void handleSelect(CommandSender sender, String[] args) {
        SetupSession session = getActiveSession(sender);
        if (session == null) return;

        if (session.getStatus() != SessionStatus.REVIEW) {
            send(sender, "&cNo recommendations to select. Run review first.");
            return;
        }

        if (args.length < 3) {
            send(sender, "&cUsage: /lg setup select <all|none|#index|group-name>");
            return;
        }

        String spec = args[2].toLowerCase();

        if (spec.equals("all")) {
            session.selectAll();
            send(sender, "&a  ✔ All " + session.getProposals().size() + " proposals selected.");
        } else if (spec.equals("none")) {
            session.getSelectedProposalIndices().clear();
            send(sender, "&a  ✔ All proposals deselected.");
        } else {
            // Try as index
            try {
                int idx = Integer.parseInt(spec);
                if (session.toggle(idx)) {
                    boolean nowSelected = session.getSelectedProposalIndices().contains(idx);
                    send(sender, "&a  ✔ Proposal #" + idx + " " + (nowSelected ? "selected" : "deselected"));
                } else {
                    send(sender, "&cInvalid index: " + idx
                        + " (range: 0-" + (session.getProposals().size() - 1) + ")");
                }
            } catch (NumberFormatException e) {
                // Try as group name — toggle all proposals from rules matching this group
                boolean found = false;
                for (int i = 0; i < session.getProposals().size(); i++) {
                    PatchProposal p = session.getProposals().get(i);
                    // Find the rule for this proposal
                    for (RuleResult r : session.getRuleResults()) {
                        if (r.getRuleId().equals(p.getRuleId()) &&
                            r.getRuleGroup().equalsIgnoreCase(spec)) {
                            session.toggle(i);
                            found = true;
                        }
                    }
                }
                if (found) {
                    send(sender, "&a  ✔ Toggled proposals for group: " + spec);
                } else {
                    send(sender, "&cUnknown selector: " + spec);
                }
            }
        }
    }

    private void handleConfirm(CommandSender sender, String[] args) {
        SetupSession session = getActiveSession(sender);
        if (session == null) return;

        if (session.getStatus() != SessionStatus.REVIEW) {
            send(sender, "&cNothing to confirm. Use /lg setup review first.");
            return;
        }

        // Optionally verify sessionId
        if (args.length >= 3 && !args[2].equals(session.getSessionId())) {
            send(sender, "&cSession ID mismatch. Expected: &e" + session.getSessionId());
            return;
        }

        long toApply = session.getEffectiveProposals().stream()
            .filter(PatchProposal::isAutoApplicable).count();
        long manualOnly = session.getEffectiveProposals().stream()
            .filter(p -> !p.isAutoApplicable()).count();

        send(sender, "");
        send(sender, "&b&l  ≡ Applying Setup Changes ≡");
        send(sender, "  &7Auto-applying &e" + toApply + "&7 LessLag config changes...");
        if (manualOnly > 0) {
            send(sender, "  &6" + manualOnly + " server/fork config changes are recommendation-only.");
        }

        boolean success = advisor.confirmAndApply(session);

        if (success) {
            send(sender, "");
            send(sender, "  &a✔ Changes applied successfully!");
            send(sender, "  &7LessLag config reloaded with new settings.");
            if (session.getRollbackBundle() != null) {
                send(sender, "  &7Rollback token: &e" + session.getRollbackBundle().getRollbackToken());
                send(sender, "  &7To undo: &e/lg setup rollback " + session.getRollbackBundle().getRollbackToken());
            }
            if (manualOnly > 0) {
                send(sender, "");
                send(sender, "  &6Manual changes saved to:");
                send(sender, "    &7plugins/LessLag/setup-reports/session-" + session.getSessionId() + "-manual-patches.md");
            }
            send(sender, "  &7Full report: &7plugins/LessLag/setup-reports/");
        } else {
            send(sender, "");
            send(sender, "  &c✘ Apply failed! Config has been restored from backup.");
            send(sender, "  &7Check console for details.");
        }
        send(sender, "");
    }

    private void handleAbort(CommandSender sender) {
        SetupSession session = getActiveSession(sender);
        if (session == null) return;

        advisor.abort(session);
        send(sender, "&a  ✔ Session &e" + session.getSessionId() + "&a aborted.");
    }

    private void handleRollback(CommandSender sender, String[] args) {
        if (args.length < 3) {
            send(sender, "&cUsage: /lg setup rollback <token>");
            return;
        }

        String token = args[2];
        send(sender, "&7  Attempting rollback with token: &e" + token + "&7...");

        boolean success = advisor.rollback(token);
        if (success) {
            send(sender, "&a  ✔ Config restored from backup. Plugin reloaded.");
        } else {
            send(sender, "&c  ✘ Rollback failed. Token not found or backup corrupted.");
        }
    }

    // ── Help ────────────────────────────

    private void showSetupHelp(CommandSender sender) {
        send(sender, "");
        send(sender, "&b&l  ≡ LessLag Setup Advisor ≡");
        send(sender, "&8  Auto-configure your server for optimal performance");
        send(sender, "");
        send(sender, "  &e/lg setup start            &8- &7Run discovery + create session");
        send(sender, "  &e/lg setup profile <type>   &8- &7Choose game profile");
        send(sender, "  &e/lg setup tier [override]  &8- &7View/override hardware tier");
        send(sender, "  &e/lg setup level <level>    &8- &7Set safe/balanced/aggressive");
        send(sender, "  &e/lg setup review           &8- &7Show recommendations & tradeoffs");
        send(sender, "  &e/lg setup select <spec>    &8- &7Include/exclude proposals");
        send(sender, "  &e/lg setup confirm [id]     &8- &7Apply selected changes");
        send(sender, "  &e/lg setup abort            &8- &7Cancel session");
        send(sender, "  &e/lg setup rollback <token> &8- &7Restore previous config");
        send(sender, "");
        send(sender, "  &8Profiles: &7smp, skyblock, minigame, creative");
        send(sender, "  &8Tiers: &7low, mid, high  &8Levels: &7safe, balanced, aggressive");
        send(sender, "");
    }

    // ── Helpers ────────────────────────────

    private SetupSession getActiveSession(CommandSender sender) {
        UUID uuid = sender instanceof Player ? ((Player) sender).getUniqueId() : null;
        SetupSession session = uuid != null ? advisor.getPlayerSession(uuid) : null;

        // Console: pick the most recent active session, or null
        if (session == null && uuid == null) {
            session = advisor.getAllSessions().stream()
                .filter(SetupSession::isActive)
                .max(Comparator.comparing(SetupSession::getUpdatedAt))
                .orElse(null);
        }

        if (session == null || !session.isActive()) {
            send(sender, "&cNo active setup session. Start one with &e/lg setup start");
            return null;
        }
        return session;
    }

    private void send(CommandSender sender, String message) {
        LessLag.sendMessage(sender, message);
    }
}
