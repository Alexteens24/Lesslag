package com.lesslag.setup;

import com.lesslag.LessLag;
import com.lesslag.setup.backup.ConfigBackup;
import com.lesslag.setup.detect.*;
import com.lesslag.setup.model.*;
import com.lesslag.setup.preset.PresetMatrix;
import com.lesslag.setup.preset.PresetProfile;
import com.lesslag.setup.report.ReportGenerator;
import com.lesslag.setup.rules.RuleEngine;
import com.lesslag.util.SchedulerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Central orchestrator for the Setup Advisor wizard.
 * Manages sessions, coordinates detection/rules/presets/backup, and drives the wizard flow.
 *
 * <p>Thread safety contract:</p>
 * <ul>
 *   <li>Heavy scans and report generation run on the plugin's async executor.</li>
 *   <li>Only safe sync touches for command feedback.</li>
 *   <li>Folia: all scheduler calls go through {@link SchedulerAdapter}.</li>
 * </ul>
 */
public class SetupAdvisor {

    private static final Logger LOG = Logger.getLogger("LessLag-Setup");

    private final LessLag plugin;
    private final PlatformDetector platformDetector;
    private final ConfigAdapter configAdapter;
    private final PluginScanner pluginScanner;
    private final HardwareDetector hardwareDetector;
    private final RuleEngine ruleEngine;
    private final ReportGenerator reportGenerator;
    private final ConfigBackup configBackup;

    /** Active sessions keyed by sessionId. */
    private final Map<String, SetupSession> sessions = new ConcurrentHashMap<>();
    /** Maps player UUID → active sessionId (one session per player). */
    private final Map<UUID, String> playerSessions = new ConcurrentHashMap<>();

    public SetupAdvisor(LessLag plugin) {
        this.plugin = plugin;
        this.platformDetector = new PlatformDetector();
        this.configAdapter = new ConfigAdapter();
        this.pluginScanner = new PluginScanner();
        this.hardwareDetector = new HardwareDetector();
        this.ruleEngine = new RuleEngine();
        this.reportGenerator = new ReportGenerator(plugin.getDataFolder());
        this.configBackup = new ConfigBackup(plugin.getDataFolder());
    }

    // ── Session Management ────────────────────────────

    /**
     * Start a new setup session. Runs discovery async.
     *
     * @param creatorUuid player UUID (or null for console)
     * @param creatorName player/console name
     * @param callback    called on main thread when discovery is complete
     */
    public void startSession(UUID creatorUuid, String creatorName,
                              java.util.function.Consumer<SetupSession> callback) {
        // Check for existing session
        if (creatorUuid != null && playerSessions.containsKey(creatorUuid)) {
            String existingId = playerSessions.get(creatorUuid);
            SetupSession existing = sessions.get(existingId);
            if (existing != null && existing.isActive()) {
                callback.accept(existing);
                return;
            }
            // Clean up stale session
            playerSessions.remove(creatorUuid);
            sessions.remove(existingId);
        }

        String sessionId = generateSessionId();
        SetupSession session = new SetupSession(sessionId, creatorUuid, creatorName);
        sessions.put(sessionId, session);
        if (creatorUuid != null) {
            playerSessions.put(creatorUuid, sessionId);
        }

        // Run discovery async
        plugin.getAsyncExecutor().submit(() -> {
            try {
                runDiscovery(session);
                // Callback on global scheduler for safe command output
                SchedulerAdapter adapter = new SchedulerAdapter(plugin);
                adapter.runGlobal(() -> callback.accept(session));
            } catch (Exception e) {
                LOG.severe("Discovery failed: " + e.getMessage());
                session.setStatus(SessionStatus.FAILED);
                SchedulerAdapter adapter = new SchedulerAdapter(plugin);
                adapter.runGlobal(() -> callback.accept(session));
            }
        });
    }

    /**
     * Run the full discovery phase (async-safe).
     */
    private void runDiscovery(SetupSession session) {
        LOG.info("Starting discovery for session " + session.getSessionId() + "...");

        // Platform detection
        platformDetector.detect();

        // Config scan
        configAdapter.scan();

        // Plugin scan (needs to be on main thread for Bukkit API)
        // We'll capture the snapshot data that's safe to read async
        pluginScanner.scan();

        // Hardware assessment (entirely MXBean-based, thread-safe)
        HardwareAssessment hw = hardwareDetector.assess();
        session.setHardwareAssessment(hw);

        // Build environment snapshot
        EnvironmentSnapshot env = new EnvironmentSnapshot();
        env.setPlatformName(platformDetector.getDetectedPlatform());
        env.setPlatformVersion(platformDetector.getPlatformVersion());
        env.setMinecraftVersion(platformDetector.getMinecraftVersion());
        env.setFoliaDetected(platformDetector.isFolia());
        env.getConfigFilesPresent().putAll(configAdapter.getFilePresence());
        env.getPlugins().addAll(pluginScanner.getDiscoveredPlugins());

        // Runtime metrics (safe to read)
        try {
            env.setOnlinePlayers(Bukkit.getOnlinePlayers().size());
            int chunks = 0, entities = 0;
            for (World w : Bukkit.getWorlds()) {
                chunks += w.getLoadedChunks().length;
                entities += w.getEntities().size();
            }
            env.setLoadedChunks(chunks);
            env.setTotalEntities(entities);
            env.setLoadedWorldCount(Bukkit.getWorlds().size());

            // TPS/MSPT from our monitor
            if (plugin.getTpsMonitor() != null) {
                env.setCurrentTps(plugin.getTpsMonitor().getCurrentTPS());
                env.setCurrentMspt(plugin.getTpsMonitor().getCurrentMSPT());
            }
        } catch (Exception e) {
            LOG.warning("Failed to capture runtime metrics: " + e.getMessage());
        }

        session.setEnvironment(env);
        session.setStatus(SessionStatus.PROFILING);

        LOG.info("Discovery complete for session " + session.getSessionId()
            + ". Platform: " + env.getPlatformName()
            + ", Tier: " + hw.getDetectedTier()
            + " (conf: " + String.format("%.0f%%", hw.getConfidenceScore() * 100) + ")");
    }

    /**
     * Set the game profile for a session and trigger recommendation generation.
     */
    public void setProfile(SetupSession session, GameProfile profile) {
        session.setSelectedProfile(profile);
    }

    /**
     * Set or confirm the hardware tier.
     */
    public void setTier(SetupSession session, HardwareTier tier) {
        session.setSelectedTier(tier);
    }

    /**
     * Set aggressiveness level.
     */
    public void setAggressiveness(SetupSession session, AggressivenessLevel level) {
        session.setAggressiveness(level);
    }

    /**
     * Generate recommendations based on session profile/tier.
     * Runs rules engine + preset matrix. Call async.
     */
    public void generateRecommendations(SetupSession session,
                                         java.util.function.Consumer<SetupSession> callback) {
        plugin.getAsyncExecutor().submit(() -> {
            try {
                HardwareTier effectiveTier = session.getSelectedTier() != null
                    ? session.getSelectedTier()
                    : session.getHardwareAssessment().getDetectedTier();

                // Apply load modifier
                if (session.getEnvironment() != null) {
                    effectiveTier = PresetMatrix.applyLoadModifier(
                        effectiveTier, session.getEnvironment().getOnlinePlayers());
                }

                GameProfile profile = session.getSelectedProfile() != null
                    ? session.getSelectedProfile() : GameProfile.SMP;

                // Run rules engine
                RuleEngine.EvaluationResult eval = ruleEngine.evaluate(
                    platformDetector, configAdapter, pluginScanner,
                    session.getHardwareAssessment(),
                    profile, effectiveTier, session.getAggressiveness());

                session.getRuleResults().clear();
                session.getRuleResults().addAll(eval.getResults());
                session.getProposals().clear();
                session.getProposals().addAll(eval.getProposals());

                // Select all by default
                session.selectAll();
                session.setStatus(SessionStatus.REVIEW);

                // Generate report
                reportGenerator.generate(session, pluginScanner.computePluginListHash());

                SchedulerAdapter adapter = new SchedulerAdapter(plugin);
                adapter.runGlobal(() -> callback.accept(session));
            } catch (Exception e) {
                LOG.severe("Recommendation generation failed: " + e.getMessage());
                e.printStackTrace();
                SchedulerAdapter adapter = new SchedulerAdapter(plugin);
                adapter.runGlobal(() -> callback.accept(session));
            }
        });
    }

    /**
     * Confirm and apply the selected proposals for a session.
     *
     * @return true if apply succeeded
     */
    public boolean confirmAndApply(SetupSession session) {
        if (session.getStatus() != SessionStatus.REVIEW) {
            return false;
        }

        session.setStatus(SessionStatus.CONFIRMED);

        // Create backup
        RollbackBundle bundle = configBackup.createSnapshot(session.getSessionId());
        if (bundle == null) {
            session.setStatus(SessionStatus.FAILED);
            return false;
        }
        session.setRollbackBundle(bundle);

        // Apply only auto-applicable proposals that are selected
        List<PatchProposal> toApply = new ArrayList<>();
        for (PatchProposal p : session.getEffectiveProposals()) {
            if (p.isAutoApplicable()) {
                toApply.add(p);
            }
        }

        if (toApply.isEmpty()) {
            session.setStatus(SessionStatus.APPLIED);
            return true;
        }

        List<PatchProposal> applied = configBackup.applyPatches(
            bundle, toApply, plugin.getConfig());

        if (applied.isEmpty()) {
            session.setStatus(SessionStatus.FAILED);
            return false;
        }

        // Write diff report
        reportGenerator.writeAppliedDiff(session, applied);

        // Reload plugin config
        plugin.reloadPlugin();
        session.setStatus(SessionStatus.APPLIED);

        LOG.info("Session " + session.getSessionId() + " applied "
            + applied.size() + " config changes.");
        return true;
    }

    /**
     * Rollback applied changes from a session or token.
     *
     * @param token rollback token (from the session)
     * @return true if rollback succeeded
     */
    public boolean rollback(String token) {
        // Search by token in sessions first
        for (SetupSession s : sessions.values()) {
            RollbackBundle b = s.getRollbackBundle();
            if (b != null && b.getRollbackToken().equals(token)) {
                if (configBackup.restore(b)) {
                    plugin.reloadPlugin();
                    s.setStatus(SessionStatus.ROLLED_BACK);
                    return true;
                }
                return false;
            }
        }

        // Search backup directory
        RollbackBundle found = configBackup.findByToken(token);
        if (found != null && configBackup.restore(found)) {
            plugin.reloadPlugin();
            return true;
        }

        return false;
    }

    /**
     * Abort an active session.
     */
    public void abort(SetupSession session) {
        session.setStatus(SessionStatus.ABORTED);
        if (session.getCreatorUuid() != null) {
            playerSessions.remove(session.getCreatorUuid());
        }
    }

    // ── Lookup ────────────────────────────

    public SetupSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    public SetupSession getPlayerSession(UUID playerUuid) {
        String id = playerSessions.get(playerUuid);
        return id != null ? sessions.get(id) : null;
    }

    public Collection<SetupSession> getAllSessions() {
        return Collections.unmodifiableCollection(sessions.values());
    }

    // ── Internal ────────────────────────────

    private String generateSessionId() {
        return Long.toHexString(System.currentTimeMillis()).substring(4)
            + "-" + UUID.randomUUID().toString().substring(0, 4);
    }

    /**
     * Shut down the advisor, aborting all active sessions.
     */
    public void shutdown() {
        for (SetupSession session : sessions.values()) {
            if (session.getStatus() != SessionStatus.APPLIED
                    && session.getStatus() != SessionStatus.ABORTED
                    && session.getStatus() != SessionStatus.FAILED
                    && session.getStatus() != SessionStatus.ROLLED_BACK) {
                session.setStatus(SessionStatus.ABORTED);
            }
        }
        sessions.clear();
        playerSessions.clear();
    }
}
