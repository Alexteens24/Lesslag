package com.lesslag;

import com.lesslag.action.ActionExecutor;
import com.lesslag.command.LagCommand;
import com.lesslag.command.LagTabCompleter;
import com.lesslag.monitor.ChunkLimiter;
import com.lesslag.monitor.FrustumCuller;
import com.lesslag.monitor.WorldChunkGuard;
import com.lesslag.monitor.MemoryLeakDetector;
import com.lesslag.monitor.GCMonitor;
import com.lesslag.monitor.LagSourceAnalyzer;
import com.lesslag.monitor.PredictiveOptimizer;
import com.lesslag.monitor.RedstoneMonitor;
import com.lesslag.monitor.TPSMonitor;
import com.lesslag.monitor.TickMonitor;
import com.lesslag.util.CompatibilityManager;
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

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
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
    private ChunkLimiter chunkLimiter;
    private RedstoneMonitor redstoneMonitor;
    private PredictiveOptimizer predictiveOptimizer;
    private FrustumCuller frustumCuller;
    private WorldChunkGuard worldChunkGuard;
    private MemoryLeakDetector memoryLeakDetector;
    private CompatibilityManager compatManager;

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
        instance = this;
        saveDefaultConfig();

        String minVersion = getConfig().getString("compat.min-version", "1.20.4");
        boolean allowUnsupported = getConfig().getBoolean("compat.allow-unsupported-versions", false);
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

        // Create async executor (2 threads: monitoring + analysis)
        asyncExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "LessLag-Async");
            t.setDaemon(true);
            return t;
        });

        // Initialize components
        actionExecutor = new ActionExecutor(this);

        // Run compatibility detection BEFORE creating monitors
        // (may adjust config values to avoid conflicts)
        compatManager = new CompatibilityManager(this);
        compatManager.detect();
        lagSourceAnalyzer = new LagSourceAnalyzer(this);
        predictiveOptimizer = new PredictiveOptimizer(this, actionExecutor);
        tpsMonitor = new TPSMonitor(this, actionExecutor, lagSourceAnalyzer, predictiveOptimizer);
        tickMonitor = new TickMonitor(this);
        gcMonitor = new GCMonitor(this);
        chunkLimiter = new ChunkLimiter(this);
        redstoneMonitor = new RedstoneMonitor(this);
        frustumCuller = new FrustumCuller(this);
        worldChunkGuard = new WorldChunkGuard(this);
        memoryLeakDetector = new MemoryLeakDetector(this);

        // Start monitoring
        tpsMonitor.start();
        tickMonitor.start();
        gcMonitor.start();
        chunkLimiter.start();
        redstoneMonitor.start();
        frustumCuller.start();
        worldChunkGuard.start();
        memoryLeakDetector.start();

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
    }

    @Override
    public void onDisable() {
        if (tpsMonitor != null)
            tpsMonitor.stop();
        if (tickMonitor != null)
            tickMonitor.stop();
        if (gcMonitor != null)
            gcMonitor.stop();
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

        // Restore original settings
        if (actionExecutor != null) {
            actionExecutor.restoreDefaults();
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

        getLogger().info("LessLag disabled. Server settings restored.");
    }

    public void reloadPlugin() {
        reloadConfig();
        if (tpsMonitor != null)
            tpsMonitor.stop();
        if (tickMonitor != null)
            tickMonitor.stop();
        if (gcMonitor != null)
            gcMonitor.stop();
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

        actionExecutor = new ActionExecutor(this);
        lagSourceAnalyzer = new LagSourceAnalyzer(this);
        predictiveOptimizer = new PredictiveOptimizer(this, actionExecutor);
        tpsMonitor = new TPSMonitor(this, actionExecutor, lagSourceAnalyzer, predictiveOptimizer);
        tickMonitor = new TickMonitor(this);
        gcMonitor = new GCMonitor(this);
        chunkLimiter = new ChunkLimiter(this);
        redstoneMonitor = new RedstoneMonitor(this);
        frustumCuller = new FrustumCuller(this);
        worldChunkGuard = new WorldChunkGuard(this);
        memoryLeakDetector = new MemoryLeakDetector(this);

        tpsMonitor.start();
        tickMonitor.start();
        gcMonitor.start();
        chunkLimiter.start();
        redstoneMonitor.start();
        frustumCuller.start();
        worldChunkGuard.start();
        memoryLeakDetector.start();
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

    public ChunkLimiter getChunkLimiter() {
        return chunkLimiter;
    }

    public RedstoneMonitor getRedstoneMonitor() {
        return redstoneMonitor;
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
        return mob.isAware();
    }

    public boolean setMobAwareSafe(Mob mob, boolean aware) {
        if (mob == null)
            return false;
        mob.setAware(aware);
        return true;
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
        return getConfig().getString("prefix", "&8[&c&lLessLag&8] &r");
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
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public WorkloadDistributor getWorkloadDistributor() {
        return workloadDistributor;
    }
}
