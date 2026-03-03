package com.lesslag;

import com.lesslag.action.ActionExecutor;
import com.lesslag.command.LagCommand;
import com.lesslag.command.LagTabCompleter; // Resync
import com.lesslag.setup.SetupAdvisor;
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
import com.lesslag.util.CompatibilityManager;
import com.lesslag.util.SchedulerAdapter;
import com.lesslag.web.LessLagApiClient;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
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
    private MemoryLeakDetector memoryLeakDetector;
    private CompatibilityManager compatManager;
    private PremiumService premiumService;
    private SetupAdvisor setupAdvisor;

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

    private static final Method WORLD_GET_SIM_DISTANCE = getMethod(World.class, "getSimulationDistance");
    private static final Method WORLD_SET_SIM_DISTANCE = getMethod(World.class, "setSimulationDistance", int.class);

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
        saveResource("messages.yml", false);

        // Initialize Folia scheduler adapter (must be early)
        SchedulerAdapter.init();
        getLogger().info("Running on " + (SchedulerAdapter.isFolia() ? "Folia (regionised)" : "Paper/Spigot"));

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
            Integer simDistance = getSimulationDistanceSafe(world);
            if (simDistance != null) {
                originalSimulationDistances.put(world.getName(), simDistance);
            }
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

        // Initialize Setup Advisor
        if (getConfig().getBoolean("setup-advisor.enabled", true)) {
            setupAdvisor = new SetupAdvisor(this);
            getLogger().info("Setup Advisor initialized.");
        }

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

        if (setupAdvisor != null) {
            setupAdvisor.shutdown();
        }

        if (workloadDistributor != null) {
            workloadDistributor.shutdown();
        }

        // Restore original settings synchronously (no scheduler available during disable)
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

    private java.util.logging.Logger getSafeLogger() {
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
     * Initialize web integration: build the API client, register the server if
     * needed, then schedule the heartbeat and (optionally) apply-queue poller.
     */
    private void initWebIntegration() {
        String apiUrl = getConfig().getString("web.api-url", "https://lesslag-api.daucatmoitu.workers.dev");
        String storedId     = getConfig().getString("web.server-id",     "");
        String storedSecret = getConfig().getString("web.server-secret", "");

        boolean hasCredentials = storedId != null && !storedId.isBlank()
                              && storedSecret != null && !storedSecret.isBlank();
        apiClient = new LessLagApiClient(apiUrl,
                hasCredentials ? storedId     : null,
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
                String newId     = LessLagApiClient.extractFromJson(json, "serverId");
                String newSecret = LessLagApiClient.extractFromJson(json, "serverSecret");
                if (newId == null || newSecret == null) return;

                apiClient.setCredentials(newId, newSecret);

                // Persist to config.yml
                getConfig().set("web.server-id",     newId);
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
        if (!getConfig().getBoolean("web.heartbeat.enabled", true)) return;
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
        if (!getConfig().getBoolean("web.apply-queue.enabled", false)) return;
        int intervalSec = getConfig().getInt("web.apply-queue.poll-interval-seconds", 60);
        long periodTicks = intervalSec * 20L;
        applyQueueTask = SchedulerAdapter.runAsyncRepeating(this, () ->
            apiClient.pollApplyQueue().thenAccept(patches -> {
                for (Map<String, Object> patch : patches) handleIncomingPatch(patch);
            }).exceptionally(ex -> null),
        periodTicks * 2, periodTicks);
    }

    /**
     * Receive a patch map from the apply-queue and decide whether to
     * auto-apply it or hold it pending a player {@code /lg confirm} command.
     */
    void handleIncomingPatch(Map<String, Object> patch) {
        String patchId    = String.valueOf(patch.getOrDefault("patchId", ""));
        String riskLevel  = String.valueOf(patch.getOrDefault("riskLevel", "SAFE")).toUpperCase();
        if (patchId.isBlank() || pendingPatches.containsKey(patchId)) return;

        int autoSec = getConfig().getInt("web.apply-queue.auto-confirm-seconds", 300);
        boolean isAggressive = "AGGRESSIVE".equals(riskLevel);

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
            @SuppressWarnings("unchecked")
            List<String> changes = (List<String>) patch.getOrDefault("changes", List.of());
            SchedulerAdapter.runGlobal(this, () ->
                Bukkit.broadcast(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    "&b[LessLag] &7New web patch (&e" + riskLevel + "&7, " + changes.size()
                    + " change(s)). Confirm with &b/lg confirm " + shortId))
            );
        }
    }

    /** Apply a patch by writing the key/value entries to config. */
    @SuppressWarnings("unchecked")
    private void applyPatch(String patchId, Map<String, Object> patch) {
        double msptBefore = tpsMonitor != null ? tpsMonitor.getCurrentMSPT() : 0;
        List<Map<String, Object>> changes =
                (List<Map<String, Object>>) patch.getOrDefault("changes", List.of());
        for (Map<String, Object> change : changes) {
            String file  = String.valueOf(change.getOrDefault("file",  ""));
            String key   = String.valueOf(change.getOrDefault("key",   ""));
            Object value = change.get("value");
            if (!file.isBlank() && !key.isBlank() && value != null) {
                getLogger().info("[LessLag Web] Applying patch: " + file + " " + key + " = " + value);
                // Only config.yml keys are applied directly via the Bukkit config object
                if ("config.yml".equals(file)) {
                    getConfig().set(key, value);
                }
                // Other files (paper.yml, spigot.yml, …) would require per-format handling —
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
                sender.sendMessage(LegacyComponentSerializer.legacyAmpersand()
                        .deserialize("&b[LessLag] &7No pending patches."));
            } else {
                sender.sendMessage(LegacyComponentSerializer.legacyAmpersand()
                        .deserialize("&b[LessLag] &7Pending patches:"));
                for (String id : pendingPatches.keySet()) {
                    String sid = id.length() >= 8 ? id.substring(0, 8) : id;
                    sender.sendMessage(LegacyComponentSerializer.legacyAmpersand()
                            .deserialize("  &e" + sid));
                }
            }
            return;
        }
        String matchedId = pendingPatches.keySet().stream()
                .filter(id -> id.startsWith(shortId) || id.equals(shortId))
                .findFirst().orElse(null);
        if (matchedId == null) {
            sender.sendMessage(LegacyComponentSerializer.legacyAmpersand()
                    .deserialize("&b[LessLag] &cNo patch matching '" + shortId + "'."));
            return;
        }
        Map<String, Object> patch = pendingPatches.remove(matchedId);
        if (patch != null) {
            applyPatch(matchedId, patch);
            sender.sendMessage(LegacyComponentSerializer.legacyAmpersand()
                    .deserialize("&b[LessLag] &aPatch applied successfully."));
        }
    }

    public LessLagApiClient getApiClient() { return apiClient; }

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

    public CompatibilityManager getCompatManager() {
        return compatManager;
    }

    public MemoryLeakDetector getMemoryLeakDetector() {
        return memoryLeakDetector;
    }

    public PremiumService getPremiumManager() {
        return premiumService;
    }

    public SetupAdvisor getSetupAdvisor() {
        return setupAdvisor;
    }

    public ExecutorService getAsyncExecutor() {
        return asyncExecutor;
    }

    public int getOriginalViewDistance(World world) {
        return originalViewDistances.getOrDefault(world.getName(), world.getViewDistance());
    }

    public Integer getOriginalSimulationDistance(World world) {
        Integer stored = originalSimulationDistances.get(world.getName());
        return stored != null ? stored : getSimulationDistanceSafe(world);
    }

    public boolean isSimulationDistanceSupported() {
        return WORLD_GET_SIM_DISTANCE != null && WORLD_SET_SIM_DISTANCE != null;
    }

    public Integer getSimulationDistanceSafe(World world) {
        if (WORLD_GET_SIM_DISTANCE == null) {
            return null;
        }
        try {
            return (Integer) WORLD_GET_SIM_DISTANCE.invoke(world);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean setSimulationDistanceSafe(World world, int distance) {
        if (WORLD_SET_SIM_DISTANCE == null) {
            return false;
        }
        try {
            WORLD_SET_SIM_DISTANCE.invoke(world, distance);
            return true;
        } catch (Exception e) {
            return false;
        }
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
     * Translate & color codes to Adventure Component
     */
    public static Component colorize(String message) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(message);
    }

    public static String colorizeLegacy(String message) {
        return LegacyComponentSerializer.legacySection().serialize(colorize(message));
    }

    public static void sendMessage(CommandSender sender, String message) {
        String legacy = colorizeLegacy(message);
        try {
            Method method = sender.getClass().getMethod("sendMessage", Component.class);
            method.invoke(sender, colorize(message));
        } catch (Exception e) {
            sender.sendMessage(legacy);
        }
    }

    public static void sendActionBar(Player player, String message) {
        String legacy = colorizeLegacy(message);
        try {
            Method method = player.getClass().getMethod("sendActionBar", Component.class);
            method.invoke(player, colorize(message));
            return;
        } catch (Exception ignored) {
        }

        try {
            Method method = player.getClass().getMethod("sendActionBar", String.class);
            method.invoke(player, legacy);
        } catch (Exception e) {
            player.sendMessage(legacy);
        }
    }

    public String getPrefix() {
        return getConfig().getString("core.prefix", "&8[&c&lLessLag&8] &r");
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        World world = event.getWorld();
        originalViewDistances.putIfAbsent(world.getName(), world.getViewDistance());
        Integer simDistance = getSimulationDistanceSafe(world);
        if (simDistance != null) {
            originalSimulationDistances.putIfAbsent(world.getName(), simDistance);
        }
    }

    private static Method getMethod(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            return null;
        }
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
