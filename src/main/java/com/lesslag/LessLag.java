package com.lesslag;

import com.lesslag.action.ActionExecutor;
import com.lesslag.command.LagCommand;
import com.lesslag.command.LagTabCompleter; // Resync
import com.lesslag.monitor.ChunkLimiter;
import com.lesslag.monitor.FrustumCuller;
import com.lesslag.monitor.WorldChunkGuard;
import com.lesslag.monitor.MemoryLeakDetector;
import com.lesslag.monitor.GCMonitor;
import com.lesslag.monitor.LagSourceAnalyzer;
import com.lesslag.monitor.BottleneckAnalyzer;
import com.lesslag.monitor.PredictiveOptimizer;
import com.lesslag.monitor.RedstoneMonitor;
import com.lesslag.monitor.TPSMonitor;
import com.lesslag.monitor.TickMonitor;
import com.lesslag.monitor.VillagerOptimizer;
import com.lesslag.monitor.BreedingLimiter;
import com.lesslag.monitor.DensityOptimizer;
import com.lesslag.monitor.MovementLimiter;
import com.lesslag.monitor.BlockPlacementLimiter;
import com.lesslag.monitor.SpawnerLimiter;
import com.lesslag.monitor.MobFarmOptimizer;
import com.lesslag.util.CompatibilityManager;
import com.lesslag.util.ConfigUpdater;
import com.lesslag.util.SchedulerAdapter;
import com.lesslag.web.LessLagApiClient;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;


import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class LessLag extends JavaPlugin implements Listener {

    private static LessLag instance;
    private TPSMonitor tpsMonitor;
    private TickMonitor tickMonitor;
    private GCMonitor gcMonitor;
    private ActionExecutor actionExecutor;
    private LagSourceAnalyzer lagSourceAnalyzer;
    private BottleneckAnalyzer bottleneckAnalyzer;
    private ChunkLimiter chunkLimiter;
    private RedstoneMonitor redstoneMonitor;
    private VillagerOptimizer villagerOptimizer;
    private BreedingLimiter breedingLimiter;
    private DensityOptimizer densityOptimizer;
    private PredictiveOptimizer predictiveOptimizer;
    private FrustumCuller frustumCuller;
    private WorldChunkGuard worldChunkGuard;
    private MovementLimiter movementLimiter;
    private BlockPlacementLimiter blockPlacementLimiter;
    private SpawnerLimiter spawnerLimiter;
    private MobFarmOptimizer mobFarmOptimizer;
    private MemoryLeakDetector memoryLeakDetector;
    private CompatibilityManager compatManager;
    private PremiumService premiumService;

    // Web integration
    private LessLagApiClient apiClient;
    private final Map<String, Map<String, Object>> pendingPatches = new ConcurrentHashMap<>();
    private SchedulerAdapter.TaskHandle heartbeatTask;
    private SchedulerAdapter.TaskHandle applyQueueTask;

    // Shared async executor for all monitoring tasks
    private ExecutorService asyncExecutor;

    // Original per-world values for recovery
    private final Map<String, Integer> originalViewDistances = new HashMap<>();
    private final Map<String, Integer> originalSimulationDistances = new HashMap<>();



    // Workload Distributor
    private final WorkloadDistributor workloadDistributor = new WorkloadDistributor();

    @Override
    public void onEnable() {
        if (Bukkit.getServer() == null) {
            getFallbackLogger().warning("Skipping enable because server runtime is unavailable.");
            return;
        }

        instance = this;
        saveDefaultConfig();
        // Auto-migrate config.yml: merge missing keys from the bundled default
        ConfigUpdater.update(this);
        reloadConfig();
        saveResource("messages.yml", false);

        // Initialize Folia scheduler adapter (must be early)
        SchedulerAdapter.init();
        getLogger().info("Running on " + (SchedulerAdapter.isFolia() ? "Folia (regionalised)" : "Paper"));

        String minVersion = getConfig().getString("compatibility.min-version", "1.20.4");
        boolean allowUnsupported = getConfig().getBoolean("compatibility.allow-unsupported-versions", false);
        if (!isVersionAtLeast(minVersion)) {
            if (!allowUnsupported) {
                getLogger().severe("LessLag requires Minecraft " + minVersion + " or newer.");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            getLogger().warning("Running on unsupported Minecraft version " + Bukkit.getMinecraftVersion()
                    + ". Fallbacks will be used where possible.");
        }

        // Store original per-world settings
        for (World world : getServer().getWorlds()) {
            originalViewDistances.put(world.getName(), world.getViewDistance());
            originalSimulationDistances.put(world.getName(), world.getSimulationDistance());
        }

        getServer().getPluginManager().registerEvents(this, this);

        // Create async executor (4 threads: monitoring + analysis)
        asyncExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "LessLag-Async");
            t.setDaemon(true);
            return t;
        });

        // Initialize WorkloadDistributor config
        workloadDistributor.reloadConfig();

        // Initialize components
        initializeMonitors();

        // Register commands
        LagCommand lagCommand = new LagCommand(this);
        if (getCommand("lg") != null) {
            getCommand("lg").setExecutor(lagCommand);
            getCommand("lg").setTabCompleter(new LagTabCompleter());
        } else {
            getLogger().severe("Command 'lg' not found in plugin.yml. Commands will be unavailable.");
        }

        getLogger().info("========================================");
        getLogger().info("  LessLag v" + getPluginMeta().getVersion() + " - Enabled!");
        getLogger().info("  Server Performance Guardian (Async)");
        getLogger().info("  Monitoring TPS, Ticks, GC & more");
        getLogger().info("========================================");

        // Initialize bStats Metrics
        int pluginId = 29516;
        org.bstats.bukkit.Metrics metrics = new org.bstats.bukkit.Metrics(this, pluginId);

        // Custom Charts
        metrics.addCustomChart(new org.bstats.charts.SimplePie("redstone_monitor_enabled",
                () -> String.valueOf(getConfig().getBoolean("modules.redstone.enabled", true))));

        metrics.addCustomChart(new org.bstats.charts.SimplePie("villager_optimizer_enabled",
                () -> String.valueOf(getConfig().getBoolean("modules.villager-optimizer.enabled", true))));

        metrics.addCustomChart(new org.bstats.charts.SimplePie("density_optimizer_enabled",
                () -> String.valueOf(getConfig().getBoolean("modules.density-optimizer.enabled", true))));

        // Initialize Premium Manager via Reflection
        try {
            Class<?> clazz = Class.forName("com.lesslag.premium.PremiumManager");
            java.lang.reflect.Constructor<?> constructor = clazz.getConstructor(LessLag.class);
            premiumService = (PremiumService) constructor.newInstance(this);
            getLogger().info("Premium features loaded.");
        } catch (ClassNotFoundException e) {
            premiumService = new NoOpPremiumService();
            getLogger().info("Running Free/Lite version (Premium features not found).");
        } catch (Exception e) {
            getLogger().warning("Failed to load Premium features: " + e.getMessage());
            premiumService = new NoOpPremiumService();
        }

        // Initialize web integration (server registration, heartbeat, apply-queue)
        initWebIntegration();

        // Schedule startup drift check (runs 5 s after enable to avoid startup noise)
        SchedulerAdapter.runGlobalDelayed(this, this::runDriftCheckOnStartup, 100L);
    }

    private void initializeMonitors() {
        actionExecutor = new ActionExecutor(this);

        // Run compatibility detection BEFORE creating monitors
        // (may adjust config values to avoid conflicts)
        compatManager = new CompatibilityManager(this);
        compatManager.detect();
        lagSourceAnalyzer = new LagSourceAnalyzer(this);
        bottleneckAnalyzer = new BottleneckAnalyzer(this);
        predictiveOptimizer = new PredictiveOptimizer(this, actionExecutor);
        tpsMonitor = new TPSMonitor(this, actionExecutor, lagSourceAnalyzer, predictiveOptimizer);
        tickMonitor = new TickMonitor(this);
        gcMonitor = new GCMonitor(this);
        chunkLimiter = new ChunkLimiter(this);
        redstoneMonitor = new RedstoneMonitor(this);
        frustumCuller = new FrustumCuller(this);
        worldChunkGuard = new WorldChunkGuard(this, actionExecutor);
        movementLimiter = new MovementLimiter(this);
        blockPlacementLimiter = new BlockPlacementLimiter(this);
        spawnerLimiter = new SpawnerLimiter(this);
        mobFarmOptimizer = new MobFarmOptimizer(this);
        memoryLeakDetector = new MemoryLeakDetector(this);
        villagerOptimizer = new VillagerOptimizer(this);
        breedingLimiter = new BreedingLimiter(this);
        densityOptimizer = new DensityOptimizer(this);

        // Start monitoring
        tpsMonitor.start();
        tickMonitor.start();
        gcMonitor.start();
        bottleneckAnalyzer.start();
        chunkLimiter.start();
        redstoneMonitor.start();
        frustumCuller.start();
        worldChunkGuard.start();
        movementLimiter.start();
        blockPlacementLimiter.start();
        spawnerLimiter.start();
        mobFarmOptimizer.start();
        memoryLeakDetector.start();
        villagerOptimizer.start();
        breedingLimiter.start();
        densityOptimizer.start();
    }

    private void stopMonitors() {
        if (tpsMonitor != null)
            tpsMonitor.stop();
        if (tickMonitor != null)
            tickMonitor.stop();
        if (gcMonitor != null)
            gcMonitor.stop();
        if (bottleneckAnalyzer != null)
            bottleneckAnalyzer.stop();
        if (chunkLimiter != null)
            chunkLimiter.stop();
        if (redstoneMonitor != null)
            redstoneMonitor.stop();
        if (frustumCuller != null)
            frustumCuller.stop();
        if (worldChunkGuard != null)
            worldChunkGuard.stop();
        if (movementLimiter != null)
            movementLimiter.stop();
        if (blockPlacementLimiter != null)
            blockPlacementLimiter.stop();
        if (spawnerLimiter != null)
            spawnerLimiter.stop();
        if (mobFarmOptimizer != null)
            mobFarmOptimizer.stop();
        if (memoryLeakDetector != null)
            memoryLeakDetector.stop();
        if (villagerOptimizer != null)
            villagerOptimizer.stop();
        if (breedingLimiter != null)
            breedingLimiter.stop();
        if (densityOptimizer != null)
            densityOptimizer.stop();
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
            heartbeatTask = null;
        }
        if (applyQueueTask != null) {
            applyQueueTask.cancel();
            applyQueueTask = null;
        }
    }

    @Override
    public void onDisable() {
        stopMonitors();

        if (workloadDistributor != null) {
            workloadDistributor.shutdown();
        }

        // Restore original settings synchronously (no scheduler available during
        // disable)
        if (actionExecutor != null) {
            actionExecutor.restoreDefaultsSync();
        }

        // Shutdown async executor gracefully
        if (asyncExecutor != null && !asyncExecutor.isShutdown()) {
            asyncExecutor.shutdown();
            try {
                if (!asyncExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                    asyncExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                asyncExecutor.shutdownNow();
            }
        }

        getSafeLogger().info("LessLag disabled. Server settings restored.");
        instance = null;
    }

    public java.util.logging.Logger getSafeLogger() {
        java.util.logging.Logger logger = null;
        try {
            logger = getLogger();
        } catch (Exception ex) {
            // Fall through to fallback logger.
        }
        return logger != null ? logger : getFallbackLogger();
    }

    private java.util.logging.Logger getFallbackLogger() {
        return java.util.logging.Logger.getLogger("LessLag");
    }

    public void reloadPlugin() {
        reloadConfig();
        workloadDistributor.reloadConfig();
        stopMonitors();
        initializeMonitors();
    }

    // ── Web Integration ────────────────────────────────────────────────────

    /**
     * Called on startup (delayed 5 s) to silently check if the current config.yml
     * has drifted from the last applied {@code lesslag-config.json}.
     * Logs a warning to console; does not spam ops.
     */
    private void runDriftCheckOnStartup() {
        SchedulerAdapter.runAsync(this, () -> {
            List<String> drifted = detectConfigDrift();
            if (!drifted.isEmpty()) {
                getLogger().warning("[LessLag] Config drift detected — " + drifted.size()
                        + " key(s) differ from lesslag-config.json: " + String.join(", ", drifted));
                getLogger().warning("[LessLag] Run '/lg verify' to view details or '/lg apply' to re-apply.");
            }
        });
    }

    /**
     * Run a drift check and send a formatted report to {@code sender}.
     * Called by {@code /lg drift}.
     *
     * @param sender the command sender (may be console or a player)
     */
    public void performDriftCheck(CommandSender sender) {
        sendMessage(sender, getPrefix() + "<gray>Checking for config drift...");
        SchedulerAdapter.runAsync(this, () -> {
            List<String> drifted = detectConfigDrift();
            SchedulerAdapter.runGlobal(this, () -> {
                if (drifted.isEmpty()) {
                    sendMessage(sender, getPrefix() + "<green>No drift detected. Config matches lesslag-config.json.");
                } else {
                    sendMessage(sender, "");
                    sendMessage(sender, "<yellow><bold>  ⚠ Config drift detected! (" + drifted.size() + " key(s))");
                    for (String key : drifted) {
                        sendMessage(sender, "    <red>✗ <gray>" + key);
                    }
                    sendMessage(sender, "");
                    sendMessage(sender, "  <gray>Run <aqua>/lg apply<gray> to re-apply lesslag-config.json.");
                    sendMessage(sender, "");
                }
            });
        });
    }

    /**
     * Compare {@code plugins/LessLag/lesslag-config.json}'s {@code lesslag} block
     * against the live config.yml. Returns a list of keys that differ.
     */
    private List<String> detectConfigDrift() {
        java.io.File configJson = new java.io.File(getDataFolder(), "lesslag-config.json");
        if (!configJson.exists())
            return java.util.Collections.emptyList();

        List<String> drifted = new java.util.ArrayList<>();
        try (java.io.FileReader reader = new java.io.FileReader(configJson)) {
            com.google.gson.JsonObject root = new com.google.gson.Gson()
                    .fromJson(reader, com.google.gson.JsonObject.class);
            if (!root.has("lesslag") || !root.get("lesslag").isJsonObject())
                return drifted;

            com.google.gson.JsonObject lesslagBlock = root.getAsJsonObject("lesslag");
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : lesslagBlock.entrySet()) {
                String key = entry.getKey();
                String expected = entry.getValue().getAsString();
                Object actual = getConfig().get(key);
                if (actual == null || !actual.toString().equals(expected)) {
                    drifted.add(key);
                }
            }
        } catch (Exception e) {
            getLogger().warning("[LessLag] Drift check failed: " + e.getMessage());
        }
        return drifted;
    }

    /**
     * Initialize web integration: build the API client, register the server if
     * needed, then schedule the heartbeat and (optionally) apply-queue poller.
     */
    private void initWebIntegration() {
        String apiUrl = getConfig().getString("web.api-url", "https://lesslag-api.daucatmoitu.workers.dev");
        String storedId = getConfig().getString("web.server-id", "");
        String storedSecret = getConfig().getString("web.server-secret", "");

        boolean hasCredentials = storedId != null && !storedId.isBlank()
                && storedSecret != null && !storedSecret.isBlank();
        apiClient = new LessLagApiClient(apiUrl,
                hasCredentials ? storedId : null,
                hasCredentials ? storedSecret : null);

        // Warn on rules version drift (non-blocking)
        apiClient.warnOnRulesVersionDrift(this);

        if (!hasCredentials) {
            // Auto-register on first run
            String motd;
            try {
                motd = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(Bukkit.getServer().motd());
            } catch (Exception e) {
                motd = "Minecraft Server";
            }
            apiClient.registerServer(motd).thenAccept(json -> {
                String newId = LessLagApiClient.extractFromJson(json, "serverId");
                String newSecret = LessLagApiClient.extractFromJson(json, "serverSecret");
                if (newId == null || newSecret == null)
                    return;

                apiClient.setCredentials(newId, newSecret);

                // Persist to config.yml
                getConfig().set("web.server-id", newId);
                getConfig().set("web.server-secret", newSecret);
                saveConfig();
                getLogger().info("[LessLag Web] Registered new server identity: " + newId);

                scheduleHeartbeat();
                scheduleApplyQueuePoll();
            }).exceptionally(ex -> {
                getLogger().warning("[LessLag Web] Server registration failed: " + ex.getMessage());
                return null;
            });
        } else {
            scheduleHeartbeat();
            scheduleApplyQueuePoll();
        }
    }

    /** Schedule recurring heartbeat (default: every 30 s). */
    private void scheduleHeartbeat() {
        if (!getConfig().getBoolean("web.heartbeat.enabled", true))
            return;
        int intervalSec = getConfig().getInt("web.heartbeat.interval-seconds", 30);
        long periodTicks = intervalSec * 20L;
        heartbeatTask = SchedulerAdapter.runAsyncRepeating(this, () -> {
            Map<String, Object> payload = LessLagApiClient.buildHeartbeatPayload(this);
            apiClient.sendHeartbeat(payload).exceptionally(ex -> {
                // Suppress routine network errors silently
                return null;
            });
        }, periodTicks, periodTicks);
    }

    /** Schedule recurring apply-queue poll (default: every 60 s). */
    private void scheduleApplyQueuePoll() {
        if (!getConfig().getBoolean("web.apply-queue.enabled", false))
            return;
        int intervalSec = getConfig().getInt("web.apply-queue.poll-interval-seconds", 60);
        long periodTicks = intervalSec * 20L;
        applyQueueTask = SchedulerAdapter.runAsyncRepeating(this,
                () -> apiClient.pollApplyQueue().thenAccept(patches -> {
                    for (Map<String, Object> patch : patches)
                        handleIncomingPatch(patch);
                }).exceptionally(ex -> null),
                periodTicks * 2, periodTicks);
    }

    /**
     * Receive a patch map from the apply-queue and decide whether to
     * auto-apply it or hold it pending a player {@code /lg confirm} command.
     */
    void handleIncomingPatch(Map<String, Object> patch) {
        String patchId = String.valueOf(patch.getOrDefault("patchId", ""));
        if (patchId.isBlank() || pendingPatches.containsKey(patchId))
            return;

        // Derive overall risk level from the highest riskTag across proposals
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> proposals = (List<Map<String, Object>>) patch.getOrDefault("proposals", List.of());
        String riskLevel = deriveRiskLevel(proposals);
        boolean isAggressive = "AGGRESSIVE".equals(riskLevel);

        int autoSec = getConfig().getInt("web.apply-queue.auto-confirm-seconds", 300);

        if (!isAggressive && autoSec > 0) {
            // Auto-apply once after the configured delay (one-shot, not repeating)
            pendingPatches.put(patchId, patch);
            SchedulerAdapter.runAsyncDelayed(this, () -> {
                if (pendingPatches.remove(patchId) != null) {
                    applyPatch(patchId, patch);
                }
            }, autoSec * 20L);
        } else {
            // Queue for manual confirm — notify online ops via Folia-compatible runGlobal
            pendingPatches.put(patchId, patch);
            String shortId = patchId.length() >= 8 ? patchId.substring(0, 8) : patchId;
            SchedulerAdapter.runGlobal(this,
                    () -> Bukkit.broadcast(MiniMessage.miniMessage().deserialize(
                            "<aqua>[LessLag] <gray>New web patch (<yellow>" + riskLevel + "<gray>, " + proposals.size()
                                    + " change(s)). Confirm with <aqua>/lg confirm " + shortId)));
        }
    }

    /**
     * Returns the highest risk level found across a list of proposals.
     * Ranking: SAFE < BALANCED < AGGRESSIVE (case-insensitive).
     */
    private static String deriveRiskLevel(List<Map<String, Object>> proposals) {
        int max = 0; // 0=SAFE, 1=BALANCED, 2=AGGRESSIVE
        for (Map<String, Object> p : proposals) {
            String tag = String.valueOf(p.getOrDefault("riskTag", "safe")).toLowerCase();
            if (tag.equals("aggressive"))
                max = Math.max(max, 2);
            else if (tag.equals("balanced"))
                max = Math.max(max, 1);
        }
        return max == 2 ? "AGGRESSIVE" : max == 1 ? "BALANCED" : "SAFE";
    }

    /** Apply a patch by writing the key/value entries to config. */
    @SuppressWarnings("unchecked")
    private void applyPatch(String patchId, Map<String, Object> patch) {
        double msptBefore = tpsMonitor != null ? tpsMonitor.getCurrentMSPT() : 0;
        // apply-queue uses PatchProposalLike: { targetFile, configKey, beforeValue,
        // afterValue }
        List<Map<String, Object>> proposals = (List<Map<String, Object>>) patch.getOrDefault("proposals", List.of());
        for (Map<String, Object> proposal : proposals) {
            String file = String.valueOf(proposal.getOrDefault("targetFile", ""));
            String key = String.valueOf(proposal.getOrDefault("configKey", ""));
            Object value = proposal.get("afterValue");
            if (!file.isBlank() && !key.isBlank() && value != null) {
                getLogger().info("[LessLag Web] Applying patch: " + file + " " + key + " = " + value);
                // Only config.yml keys are applied directly via the Bukkit config object
                if ("config.yml".equals(file)) {
                    getConfig().set(key, value);
                }
                // Other files (paper.yml, spigot.yml, …) require per-format handling —
                // left as an extension point.
            }
        }
        saveConfig();
        double msptAfter = tpsMonitor != null ? tpsMonitor.getCurrentMSPT() : 0;
        apiClient.confirmPatch(patchId, "APPLIED", msptBefore, msptAfter)
                .exceptionally(ex -> null);
    }

    /**
     * Called by {@link com.lesslag.command.LagCommand} when a player runs
     * {@code /lg confirm <shortId>}.
     */
    public void confirmPendingPatch(String shortId, CommandSender sender) {
        if (shortId == null || shortId.isBlank()) {
            if (pendingPatches.isEmpty()) {
                sender.sendMessage(MiniMessage.miniMessage()
                        .deserialize("<aqua>[LessLag] <gray>No pending patches."));
            } else {
                sender.sendMessage(MiniMessage.miniMessage()
                        .deserialize("<aqua>[LessLag] <gray>Pending patches:"));
                for (String id : pendingPatches.keySet()) {
                    String sid = id.length() >= 8 ? id.substring(0, 8) : id;
                    sender.sendMessage(MiniMessage.miniMessage()
                            .deserialize("  <yellow>" + sid));
                }
            }
            return;
        }
        String matchedId = pendingPatches.keySet().stream()
                .filter(id -> id.startsWith(shortId) || id.equals(shortId))
                .findFirst().orElse(null);
        if (matchedId == null) {
            sender.sendMessage(MiniMessage.miniMessage()
                    .deserialize("<aqua>[LessLag] <red>No patch matching '" + shortId + "'."));
            return;
        }
        Map<String, Object> patch = pendingPatches.remove(matchedId);
        if (patch != null) {
            applyPatch(matchedId, patch);
            sender.sendMessage(MiniMessage.miniMessage()
                    .deserialize("<aqua>[LessLag] <green>Patch applied successfully."));
        }
    }

    public LessLagApiClient getApiClient() {
        return apiClient;
    }

    // ── Getters ────────────────────────────

    public static LessLag getInstance() {
        return instance;
    }

    public TPSMonitor getTpsMonitor() {
        return tpsMonitor;
    }

    public TickMonitor getTickMonitor() {
        return tickMonitor;
    }

    public GCMonitor getGcMonitor() {
        return gcMonitor;
    }

    public ActionExecutor getActionExecutor() {
        return actionExecutor;
    }

    public LagSourceAnalyzer getLagSourceAnalyzer() {
        return lagSourceAnalyzer;
    }

    public BottleneckAnalyzer getBottleneckAnalyzer() {
        return bottleneckAnalyzer;
    }

    public ChunkLimiter getChunkLimiter() {
        return chunkLimiter;
    }

    public RedstoneMonitor getRedstoneMonitor() {
        return redstoneMonitor;
    }

    public VillagerOptimizer getVillagerOptimizer() {
        return villagerOptimizer;
    }

    public BreedingLimiter getBreedingLimiter() {
        return breedingLimiter;
    }

    public DensityOptimizer getDensityOptimizer() {
        return densityOptimizer;
    }

    public PredictiveOptimizer getPredictiveOptimizer() {
        return predictiveOptimizer;
    }

    public FrustumCuller getFrustumCuller() {
        return frustumCuller;
    }

    public WorldChunkGuard getWorldChunkGuard() {
        return worldChunkGuard;
    }

    public MovementLimiter getMovementLimiter() {
        return movementLimiter;
    }

    public BlockPlacementLimiter getBlockPlacementLimiter() {
        return blockPlacementLimiter;
    }

    public CompatibilityManager getCompatManager() {
        return compatManager;
    }

    public MemoryLeakDetector getMemoryLeakDetector() {
        return memoryLeakDetector;
    }

    public PremiumService getPremiumManager() {
        return premiumService;
    }

    public ExecutorService getAsyncExecutor() {
        return asyncExecutor;
    }

    public int getOriginalViewDistance(World world) {
        return originalViewDistances.getOrDefault(world.getName(), world.getViewDistance());
    }

    public int getOriginalSimulationDistance(World world) {
        return originalSimulationDistances.getOrDefault(world.getName(), world.getSimulationDistance());
    }

    public static boolean hasCustomName(Entity entity) {
        if (entity == null)
            return false;
        return entity.customName() != null;
    }

    public boolean isMobAwareSafe(Mob mob) {
        if (mob == null)
            return true;
        try {
            return mob.isAware();
        } catch (IllegalStateException ignored) {
            return true;
        }
    }

    public boolean setMobAwareSafe(Mob mob, boolean aware) {
        if (mob == null)
            return false;
        try {
            mob.setAware(aware);
            return true;
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    /**
     * Parse a message to a Component.
     * <p>
     * Native MiniMessage tags ({@code <red>}, {@code <bold>}, …) are parsed directly.
     * Legacy Bukkit {@code &x} / {@code §x} color codes are supported for backward
     * compatibility with config-supplied strings — they are deserialized via
     * {@link net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer}.
     * Internally authored strings should use MiniMessage natively.
     */
    public static Component colorize(String message) {
        if (message == null) return Component.empty();
        // Normalize § prefix variant used in some configs
        if (message.contains("§")) {
            message = message.replace('§', '&');
        }
        // If any legacy &x codes remain (config-sourced strings), use legacy deserializer.
        // Pure MiniMessage strings won't contain bare '&' followed by a hex char, so this
        // guard is a reliable fast-path discriminator.
        if (message.contains("&")) {
            return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                    .legacyAmpersand()
                    .deserialize(message);
        }
        return MiniMessage.miniMessage().deserialize(message);
    }

    public static void sendMessage(CommandSender sender, String message) {
        sender.sendMessage(colorize(message));
    }

    public static void sendActionBar(Player player, String message) {
        player.sendActionBar(colorize(message));
    }

    public String getPrefix() {
        return getConfig().getString("core.prefix", "<dark_gray>[<red><bold>LessLag<dark_gray>] <reset>");
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        World world = event.getWorld();
        originalViewDistances.putIfAbsent(world.getName(), world.getViewDistance());
        originalSimulationDistances.putIfAbsent(world.getName(), world.getSimulationDistance());
    }



    private boolean isVersionAtLeast(String minVersion) {
        String[] parts = minVersion.split("\\.");
        int major = parts.length > 0 ? parseIntSafe(parts[0]) : 1;
        int minor = parts.length > 1 ? parseIntSafe(parts[1]) : 0;
        int patch = parts.length > 2 ? parseIntSafe(parts[2]) : 0;
        return isVersionAtLeast(major, minor, patch);
    }

    private boolean isVersionAtLeast(int major, int minor, int patch) {
        String version = Bukkit.getMinecraftVersion();
        String[] parts = version.split("\\.");
        if (parts.length < 2) {
            return false;
        }
        int vMajor = parseIntSafe(parts[0]);
        int vMinor = parseIntSafe(parts[1]);
        int vPatch = parts.length > 2 ? parseIntSafe(parts[2]) : 0;

        if (vMajor != major) {
            return vMajor > major;
        }
        if (vMinor != minor) {
            return vMinor > minor;
        }
        return vPatch >= patch;
    }

    private int parseIntSafe(String value) {
        try {
            // Handle versions like 1.20.4-R0.1
            StringBuilder sb = new StringBuilder();
            for (char c : value.toCharArray()) {
                if (Character.isDigit(c)) {
                    sb.append(c);
                } else {
                    break;
                }
            }
            if (sb.length() == 0)
                return 0;
            return Integer.parseInt(sb.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public WorkloadDistributor getWorkloadDistributor() {
        return workloadDistributor;
    }

    private static class NoOpPremiumService implements PremiumService {
        @Override
        public void sendAlert(String message) {
            // Do nothing
        }

        @Override
        public boolean isEnabled() {
            return false;
        }

        @Override
        public void reload() {
            // Do nothing
        }
    }
}
