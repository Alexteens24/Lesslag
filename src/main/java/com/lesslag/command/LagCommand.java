package com.lesslag.command;

import com.lesslag.LessLag;
import com.lesslag.action.ActionExecutor;
import com.lesslag.action.ThresholdConfig;
import com.lesslag.web.ApplyConfigCommand;
import com.lesslag.web.LessLagApiClient;
import com.lesslag.web.VerifyConfigCommand;
import com.lesslag.web.WebLinkCommand;
import com.lesslag.monitor.BottleneckAnalyzer;
import com.lesslag.monitor.BreedingLimiter;
import com.lesslag.monitor.ChunkLimiter;
import com.lesslag.monitor.DensityOptimizer;
import com.lesslag.monitor.FrustumCuller;
import com.lesslag.monitor.WorldChunkGuard;
import com.lesslag.monitor.MemoryLeakDetector;
import com.lesslag.monitor.GCMonitor;
import com.lesslag.monitor.PredictiveOptimizer;
import com.lesslag.monitor.RedstoneMonitor;
import com.lesslag.monitor.TPSMonitor;
import com.lesslag.monitor.TickMonitor;
import com.lesslag.monitor.VillagerOptimizer;
import com.lesslag.util.SchedulerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class LagCommand implements CommandExecutor {

    private final LessLag plugin;
    private FileConfiguration messagesConfig;

    public LagCommand(LessLag plugin) {
        this.plugin = plugin;
        File msgFile = new File(plugin.getDataFolder(), "messages.yml");
        if (msgFile.exists()) {
            messagesConfig = YamlConfiguration.loadConfiguration(msgFile);
        }
    }

    private String getMessage(String key, String def) {
        if (messagesConfig != null) {
            String val = messagesConfig.getString(key);
            if (val != null)
                return val;
        }
        return plugin.getConfig().getString(key, def);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {

        if (!sender.hasPermission("lesslag.admin")) {
            sender.sendMessage(LessLag.colorize("<red>You don't have permission to use this command!"));
            return true;
        }

        if (args.length == 0) {
            showHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "status":
                showStatus(sender);
                break;
            case "health":
                showHealthReport(sender);
                break;
            case "tps":
                showTPS(sender);
                break;
            case "gc":
                doGC(sender);
                break;
            case "gcinfo":
                showGCInfo(sender);
                break;
            case "clear":
                doClear(sender, args);
                break;
            case "ai":
                doAI(sender, args);
                break;
            case "tickmonitor":
                showTickInfo(sender);
                break;
            case "entities":
                showEntityBreakdown(sender);
                break;
            case "thresholds":
                showThresholds(sender);
                break;
            case "sources":
                showSources(sender);
                break;
            case "trace":
                doTrace(sender);
                break;
            case "chunks":
                showChunkLimiter(sender);
                break;
            case "redstone":
                showRedstone(sender);
                break;
            case "predictive":
                showPredictive(sender);
                break;
            case "frustum":
                showFrustum(sender);
                break;
            case "worldguard":
                showWorldGuard(sender);
                break;
            case "memory":
                showMemory(sender);
                break;
            case "villager":
                showVillagerOptimizer(sender);
                break;
            case "density":
                showDensityOptimizer(sender);
                break;
            case "breeding":
                showBreedingLimiter(sender);
                break;
            case "restore":
                doRestore(sender);
                break;
            case "setup":
                send(sender, "<red>The setup wizard has been moved to the web dashboard! Please use <yellow>/lg web link<red>.");
                break;
            case "web":
                handleWeb(sender, args);
                break;
            case "apply":
                new ApplyConfigCommand(plugin).execute(sender);
                break;
            case "verify":
                new VerifyConfigCommand(plugin).execute(sender);
                break;
            case "drift":
                plugin.performDriftCheck(sender);
                break;
            case "confirm":
                plugin.confirmPendingPatch(args.length > 1 ? args[1] : "", sender);
                break;
            case "reload":
                doReload(sender);
                break;
            default:
                showHelp(sender);
                break;
        }

        return true;
    }

    // ══════════════════════════════════════════════════
    // Help
    // ══════════════════════════════════════════════════

    private void showHelp(CommandSender sender) {
        send(sender, "");
        send(sender, "<red><bold>  ≡ LessLag v" + plugin.getPluginMeta().getVersion() + " ≡");
        send(sender, "<dark_gray>  Server Performance Guardian");
        send(sender, "");
        send(sender, "  <dark_gray>── Core ──────────────────────────────────────────");
        send(sender, "  <yellow>/lg status      <dark_gray>- <gray>Quick server overview");
        send(sender, "  <yellow>/lg health      <dark_gray>- <gray>Full diagnostics report");
        send(sender, "  <yellow>/lg tps         <dark_gray>- <gray>TPS history (5s/10s/1m/5m/15m)");
        send(sender, "  <yellow>/lg gc          <dark_gray>- <gray>Force garbage collection");
        send(sender, "  <yellow>/lg tickmonitor <dark_gray>- <gray>Tick spike statistics");
        send(sender, "  <yellow>/lg entities    <dark_gray>- <gray>Entity type breakdown");
        send(sender, "  <yellow>/lg thresholds  <dark_gray>- <gray>View threshold config & status");
        send(sender, "  <yellow>/lg chunks      <dark_gray>- <gray>Smart Chunk Limiter status");
        send(sender, "  <yellow>/lg redstone    <dark_gray>- <gray>Redstone Suppressor status");
        send(sender, "  <yellow>/lg predictive  <dark_gray>- <gray>Predictive Optimizer status");
        send(sender, "  <yellow>/lg breeding    <dark_gray>- <gray>Breeding Limiter status");
        send(sender, "  <yellow>/lg clear       <dark_gray>- <gray>Clear entities <dark_gray>[items|mobs|hostile|all]");
        send(sender, "  <yellow>/lg ai          <dark_gray>- <gray>AI control <dark_gray>[disable|restore|status]");
        send(sender, "  <yellow>/lg restore     <dark_gray>- <gray>Restore all defaults");
        send(sender, "");
        send(sender, "  <dark_gray>── Advanced (opt-in) ────────────────────────────");
        send(sender, "  <yellow>/lg sources     <dark_gray>- <gray>Analyze lag sources <dark_gray>(lag-source-analyzer)");
        send(sender, "  <yellow>/lg gcinfo      <dark_gray>- <gray>GC collector statistics <dark_gray>(gc-monitor)");
        send(sender, "  <yellow>/lg villager    <dark_gray>- <gray>Villager Optimizer status");
        send(sender, "  <yellow>/lg density     <dark_gray>- <gray>Density Optimizer status");
        send(sender, "");
        send(sender, "  <dark_gray>── Experimental (opt-in) ────────────────────────");
        send(sender, "  <yellow>/lg frustum     <dark_gray>- <gray>Frustum Culler status <dark_gray>(modules.mob-ai)");
        send(sender, "  <yellow>/lg trace       <dark_gray>- <gray>Bottleneck Analyzer config <dark_gray>(bottleneck-analyzer)");
        send(sender, "  <yellow>/lg memory      <dark_gray>- <gray>Memory Leak Detector status");
        send(sender, "");
        send(sender, "  <dark_gray>── Emergency (opt-in) ───────────────────────────");
        send(sender, "  <yellow>/lg worldguard  <dark_gray>- <gray>World Chunk Guard status");
        send(sender, "");
        send(sender, "  <dark_gray>── Web & Config ─────────────────────────────────");
        send(sender, "  <yellow>/lg web link    <dark_gray>- <gray>Generate pre-filled web configurator link");
        send(sender, "  <yellow>/lg apply       <dark_gray>- <gray>Apply lesslag-config.json from web");
        send(sender, "  <yellow>/lg verify      <dark_gray>- <gray>Verify server config expectations");
        send(sender, "  <yellow>/lg drift       <dark_gray>- <gray>Check for config drift from web snapshot");
        send(sender, "  <yellow>/lg confirm     <dark_gray>- <gray>Confirm pending web config patches");
        send(sender, "  <yellow>/lg reload      <dark_gray>- <gray>Reload configuration");
        send(sender, "");
        send(sender, "  <dark_gray>Permissions: <gray>lesslag.admin <dark_gray>(commands) <gray>lesslag.notify <dark_gray>(alerts)");
        send(sender, "");
    }

    // ══════════════════════════════════════════════════
    // Status (quick overview)
    // ══════════════════════════════════════════════════

    private void showStatus(CommandSender sender) {
        TPSMonitor tps = plugin.getTpsMonitor();
        ThresholdConfig active = tps.getActiveThreshold();
        List<ThresholdConfig> allThresholds = tps.getThresholds();

        String tpsColor = getTpsColor(tps.getCurrentTPS());

        // Status from active threshold
        String statusColor, statusText;
        if (active != null) {
            statusColor = active.getColor(allThresholds);
            statusText = "⚠ " + active.getName().toUpperCase();
        } else {
            statusColor = "<green>";
            statusText = "✔ NORMAL";
        }

        // Health Score (0-100) computed from TPS, MSPT, memory
        int healthScore = computeHealthScore(tps);
        String healthColor = healthScore >= 80 ? "<green>" : healthScore >= 50 ? "<yellow>" : "<red>";
        String healthBar = buildBar(healthScore, 100, 20);

        // TPS bar with gradient
        StringBuilder tpsBar = new StringBuilder();
        int filled = (int) Math.round(tps.getCurrentTPS());
        for (int i = 0; i < 20; i++) {
            if (i < filled) {
                tpsBar.append(i < 8 ? "<red>" : i < 16 ? "<yellow>" : "<green>").append("█");
            } else {
                tpsBar.append("<dark_gray>█");
            }
        }

        // Uptime
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        String uptime = formatDuration(uptimeMs);

        send(sender, "");
        send(sender, "<red><bold>  ≡ LessLag Status ≡");
        send(sender, "");
        send(sender, "  <gray>Health: " + healthColor + healthScore + "/100 " + healthBar);
        send(sender, "  <gray>TPS: " + tpsColor + String.format("%.1f", tps.getCurrentTPS()) + " <dark_gray>/ <green>20.0");
        send(sender, "  " + tpsBar);
        send(sender, "  <gray>MSPT: <white>" + String.format("%.1f", tps.getCurrentMSPT()) + "ms <dark_gray>("
                + "<gray>min: " + String.format("%.1f", tps.getMinMSPT())
                + " <dark_gray>/ <gray>max: " + String.format("%.1f", tps.getMaxMSPT()) + "<dark_gray>)");

        // MSPT percentiles
        double[] pct = tps.getMSPTPercentiles();
        send(sender, "  <gray>MSPT <dark_gray>P50: <white>" + String.format("%.1f", pct[0])
                + "ms <dark_gray>P95: <white>" + String.format("%.1f", pct[1])
                + "ms <dark_gray>P99: <white>" + String.format("%.1f", pct[2]) + "ms");
        send(sender, "");
        send(sender, "  <gray>Status: " + statusColor + statusText);
        send(sender, "  <gray>Modified: " + (tps.isSettingsModified() ? "<yellow>Yes <dark_gray>(settings changed)" : "<green>No"));
        send(sender, "  <gray>RAM: <white>" + plugin.getActionExecutor().getMemoryInfo());
        send(sender, "  <gray>Players: <white>" + Bukkit.getOnlinePlayers().size() + " <dark_gray>/ <white>" + Bukkit.getMaxPlayers());
        send(sender, "  <gray>Entities: <white>" + plugin.getActionExecutor().getTotalEntityCount());
        send(sender, "  <gray>Uptime: <white>" + uptime);

        // Active optimizers summary
        send(sender, "");
        send(sender, "  <yellow><bold>Active Optimizers");
        PredictiveOptimizer po = plugin.getPredictiveOptimizer();
        String predState = po != null && po.isPredictiveTriggered() ? "<red>⚠ TRIGGERED" : "<green>✔ Idle";
        send(sender, "    <gray>Predictive: " + predState
                + (po != null && po.getTriggerCount() > 0 ? " <dark_gray>(" + po.getTriggerCount() + " total)" : ""));

        VillagerOptimizer vo = plugin.getVillagerOptimizer();
        if (vo != null) {
            send(sender, "    <gray>Villagers: <yellow>" + vo.getOptimizedCount() + " <gray>optimized, <green>"
                    + vo.getActiveRestoredCount() + " <gray>active");
        }

        DensityOptimizer dens = plugin.getDensityOptimizer();
        if (dens != null && dens.isEnabled()) {
            send(sender, "    <gray>Density: <yellow>" + dens.getTotalMobsOptimized() + " <gray>mobs suppressed");
        }

        FrustumCuller fc = plugin.getFrustumCuller();
        if (fc != null) {
            send(sender, "    <gray>Frustum: <yellow>" + fc.getLastCulled() + " <gray>culled, <green>"
                    + fc.getLastRestored() + " <gray>restored");
        }

        // Workload queue
        com.lesslag.WorkloadDistributor wd = plugin.getWorkloadDistributor();
        if (wd != null) {
            int queueSize = wd.getQueueSize();
            String qColor = queueSize == 0 ? "<green>" : queueSize < 50 ? "<yellow>" : "<red>";
            send(sender, "    <gray>Workload Queue: " + qColor + queueSize
                    + (wd.isProcessing() ? " <dark_gray>(processing)" : " <dark_gray>(idle)"));
        }

        // Tick spikes
        TickMonitor tick = plugin.getTickMonitor();
        if (tick != null && tick.getSpikeCount() > 0) {
            send(sender, "    <gray>Tick Spikes: <yellow>" + tick.getSpikeCount()
                    + " <dark_gray>(worst: <white>" + String.format("%.0f", tick.getWorstTickMs()) + "ms<dark_gray>)");
        }

        // Bottleneck spikes
        BottleneckAnalyzer ba = plugin.getBottleneckAnalyzer();
        if (ba != null && ba.getTotalSpikes() > 0) {
            send(sender, "    <gray>Bottlenecks: <red>" + ba.getTotalSpikes()
                    + " <dark_gray>(worst: <white>" + ba.getWorstSpikeDurationMs() + "ms<dark_gray>)");
        }

        send(sender, "");
    }

    /**
     * Compute a 0-100 health score from TPS, MSPT, and memory usage.
     * Fast — no allocations, no I/O.
     */
    private int computeHealthScore(TPSMonitor tps) {
        // TPS component: 0-40 points (20 TPS = 40, 10 TPS = 0)
        double tpsScore = Math.max(0, Math.min(40, (tps.getCurrentTPS() / 20.0) * 40));
        // MSPT component: 0-30 points (under 30ms = 30, over 50ms = 0)
        double msptScore = Math.max(0, Math.min(30, (1.0 - Math.max(0, tps.getCurrentMSPT() - 30) / 20.0) * 30));
        // Memory component: 0-30 points
        Runtime rt = Runtime.getRuntime();
        double memUsed = (double) (rt.totalMemory() - rt.freeMemory()) / rt.maxMemory();
        double memScore = Math.max(0, Math.min(30, (1.0 - memUsed) * 30));
        return (int) Math.round(tpsScore + msptScore + memScore);
    }

    private String buildBar(int value, int max, int width) {
        int filled = (int) Math.round((double) value / max * width);
        StringBuilder bar = new StringBuilder();
        String color = value * 100 / max >= 80 ? "<green>" : value * 100 / max >= 50 ? "<yellow>" : "<red>";
        for (int i = 0; i < width; i++) {
            bar.append(i < filled ? color + "█" : "<dark_gray>█");
        }
        return bar.toString();
    }

    private String formatDuration(long ms) {
        long hours = TimeUnit.MILLISECONDS.toHours(ms);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60;
        if (hours > 0)
            return hours + "h " + minutes + "m";
        if (minutes > 0)
            return minutes + "m " + seconds + "s";
        return seconds + "s";
    }

    // ══════════════════════════════════════════════════
    // Thresholds Info
    // ══════════════════════════════════════════════════

    private void showThresholds(CommandSender sender) {
        TPSMonitor tps = plugin.getTpsMonitor();
        List<ThresholdConfig> thresholds = tps.getThresholds();
        ThresholdConfig active = tps.getActiveThreshold();

        send(sender, "");
        send(sender, "<red><bold>  ≡ Threshold Configuration ≡");
        send(sender, "  <gray>Current TPS: " + getTpsColor(tps.getCurrentTPS())
                + String.format("%.1f", tps.getCurrentTPS()));
        send(sender, "");

        if (thresholds.isEmpty()) {
            send(sender, "  <gray>No thresholds configured.");
        } else {
            for (ThresholdConfig threshold : thresholds) {
                String color = threshold.getColor(thresholds);
                boolean isActive = threshold.equals(active);
                String marker = isActive ? " <red><bold>◄ ACTIVE" : "";

                // Header line
                send(sender, "  " + color + (isActive ? "▶" : "▸") + " " + threshold.getName().toUpperCase()
                        + " <dark_gray>(TPS ≤ " + color + threshold.getTps() + "<dark_gray>)" + marker);

                // Actions
                if (!threshold.getActions().isEmpty()) {
                    send(sender, "    <gray>Actions: <white>" + String.join("<dark_gray>, <white>", threshold.getActions()));
                }

                // Commands
                if (!threshold.getCommands().isEmpty()) {
                    send(sender, "    <gray>Commands: <white>" + threshold.getCommands().size() + " configured");
                }

                // Notification summary
                StringBuilder notifyInfo = new StringBuilder("    <gray>Notify: ");
                if (threshold.isNotifyChat())
                    notifyInfo.append("<green>Chat ");
                if (threshold.isNotifyActionbar())
                    notifyInfo.append("<green>ActionBar ");
                if (threshold.isNotifySound())
                    notifyInfo.append("<green>Sound<dark_gray>(<white>")
                            .append(threshold.getSoundType()).append("<dark_gray>) ");
                if (threshold.isBroadcast())
                    notifyInfo.append("<gold>Broadcast ");
                send(sender, notifyInfo.toString());

                send(sender, "");
            }
        }

        // Available actions
        send(sender, "<dark_gray>  ─────────────────────────────────────");
        send(sender, "  <gray>Available actions:");
        for (String action : ActionExecutor.ACTIONS_SORTED) {
            send(sender, "    <dark_gray>• <white>" + action + " <dark_gray>- <gray>" + getActionDescription(action));
        }
        send(sender, "");
    }

    /**
     * Get a human-readable description for an action key.
     */
    private String getActionDescription(String action) {
        switch (action) {
            case "clear-ground-items":
                return "Remove dropped items";
            case "clear-xp-orbs":
                return "Remove XP orbs";
            case "clear-mobs":
                return "Remove non-whitelisted mobs";
            case "kill-hostile-mobs":
                return "Kill hostile mobs";
            case "reduce-view-distance":
                return "Lower view distance";
            case "reduce-simulation-distance":
                return "Lower simulation distance";
            case "disable-mob-ai":
                return "Disable AI for far mobs";
            case "force-gc":
                return "Force garbage collection";
            case "chunk-clean":
                return "Smart chunk cleanup";
            case "enforce-entity-limits":
                return "Force-remove excess entities (ignores protection)";
            case "unload-world-chunks":
                return "Unload excess chunks in overloaded worlds";
            default:
                return "Unknown action";
        }
    }

    // ══════════════════════════════════════════════════
    // Health Report (Spark-style)
    // ══════════════════════════════════════════════════

    private void showHealthReport(CommandSender sender) {
        TPSMonitor tps = plugin.getTpsMonitor();
        boolean showTps = plugin.getConfig().getBoolean("health-report.tps", true);
        boolean showMspt = plugin.getConfig().getBoolean("health-report.mspt", true);
        boolean showCpu = plugin.getConfig().getBoolean("health-report.cpu", true);
        boolean showMemory = plugin.getConfig().getBoolean("health-report.memory", true);
        boolean showDisk = plugin.getConfig().getBoolean("health-report.disk", true);
        boolean showWorlds = plugin.getConfig().getBoolean("health-report.worlds", true);
        boolean showEntityBreakdown = plugin.getConfig().getBoolean("health-report.entity-breakdown", true);

        send(sender, "");
        send(sender, "<red><bold>  ≡ Server Health Report ≡");
        send(sender, "<dark_gray>  ─────────────────────────────────────");

        if (!showTps && !showMspt && !showCpu && !showMemory && !showDisk && !showWorlds && !showEntityBreakdown) {
            send(sender, "");
            send(sender, "  <gray>All health-report sections are disabled in config.");
            send(sender, "");
            send(sender, "<dark_gray>  ─────────────────────────────────────");
            send(sender, "");
            return;
        }

        // TPS Section
        if (showTps) {
            send(sender, "");
            send(sender, "  <yellow><bold>TPS <dark_gray>(Ticks Per Second)");
            send(sender, "    <gray> 5s: " + formatTPS(tps.getTPS5s()));
            send(sender, "    <gray>10s: " + formatTPS(tps.getTPS10s()));
            send(sender, "    <gray> 1m: " + formatTPS(tps.getTPS1m()));
            send(sender, "    <gray> 5m: " + formatTPS(tps.getTPS5m()));
            send(sender, "    <gray>15m: " + formatTPS(tps.getTPS15m()));
        }

        // MSPT Section
        if (showMspt) {
            send(sender, "");
            send(sender, "  <yellow><bold>MSPT <dark_gray>(Milliseconds Per Tick)");
            send(sender, "    <gray>Avg: " + formatMSPT(tps.getCurrentMSPT()));
            send(sender, "    <gray>Min: " + formatMSPT(tps.getMinMSPT()));
            send(sender, "    <gray>Max: " + formatMSPT(tps.getMaxMSPT()));
        }

        // CPU Section
        if (showCpu) {
            send(sender, "");
            send(sender, "  <yellow><bold>CPU");
            try {
                OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
                double loadAvg = os.getSystemLoadAverage();
                int cpus = os.getAvailableProcessors();
                send(sender, "    <gray>Cores: <white>" + cpus);
                send(sender, "    <gray>Load Avg: <white>" + (loadAvg >= 0 ? String.format("%.2f", loadAvg) : "N/A"));
            } catch (Exception e) {
                send(sender, "    <gray>CPU info unavailable");
            }
        }

        // Memory Section
        if (showMemory) {
            send(sender, "");
            send(sender, "  <yellow><bold>Memory");
            Runtime rt = Runtime.getRuntime();
            long usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
            long allocMB = rt.totalMemory() / (1024 * 1024);
            long maxMB = rt.maxMemory() / (1024 * 1024);
            long freePercent = 100 - (usedMB * 100 / maxMB);
            String memColor = freePercent > 30 ? "<green>" : freePercent > 15 ? "<yellow>" : "<red>";

            // Memory bar
            int memFilled = (int) ((usedMB * 20) / maxMB);
            StringBuilder memBar = new StringBuilder();
            for (int i = 0; i < 20; i++) {
                memBar.append(i < memFilled ? memColor + "█" : "<dark_gray>█");
            }

            send(sender, "    <gray>Used:      " + memColor + usedMB + "MB <dark_gray>/ <white>" + maxMB + "MB <dark_gray>("
                    + memColor + (usedMB * 100 / maxMB) + "%<dark_gray>)");
            send(sender, "    " + memBar);
            send(sender, "    <gray>Allocated: <white>" + allocMB + "MB");
            send(sender, "    <gray>Free:      <white>" + (maxMB - usedMB) + "MB");
        }

        // Disk Section
        if (showDisk) {
            send(sender, "");
            send(sender, "  <yellow><bold>Disk");
            File root = new File(".");
            long diskFreeMB = root.getFreeSpace() / (1024 * 1024);
            long diskTotalMB = root.getTotalSpace() / (1024 * 1024);
            long diskUsedMB = diskTotalMB - diskFreeMB;
            String diskColor = diskFreeMB > 5000 ? "<green>" : diskFreeMB > 1000 ? "<yellow>" : "<red>";
            send(sender, "    <gray>Used: " + diskColor + diskUsedMB + "MB <dark_gray>/ <white>" + diskTotalMB + "MB");
            send(sender, "    <gray>Free: " + diskColor + diskFreeMB + "MB");
        }

        // Uptime
        send(sender, "");
        send(sender, "  <yellow><bold>Server");
        RuntimeMXBean runtimeMX = ManagementFactory.getRuntimeMXBean();
        long uptimeMs = runtimeMX.getUptime();
        long hours = TimeUnit.MILLISECONDS.toHours(uptimeMs);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(uptimeMs) % 60;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(uptimeMs) % 60;
        send(sender, "    <gray>Uptime: <white>" + hours + "h " + minutes + "m " + seconds + "s");
        send(sender, "    <gray>Java: <white>" + System.getProperty("java.version"));
        send(sender, "    <gray>OS: <white>" + System.getProperty("os.name") + " " + System.getProperty("os.arch"));

        // World Overview
        if (showWorlds) {
            send(sender, "");
            send(sender, "  <yellow><bold>Worlds");
            for (World world : Bukkit.getWorlds()) {
                int entities = world.getEntityCount();
                int chunks = world.getLoadedChunks().length;
                int players = world.getPlayers().size();
                String entColor = entities > 500 ? "<red>" : entities > 200 ? "<yellow>" : "<green>";
                String chkColor = chunks > 1000 ? "<red>" : chunks > 500 ? "<yellow>" : "<green>";
                int simDistance = world.getSimulationDistance();
                String simText = String.valueOf(simDistance);
                send(sender, "    <dark_gray>▸ <white>" + world.getName()
                        + " <dark_gray>| <gray>E: " + entColor + entities
                        + " <dark_gray>| <gray>C: " + chkColor + chunks
                        + " <dark_gray>| <gray>P: <yellow>" + players
                        + " <dark_gray>| <gray>VD: <yellow>" + world.getViewDistance()
                        + " <dark_gray>| <gray>SD: <yellow>" + simText);
            }
        }

        if (showEntityBreakdown) {
            Map<String, Integer> breakdown = plugin.getActionExecutor().getEntityBreakdown();
            if (!breakdown.isEmpty()) {
                send(sender, "");
                send(sender, "  <yellow><bold>Entity Breakdown");
                int total = plugin.getActionExecutor().getTotalEntityCount();
                breakdown.entrySet().stream()
                        .sorted((a, b) -> b.getValue() - a.getValue())
                        .limit(10)
                        .forEach(entry -> {
                            String color = entry.getValue() > 100 ? "<red>" : entry.getValue() > 50 ? "<yellow>" : "<green>";
                            String pct = total > 0 ? String.format("%.0f", entry.getValue() * 100.0 / total) + "%" : "";
                            send(sender, "    <dark_gray>▸ <white>" + entry.getKey() + " <dark_gray>- " + color
                                    + entry.getValue() + " <dark_gray>(" + pct + ")");
                        });
            }
        }

        send(sender, "");
        send(sender, "<dark_gray>  ─────────────────────────────────────");
        send(sender, "");
    }

    // ══════════════════════════════════════════════════
    // TPS History
    // ══════════════════════════════════════════════════

    private void showTPS(CommandSender sender) {
        TPSMonitor tps = plugin.getTpsMonitor();

        send(sender, "");
        send(sender, "<red><bold>  ≡ TPS History ≡");
        send(sender, "");
        send(sender, "  <gray> 5s avg: " + formatTPS(tps.getTPS5s()));
        send(sender, "  <gray>10s avg: " + formatTPS(tps.getTPS10s()));
        send(sender, "  <gray> 1m avg: " + formatTPS(tps.getTPS1m()));
        send(sender, "  <gray> 5m avg: " + formatTPS(tps.getTPS5m()));
        send(sender, "  <gray>15m avg: " + formatTPS(tps.getTPS15m()));
        send(sender, "");
        send(sender, "  <gray>MSPT: <white>" + String.format("%.1f", tps.getCurrentMSPT()) + "ms " +
                "<dark_gray>(<gray>min " + String.format("%.1f", tps.getMinMSPT()) +
                " / max " + String.format("%.1f", tps.getMaxMSPT()) + "<dark_gray>)");
        send(sender, "");
    }

    // ══════════════════════════════════════════════════
    // GC
    // ══════════════════════════════════════════════════

    private void doGC(CommandSender sender) {
        plugin.getLogger().info(sender.getName() + " requested manual GC (disabled — use /lg gcinfo for stats).");
        send(sender, plugin.getPrefix() + "<gray>Manual GC is <red>disabled<gray> to prevent stop-the-world pauses.");
        send(sender, plugin.getPrefix() + "<gray>Use <white>/lg gcinfo<gray> for Garbage Collection statistics.");
        send(sender, plugin.getPrefix() + "<gray>RAM: <white>" + plugin.getActionExecutor().getMemoryInfo());
    }

    private void showGCInfo(CommandSender sender) {
        GCMonitor gc = plugin.getGcMonitor();

        send(sender, "");
        send(sender, "<red><bold>  ≡ GC Information ≡");
        send(sender, "");
        send(sender, "  <gray>Total collections: <yellow>" + gc.getTotalCollections());
        send(sender, "  <gray>Total GC time: <yellow>" + gc.getTotalTimeMs() + "ms");
        send(sender, "");
        send(sender, "  <gray><bold>Collectors:");
        String summary = gc.getGCSummary();
        for (String line : summary.split("\n")) {
            send(sender, line);
        }
        send(sender, "");
    }

    // ══════════════════════════════════════════════════
    // Tick Monitor
    // ══════════════════════════════════════════════════

    private void showTickInfo(CommandSender sender) {
        TickMonitor tick = plugin.getTickMonitor();
        TPSMonitor tps = plugin.getTpsMonitor();

        send(sender, "");
        send(sender, "<red><bold>  ≡ Tick Monitor ≡");
        send(sender, "");
        send(sender, "  <gray>Last tick: <white>" + String.format("%.1f", tick.getLastTickMs()) + "ms");
        send(sender, "  <gray>Worst tick: <white>" + String.format("%.1f", tick.getWorstTickMs()) + "ms");
        send(sender, "  <gray>Spike count: <yellow>" + tick.getSpikeCount() +
                " <dark_gray>(threshold: " + plugin.getConfig().getDouble("system.tick-monitor.threshold-ms", 100) + "ms)");

        // MSPT percentiles from TPSMonitor's buffer
        if (tps != null) {
            double[] pct = tps.getMSPTPercentiles();
            send(sender, "");
            send(sender, "  <yellow><bold>MSPT Distribution");
            send(sender, "    <gray>P50: <white>" + String.format("%.1f", pct[0]) + "ms <dark_gray>(median)");
            send(sender, "    <gray>P95: <white>" + String.format("%.1f", pct[1]) + "ms <dark_gray>(95th percentile)");
            send(sender, "    <gray>P99: <white>" + String.format("%.1f", pct[2]) + "ms <dark_gray>(99th percentile)");
            send(sender, "    <gray>Avg: <white>" + String.format("%.1f", tps.getCurrentMSPT()) + "ms");
            send(sender, "    <gray>Min: <white>" + String.format("%.1f", tps.getMinMSPT()) + "ms");
            send(sender, "    <gray>Max: <white>" + String.format("%.1f", tps.getMaxMSPT()) + "ms");
        }

        send(sender, "");
    }

    // ══════════════════════════════════════════════════
    // Entity Breakdown
    // ══════════════════════════════════════════════════

    private void showEntityBreakdown(CommandSender sender) {
        Map<String, Integer> breakdown = plugin.getActionExecutor().getEntityBreakdown();
        // Derive total from the snapshot map once — avoids re-iterating all worlds
        // for every single row in the formatted output.
        int total = breakdown.values().stream().mapToInt(Integer::intValue).sum();

        send(sender, "");
        send(sender, "<red><bold>  ≡ Entity Breakdown ≡");
        send(sender, "  <gray>Total: <white>" + total);
        send(sender, "");

        breakdown.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(15)
                .forEach(entry -> {
                    String color = entry.getValue() > 100 ? "<red>" : entry.getValue() > 50 ? "<yellow>" : "<green>";
                    String pct = total > 0 ? String.format("%.0f", entry.getValue() * 100.0 / total) + "%" : "";
                    send(sender, "  <dark_gray>▸ <white>" + entry.getKey() + " <dark_gray>- " + color + entry.getValue()
                            + " <dark_gray>(" + pct + ")");
                });

        if (breakdown.size() > 15) {
            send(sender, "  <dark_gray>... and " + (breakdown.size() - 15) + " more types");
        }
        send(sender, "");
    }

    // ══════════════════════════════════════════════════
    // Clear
    // ══════════════════════════════════════════════════

    private void doClear(CommandSender sender, String[] args) {
        String type = args.length > 1 ? args[1].toLowerCase() : "all";
        plugin.getLogger().info(sender.getName() + " cleared entities (type: " + type + ").");

        switch (type) {
            case "items": {
                plugin.getActionExecutor().clearGroundItems();
                String msg = getMessage("messages.items-cleared", "<green>Scheduled clearing of ground items.");
                send(sender, plugin.getPrefix() + msg);
                break;
            }
            case "xp": {
                plugin.getActionExecutor().clearXPOrbs();
                String msg = getMessage("messages.xp-cleared", "<green>Scheduled clearing of XP orbs.");
                send(sender, plugin.getPrefix() + msg);
                break;
            }
            case "mobs": {
                plugin.getActionExecutor().clearExcessMobs();
                String msg = getMessage("messages.mobs-cleared", "<green>Scheduled removal of excess mobs.");
                send(sender, plugin.getPrefix() + msg);
                break;
            }
            case "hostile": {
                plugin.getActionExecutor().killHostileMobs();
                String msg = getMessage("messages.hostile-killed", "<green>Scheduled killing of hostile mobs.");
                send(sender, plugin.getPrefix() + msg);
                break;
            }
            case "all":
            default: {
                plugin.getActionExecutor().clearAll();
                String msg = getMessage("messages.all-cleared",
                        "<green>Scheduled clearing of all entities (items, xp, mobs).");
                send(sender, plugin.getPrefix() + msg);
                break;
            }
        }
    }

    // ══════════════════════════════════════════════════
    // AI Control
    // ══════════════════════════════════════════════════

    private void doAI(CommandSender sender, String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase() : "status";
        if (!action.equals("status")) {
            plugin.getLogger().info(sender.getName() + " modified mob AI (action: " + action + ").");
        }

        switch (action) {
            case "disable": {
                plugin.getActionExecutor().disableMobAI();
                String msg = getMessage("messages.ai-disabled", "<green>Scheduled AI disable task.");
                send(sender, plugin.getPrefix() + msg);
                break;
            }
            case "restore": {
                plugin.getActionExecutor().restoreMobAI();
                send(sender, plugin.getPrefix()
                        + "<green>Scheduled AI restoration for all mobs. This process is batched to prevent lag.");
                break;
            }
            case "status":
            default: {
                // Snapshot world/entity references on main thread first,
                // then count async to avoid stalling the tick with a full entity scan.
                send(sender, plugin.getPrefix() + "<gray>Counting mobs (async)...");
                final List<World> worlds = Bukkit.getWorlds();
                final int activeRadius = plugin.getConfig().getInt("modules.mob-ai.active-radius", 48);
                final int protectedTypes = plugin.getConfig().getStringList("modules.mob-ai.protected").size();

                SchedulerAdapter.runAsync(plugin, () -> {
                    int countTotal = 0, countNoAI = 0;
                    for (World world : worlds) {
                        for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                            for (org.bukkit.entity.Entity entity : chunk.getEntities()) {
                                if (entity instanceof org.bukkit.entity.Mob) {
                                    countTotal++;
                                    if (!plugin.isMobAwareSafe((org.bukkit.entity.Mob) entity))
                                        countNoAI++;
                                }
                            }
                        }
                    }
                    final int ft = countTotal, fn = countNoAI;
                    SchedulerAdapter.runGlobal(plugin, () -> {
                        send(sender, "");
                        send(sender, "<red><bold>  ≡ AI Status ≡");
                        send(sender, "  <gray>Total mobs: <white>" + ft);
                        send(sender, "  <gray>AI disabled: <yellow>" + fn);
                        send(sender, "  <gray>AI active: <green>" + (ft - fn));
                        send(sender, "  <gray>Active radius: <white>" + activeRadius + " blocks");
                        send(sender, "  <gray>Protected types: <white>" + protectedTypes);
                        send(sender, "");
                    });
                });
                break;
            }
        }
    }

    // ══════════════════════════════════════════════════
    // Lag Sources (async analysis)
    // ══════════════════════════════════════════════════

    private void showSources(CommandSender sender) {
        send(sender, plugin.getPrefix() + "<gray>Analyzing lag sources (async)...");

        plugin.getLagSourceAnalyzer().analyzeFullAsync().thenAccept(result -> {
            // Dispatch display back to main thread
            SchedulerAdapter.runGlobal(plugin, () -> {
                send(sender, "");
                send(sender, "<red><bold>  ≡ Lag Source Analysis ≡");
                send(sender, "");

                // Use the full detailed report format
                java.util.List<String> report = plugin.getLagSourceAnalyzer()
                        .formatFullReport(result);
                for (String line : report) {
                    send(sender, line);
                }

                // Warnings summary
                if (!result.sources.isEmpty()) {
                    send(sender, "");
                    send(sender, "  <red><bold>WARNINGS <dark_gray>(" + result.sources.size() + " issues detected)");
                    int shown = 0;
                    for (var source : result.sources) {
                        if (shown >= 5)
                            break;
                        send(sender, "    <red>⚠ " + source.description);
                        shown++;
                    }
                    if (result.sources.size() > 5) {
                        send(sender, "    <dark_gray>  ... and " + (result.sources.size() - 5) + " more");
                    }
                } else {
                    send(sender, "");
                    send(sender, "  <green>✔ No significant lag sources detected.");
                }

                send(sender, "");
            });
        }).exceptionally(e -> {
            SchedulerAdapter.runGlobal(plugin, () -> {
                send(sender, plugin.getPrefix() + "<red>Failed to analyze lag sources: " + e.getMessage());
            });
            return null;
        });
    }

    // ══════════════════════════════════════════════════
    // Bottleneck Analyzer (Trace)
    // ══════════════════════════════════════════════════

    private void doTrace(CommandSender sender) {
        boolean enabled = plugin.getConfig().getBoolean("system.bottleneck-analyzer.enabled", true);
        BottleneckAnalyzer ba = plugin.getBottleneckAnalyzer();

        send(sender, "");
        send(sender, "<red><bold>  ≡ Bottleneck Analyzer (Trace) ≡");
        send(sender, "");
        send(sender, "  <gray>Status: " + (enabled ? "<green>Enabled (Running as Watchdog)" : "<red>Disabled"));
        send(sender, "  <gray>Lag Threshold: <white>"
                + plugin.getConfig().getLong("system.bottleneck-analyzer.threshold-ms", 100L) + "ms");
        send(sender, "  <gray>Sampling Interval: <white>"
                + plugin.getConfig().getLong("system.bottleneck-analyzer.sample-interval-ms", 5L) + "ms");

        if (ba != null && ba.getTotalSpikes() > 0) {
            send(sender, "");
            send(sender, "  <yellow><bold>Runtime Statistics");
            send(sender, "    <gray>Total spikes detected: <red>" + ba.getTotalSpikes());
            send(sender, "    <gray>Worst spike: <red>" + ba.getWorstSpikeDurationMs() + "ms");
            if (!ba.getWorstSpikeCulprit().isEmpty()) {
                String culprit = ba.getWorstSpikeCulprit();
                if (culprit.length() > 50)
                    culprit = "..." + culprit.substring(culprit.length() - 47);
                send(sender, "    <gray>Worst culprit: <yellow>" + culprit);
            }
            if (!ba.getLastSpikeCulprit().isEmpty()) {
                String last = ba.getLastSpikeCulprit();
                if (last.length() > 50)
                    last = "..." + last.substring(last.length() - 47);
                send(sender, "    <gray>Last culprit: <yellow>" + last);
                long ago = (System.currentTimeMillis() - ba.getLastSpikeTimeMs()) / 1000;
                send(sender, "    <gray>Last spike: <white>" + ago + "s ago");
            }
        } else {
            send(sender, "");
            send(sender, "  <green>✔ No lag spikes detected yet.");
        }

        send(sender, "");
        send(sender, "  <dark_gray>The watchdog constantly monitors the main thread.");
        send(sender, "  <dark_gray>If a tick exceeds the threshold, it samples the stack trace");
        send(sender, "  <dark_gray>and identifies the exact method causing the hang.");
        send(sender, "");
    }

    // ══════════════════════════════════════════════════
    // Smart Chunk Limiter
    // ══════════════════════════════════════════════════

    private void showChunkLimiter(CommandSender sender) {
        ChunkLimiter cl = plugin.getChunkLimiter();
        boolean enabled = plugin.getConfig().getBoolean("modules.entities.chunk-limiter.enabled", true);

        send(sender, "");
        send(sender, "<red><bold>  ≡ Smart Chunk Limiter ≡");
        send(sender, "");
        send(sender, "  <gray>Status: " + (enabled ? "<green>Enabled" : "<red>Disabled"));
        send(sender,
                "  <gray>Max entities/chunk: <white>"
                        + plugin.getConfig().getInt("modules.entities.chunk-limiter.max-entities-per-chunk", 50));
        send(sender, "  <gray>Scan interval: <white>"
                + plugin.getConfig().getInt("modules.entities.chunk-limiter.scan-interval", 30) + "s");

        if (cl != null && cl.getLastScanTime() > 0) {
            long ago = (System.currentTimeMillis() - cl.getLastScanTime()) / 1000;
            send(sender, "");
            send(sender, "  <yellow><bold>Last Scan <dark_gray>(" + ago + "s ago)");
            send(sender,
                    "    <gray>Hot chunks found: " + (cl.getLastHotChunks() > 0 ? "<red>" : "<green>") + cl.getLastHotChunks());
            send(sender, "    <gray>Entities removed: " + (cl.getLastEntitiesRemoved() > 0 ? "<yellow>" : "<green>")
                    + cl.getLastEntitiesRemoved());
        } else {
            send(sender, "  <dark_gray>No scan data yet.");
        }
        send(sender, "");
    }

    // ══════════════════════════════════════════════════
    // Redstone Suppressor
    // ══════════════════════════════════════════════════

    private void showRedstone(CommandSender sender) {
        RedstoneMonitor rm = plugin.getRedstoneMonitor();
        boolean enabled = plugin.getConfig().getBoolean("modules.redstone.enabled", true);

        send(sender, "");
        send(sender, "<red><bold>  ≡ Redstone Suppressor ≡");
        send(sender, "");
        send(sender, "  <gray>Status: " + (enabled ? "<green>Enabled" : "<red>Disabled"));
        send(sender, "  <gray>Max activations/chunk: <white>"
                + plugin.getConfig().getInt("modules.redstone.max-activations-per-chunk", 200));
        send(sender, "  <gray>Window: <white>" + plugin.getConfig().getInt("modules.redstone.window-seconds", 2) + "s");
        send(sender, "  <gray>Cooldown: <white>" + plugin.getConfig().getInt("modules.redstone.cooldown-seconds", 10) + "s");

        if (rm != null) {
            send(sender, "");
            send(sender, "  <gray>Total suppressions: <yellow>" + rm.getTotalSuppressed());
            int active = rm.getActiveSuppressedChunks();
            send(sender, "  <gray>Currently suppressed: " + (active > 0 ? "<red>" : "<green>") + active + " chunk(s)");

            if (!rm.getSuppressedChunks().isEmpty()) {
                send(sender, "");
                send(sender, "  <yellow><bold>Active Suppressions:");

                // Advanced stats
                if (plugin.getConfig().getBoolean("modules.redstone.advanced.enabled", true)) {
                    send(sender,
                            "  <gray>Max Frequency: <white>"
                                    + plugin.getConfig().getInt("modules.redstone.advanced.max-frequency")
                                    + "/s");
                    send(sender,
                            "  <gray>Piston Limit: <white>"
                                    + plugin.getConfig()
                                            .getInt("modules.redstone.advanced.piston-limit.max-pushes-per-chunk")
                                    + "/tick");
                }

                long nowNs = System.nanoTime();
                int shown = 0;
                for (Map.Entry<String, Long> entry : rm.getSuppressedChunks().entrySet()) {
                    if (shown >= 5)
                        break;
                    String key = entry.getKey();
                    long remainMs = (entry.getValue() - nowNs) / 1_000_000L;
                    if (remainMs <= 0)
                        continue;
                    // Key format: "worldUID:chunkX:chunkZ"
                    String[] parts = key.split(":");
                    String chunkInfo = parts.length >= 3 ? parts[1] + ", " + parts[2] : key;
                    send(sender, "    <dark_gray>▸ <white>Chunk (" + chunkInfo + ") <dark_gray>- <yellow>"
                            + String.format("%.1f", remainMs / 1000.0) + "s remaining");
                    shown++;
                }
            }
        }
        send(sender, "");
    }

    // ══════════════════════════════════════════════════
    // Predictive Optimization
    // ══════════════════════════════════════════════════

    private void showPredictive(CommandSender sender) {
        PredictiveOptimizer po = plugin.getPredictiveOptimizer();
        boolean enabled = plugin.getConfig().getBoolean("automation.predictive-optimization.enabled", true);

        send(sender, "");
        send(sender, "<red><bold>  ≡ Predictive Optimizer ≡");
        send(sender, "");
        send(sender, "  <gray>Status: " + (enabled ? "<green>Enabled" : "<red>Disabled"));
        send(sender, "  <gray>Slope threshold: <white>"
                + plugin.getConfig().getDouble("automation.predictive-optimization.slope-threshold", 3.0) + " ms/s");
        send(sender, "  <gray>MSPT baseline: <white>"
                + plugin.getConfig().getDouble("automation.predictive-optimization.mspt-baseline", 30.0) + "ms");
        send(sender, "  <gray>Window: <white>"
                + plugin.getConfig().getInt("automation.predictive-optimization.window-seconds", 10) + "s");
        send(sender,
                "  <gray>Cooldown: <white>" + plugin.getConfig().getInt("automation.predictive-optimization.cooldown", 60)
                        + "s");

        if (po != null) {
            send(sender, "");
            send(sender, "  <yellow><bold>Current State");
            send(sender, "    <gray>MSPT trend slope: "
                    + (po.getLastSlope() > 0 ? "<red>+" : "<green>") + String.format("%.2f", po.getLastSlope()) + " ms/s");
            send(sender, "    <gray>Avg MSPT: <white>" + String.format("%.1f", po.getLastAvgMSPT()) + "ms");
            send(sender, "    <gray>Triggered: " + (po.isPredictiveTriggered() ? "<red>⚠ YES" : "<green>No"));
            send(sender, "    <gray>Total triggers: <yellow>" + po.getTriggerCount());
            if (po.getLastTriggerTime() > 0) {
                long ago = (System.currentTimeMillis() - po.getLastTriggerTime()) / 1000;
                send(sender, "    <gray>Last trigger: <white>" + ago + "s ago");
            }
        }
        send(sender, "");
    }

    // ══════════════════════════════════════════════════
    // Frustum Culler
    // ══════════════════════════════════════════════════

    private void showFrustum(CommandSender sender) {
        FrustumCuller fc = plugin.getFrustumCuller();
        boolean enabled = plugin.getConfig().getBoolean("modules.mob-ai.enabled", true);

        send(sender, "");
        send(sender, "<red><bold>  ≡ Frustum Culler ≡");
        send(sender, "");
        send(sender, "  <gray>Status: " + (enabled ? "<green>Enabled" : "<red>Disabled"));
        send(sender,
                "  <gray>FOV: <white>" + plugin.getConfig().getDouble("modules.mob-ai.fov-degrees", 110) + "°");
        send(sender,
                "  <gray>Max radius: <white>" + plugin.getConfig().getDouble("modules.mob-ai.active-radius", 48) + " blocks");
        send(sender, "  <gray>Behind safe radius: <white>"
                + plugin.getConfig().getDouble("modules.mob-ai.behind-safe-radius", 12) + " blocks");
        send(sender, "  <gray>Interval: <white>"
                + plugin.getConfig().getInt("modules.mob-ai.update-interval", 40) + " ticks");

        if (fc != null) {
            send(sender, "");
            send(sender, "  <yellow><bold>Last Cycle");
            send(sender, "    <gray>Mobs processed: <white>" + fc.getLastProcessed());
            send(sender, "    <gray>AI culled (behind): " + (fc.getLastCulled() > 0 ? "<yellow>" : "<green>") + fc.getLastCulled());
            send(sender,
                    "    <gray>AI restored (in view): " + (fc.getLastRestored() > 0 ? "<yellow>" : "<green>") + fc.getLastRestored());
        }
        send(sender, "");
    }

    // ══════════════════════════════════════════════════
    // World Chunk Guard
    // ══════════════════════════════════════════════════

    private void showWorldGuard(CommandSender sender) {
        WorldChunkGuard wg = plugin.getWorldChunkGuard();
        boolean enabled = plugin.getConfig().getBoolean("modules.chunks.world-guard.enabled", true);

        send(sender, "");
        send(sender, "<red><bold>  ≡ World Chunk Guard ≡");
        send(sender, "");
        send(sender, "  <gray>Status: " + (enabled ? "<green>Enabled" : "<red>Disabled"));
        send(sender, "  <gray>Overload multiplier: <white>"
                + plugin.getConfig().getDouble("modules.chunks.world-guard.overload-multiplier", 2.0) + "x");
        send(sender, "  <gray>Check interval: <white>"
                + plugin.getConfig().getInt("modules.chunks.world-guard.check-interval", 10) + "s");
        send(sender, "  <gray>Max retries before evacuate: <white>"
                + plugin.getConfig().getInt("modules.chunks.world-guard.max-retries", 3));
        send(sender, "  <gray>Evacuate world: <white>"
                + plugin.getConfig().getString("modules.chunks.world-guard.evacuate-world", "world"));

        if (wg != null && wg.getLastCheckTime() > 0) {
            long ago = (System.currentTimeMillis() - wg.getLastCheckTime()) / 1000;
            send(sender, "");
            send(sender, "  <yellow><bold>Last Check <dark_gray>(" + ago + "s ago)");
            send(sender, "    <gray>Chunks unloaded: " + (wg.getLastTotalUnloaded() > 0 ? "<yellow>" : "<green>")
                    + wg.getLastTotalUnloaded());
        }

        // Per-world status
        if (wg != null && !wg.getWorldStatuses().isEmpty()) {
            send(sender, "");
            send(sender, "  <yellow><bold>World Status");
            for (WorldChunkGuard.WorldChunkStatus ws : wg.getWorldStatuses().values()) {
                String statusColor = ws.overloaded ? "<red>" : "<green>";
                String statusIcon = ws.overloaded ? "⚠" : "✔";
                send(sender, "    <dark_gray>▸ <white>" + ws.worldName
                        + " <dark_gray>| " + statusColor + statusIcon
                        + " <dark_gray>| <gray>C: " + (ws.overloaded ? "<red>" : "<green>") + ws.loadedChunks
                        + "<dark_gray>/<gray>" + ws.expectedMax
                        + " <dark_gray>| <gray>P: <yellow>" + ws.playerCount
                        + " <dark_gray>| <gray>VD: <yellow>" + ws.viewDistance);
                if (ws.overloaded || !"OK".equals(ws.lastAction)) {
                    send(sender, "      <gray>Action: " + (ws.overloaded ? "<red>" : "<gray>") + ws.lastAction);
                }
            }
        } else {
            send(sender, "  <dark_gray>No world data yet.");
        }
        send(sender, "");
    }

    // ══════════════════════════════════════════════════
    // Memory Leak Detector
    // ══════════════════════════════════════════════════

    private void showMemory(CommandSender sender) {
        MemoryLeakDetector mld = plugin.getMemoryLeakDetector();
        boolean enabled = plugin.getConfig().getBoolean("system.memory-leak-detection.enabled", true);

        Runtime rt = Runtime.getRuntime();
        long usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMB = rt.maxMemory() / (1024 * 1024);
        double pct = (double) usedMB / maxMB * 100;

        send(sender, "");
        send(sender, "<red><bold>  ≡ Memory Leak Detector ≡");
        send(sender, "");
        send(sender, "  <gray>Status: " + (enabled ? "<green>Enabled" : "<red>Disabled"));
        send(sender, "  <gray>Heap: <white>" + usedMB + "MB <dark_gray>/ <white>" + maxMB + "MB <dark_gray>(" + String.format("%.0f", pct) + "%)");

        if (mld != null) {
            // Leak status
            send(sender, "  <gray>Leak Suspected: "
                    + (mld.isLeakSuspected() ? "<red><bold>YES" : "<green>No"));
            if (mld.getLastSlope() != 0) {
                String slopeColor = mld.getLastSlope() > 0 ? "<red>+" : "<green>";
                send(sender, "  <gray>Post-GC Trend: " + slopeColor
                        + String.format("%.1f", mld.getLastSlope()) + " MB/min");
            }
            if (mld.getLastPostGCBaseline() > 0) {
                send(sender, "  <gray>Post-GC Baseline: <white>"
                        + String.format("%.0f", mld.getLastPostGCBaseline()) + "MB");
            }
            send(sender, "  <gray>GC Rate: <yellow>" + String.format("%.1f", mld.getGcFrequency()) + " <gray>/min");

            // Per-player memory
            int playerCount = Bukkit.getOnlinePlayers().size();
            if (playerCount > 0) {
                send(sender, "  <gray>Per-Player: <white>"
                        + String.format("%.0f", mld.getPerPlayerMemoryMB()) + " MB/player"
                        + " <dark_gray>(" + playerCount + " online)");
            }

            // Heap Pools
            if (!mld.getHeapPools().isEmpty()) {
                send(sender, "");
                send(sender, "  <yellow><bold>Heap Pools");
                for (MemoryLeakDetector.PoolInfo pool : mld.getHeapPools().values()) {
                    String maxStr = pool.maxMB > 0 ? pool.maxMB + "MB" : "?";
                    String postGC = pool.postGCMB >= 0 ? " <dark_gray>(post-GC: <white>" + pool.postGCMB + "MB<dark_gray>)" : "";
                    send(sender, "    <dark_gray>▸ <white>" + pool.name
                            + " <dark_gray>| <gray>Used: <yellow>" + pool.usedMB + "MB"
                            + " <dark_gray>/ <gray>" + maxStr + postGC);
                }
            }

            // Baseline History (mini sparkline)
            java.util.List<MemoryLeakDetector.PostGCSample> history = mld.getBaselineHistory();
            if (!history.isEmpty()) {
                send(sender, "");
                send(sender, "  <yellow><bold>Baseline Trend <dark_gray>(" + history.size() + " samples)");
                StringBuilder sparkline = new StringBuilder("    <gray>");
                double min = history.stream().mapToDouble(s -> s.baselineMB).min().orElse(0);
                double max = history.stream().mapToDouble(s -> s.baselineMB).max().orElse(1);
                double range = max - min;
                if (range < 1)
                    range = 1;
                String[] bars = { "▁", "▂", "▃", "▄", "▅", "▆", "▇", "█" };
                for (MemoryLeakDetector.PostGCSample s : history) {
                    int idx = (int) Math.min(7, ((s.baselineMB - min) / range) * 7);
                    sparkline.append(bars[idx]);
                }
                sparkline.append(" <dark_gray>[").append(String.format("%.0f", min))
                        .append("-").append(String.format("%.0f", max)).append("MB]");
                send(sender, sparkline.toString());
            }

            // Consecutive signals
            if (mld.getConsecutiveLeakSignals() > 0) {
                send(sender, "  <gray>Leak signal streak: <red>" + mld.getConsecutiveLeakSignals()
                        + " <dark_gray>(alerts at 3+)");
            }
        }

        // Config
        send(sender, "");
        send(sender, "  <yellow><bold>Config");
        send(sender, "    <gray>Check interval: <white>"
                + plugin.getConfig().getInt("system.memory-leak-detection.check-interval-minutes", 30) + "m");
        send(sender, "    <gray>Slope threshold: <white>"
                + plugin.getConfig().getDouble("system.memory-leak-detection.warn-slope-threshold", 5.0)
                + " MB/min");
        send(sender, "    <gray>Window size: <white>"
                + plugin.getConfig().getInt("system.memory-leak-detection.window-size", 20) + " samples");
        send(sender, "    <gray>Notify: <white>"
                + plugin.getConfig().getBoolean("system.memory-leak-detection.notify", true));
        send(sender, "");
    }

    // ══════════════════════════════════════════════════
    // Web Optimizer
    // ══════════════════════════════════════════════════

    private void handleWeb(CommandSender sender, String[] args) {
        String apiUrl = plugin.getConfig().getString("web.api-url", "https://lesslag-api.daucatmoitu.workers.dev");
        String webUrl = plugin.getConfig().getString("web.dashboard-url", "https://lesslag-web.vercel.app");

        if (args.length >= 2 && args[1].equalsIgnoreCase("status")) {
            // Check API health
            send(sender, plugin.getPrefix() + "<gray>Checking API status...");
            LessLagApiClient client = new LessLagApiClient(apiUrl);
            client.isReachable().thenAccept(reachable -> {
                SchedulerAdapter.runGlobal(plugin, () -> {
                    if (reachable) {
                        send(sender, plugin.getPrefix() + "<green>LessLag API is online!");
                    } else {
                        send(sender, plugin.getPrefix() + "<red>API is unreachable at <white>" + apiUrl);
                    }
                });
            });
            return;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("link")) {
            // Delegate to WebLinkCommand — encodes hardware payload in URL, no API call
            // needed
            new WebLinkCommand(plugin).execute(sender);
            return;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("analyze")) {
            // Send server info to API for remote analysis
            send(sender, plugin.getPrefix() + "<gray>Sending server data for analysis...");
            String profile = args.length >= 3 ? args[2] : "SMP";
            String tier = args.length >= 4 ? args[3] : "MID";
            String level = args.length >= 5 ? args[4] : "BALANCED";

            LessLagApiClient client = new LessLagApiClient(apiUrl);
            var payload = LessLagApiClient.buildServerPayload(plugin, profile, tier, level);
            client.evaluate(payload).thenAccept(response -> {
                SchedulerAdapter.runGlobal(plugin, () -> {
                    send(sender, plugin.getPrefix() + "<green>Analysis complete! Results:");
                    // Show a summary (first 500 chars)
                    String preview = response.length() > 500 ? response.substring(0, 500) + "..." : response;
                    send(sender, "<gray>" + preview);
                    send(sender, plugin.getPrefix() + "<gray>Full results at: <aqua>" + webUrl);
                });
            }).exceptionally(ex -> {
                SchedulerAdapter.runGlobal(plugin, () -> {
                    send(sender, plugin.getPrefix() + "<red>Analysis failed: <white>" + ex.getMessage());
                });
                return null;
            });
            return;
        }

        // Default: show web info
        send(sender, "");
        send(sender, "<aqua><bold>  ≡ LessLag Web Optimizer ≡");
        send(sender, "");
        send(sender, "  <gray>Dashboard: <aqua>" + webUrl);
        send(sender, "  <gray>API:       <aqua>" + apiUrl);
        send(sender, "");
        send(sender, "  <white>Commands:");
        send(sender, "    <aqua>/lg web          <dark_gray>- <gray>Show this info");
        send(sender, "    <aqua>/lg web status   <dark_gray>- <gray>Check API health");
        send(sender, "    <aqua>/lg web link     <dark_gray>- <gray>Generate a shareable config link");
        send(sender, "    <aqua>/lg web analyze  <dark_gray>- <gray>Send server data for optimization");
        send(sender, "    <aqua>/lg web analyze <profile> <tier> <level>");
        send(sender, "");
    }

    // ══════════════════════════════════════════════════
    // Restore & Reload
    // ══════════════════════════════════════════════════

    private void doRestore(CommandSender sender) {
        plugin.getLogger().info(sender.getName() + " restored default settings.");
        plugin.getActionExecutor().restoreDefaults();
        send(sender, plugin.getPrefix() + "<green>All server settings restored to defaults.");
    }

    private void doReload(CommandSender sender) {
        plugin.getLogger().info(sender.getName() + " reloaded the plugin configuration.");
        plugin.reloadPlugin();
        send(sender, plugin.getPrefix() + "<green>Configuration reloaded successfully!");
        send(sender,
                plugin.getPrefix() + "<gray>Loaded <white>" + plugin.getTpsMonitor().getThresholds().size() + " <gray>thresholds.");
    }

    // ══════════════════════════════════════════════════
    // Utility
    // ══════════════════════════════════════════════════

    private void send(CommandSender sender, String message) {
        LessLag.sendMessage(sender, message);
    }

    private String formatTPS(double tps) {
        return getTpsColor(tps) + String.format("%.1f", tps) + " <dark_gray>(" + formatTPSBar(tps) + "<dark_gray>)";
    }

    private String formatTPSBar(double tps) {
        StringBuilder bar = new StringBuilder();
        int filled = (int) Math.round(tps);
        for (int i = 0; i < 20; i++) {
            bar.append(i < filled ? "<green>|" : "<dark_gray>|");
        }
        return bar.toString();
    }

    private String formatMSPT(double mspt) {
        String color;
        if (mspt <= 40)
            color = "<green>";
        else if (mspt <= 50)
            color = "<yellow>";
        else
            color = "<red>";
        return color + String.format("%.1f", mspt) + "ms";
    }

    private String getTpsColor(double tps) {
        if (tps >= 18)
            return "<green>";
        if (tps >= 16)
            return "<yellow>";
        if (tps >= 12)
            return "<red>";
        return "<dark_red>";
    }

    // ══════════════════════════════════════════════════
    // Villager Optimizer
    // ══════════════════════════════════════════════════

    private void showVillagerOptimizer(CommandSender sender) {
        VillagerOptimizer vo = plugin.getVillagerOptimizer();
        boolean enabled = plugin.getConfig().getBoolean("modules.villager-optimizer.enabled", true);

        send(sender, "");
        send(sender, "<red><bold>  ≡ Villager Optimizer ≡");
        send(sender, "");
        send(sender, "  <gray>Status: " + (enabled ? "<green>Enabled" : "<red>Disabled"));
        send(sender, "  <gray>Optimize Trapped Only: "
                + (plugin.getConfig().getBoolean("modules.villager-optimizer.optimize-trapped", true) ? "<green>Yes"
                        : "<red>No"));
        send(sender, "  <gray>Check Interval: <white>"
                + plugin.getConfig().getInt("modules.villager-optimizer.check-interval", 600) + " ticks");
        send(sender, "  <gray>Restore Duration: <white>"
                + plugin.getConfig().getInt("modules.villager-optimizer.ai-restore-duration", 30) + "s");

        if (vo != null && enabled) {
            send(sender, "");
            send(sender, "  <gray>Optimized (AI Disabled): <yellow>" + vo.getOptimizedCount());
            send(sender, "  <gray>Active (Restored): <green>" + vo.getActiveRestoredCount());
            send(sender, "  <dark_gray>(Villagers in trading halls are lobotomized until interaction)");
        }
        send(sender, "");
    }

    // ══════════════════════════════════════════════════
    // Density Optimizer
    // ══════════════════════════════════════════════════

    private void showDensityOptimizer(CommandSender sender) {
        DensityOptimizer dens = plugin.getDensityOptimizer();
        boolean enabled = plugin.getConfig().getBoolean("modules.density-optimizer.enabled", true);

        send(sender, "");
        send(sender, "<red><bold>  ≡ Density Optimizer ≡");
        send(sender, "");
        send(sender, "  <gray>Status: " + (enabled ? "<green>Enabled" : "<red>Disabled"));
        send(sender, "  <gray>Check Interval: <white>"
                + plugin.getConfig().getInt("modules.density-optimizer.check-interval", 40) + " ticks");
        send(sender, "  <gray>Bypass: "
                + (plugin.getConfig().getBoolean("modules.density-optimizer.bypass-tamed", true) ? "<green>Tamed " : "")
                + (plugin.getConfig().getBoolean("modules.density-optimizer.bypass-named", true) ? "<green>Named " : "")
                + (plugin.getConfig().getBoolean("modules.density-optimizer.bypass-leashed", true) ? "<green>Leashed" : ""));

        if (dens != null && enabled) {
            // Show limits
            send(sender, "");
            send(sender, "  <yellow><bold>Entity Limits");
            for (var entry : dens.getLimits().entrySet()) {
                send(sender, "    <dark_gray>▸ <white>" + entry.getKey().name() + " <dark_gray>- max <yellow>" + entry.getValue() + " <gray>/chunk");
            }

            // Stats
            send(sender, "");
            send(sender, "  <yellow><bold>Runtime Statistics");
            send(sender, "    <gray>Total mobs suppressed: <yellow>" + dens.getTotalMobsOptimized());
            send(sender, "    <gray>Total chunks scanned: <white>" + dens.getTotalChunksScanned());
            send(sender, "    <gray>Last pass: <yellow>" + dens.getLastPassOptimized()
                    + " mobs <gray>in <white>" + dens.getLastPassChunks() + " chunks");
        }
        send(sender, "");
    }

    // ══════════════════════════════════════════════════
    // Breeding Limiter
    // ══════════════════════════════════════════════════

    private void showBreedingLimiter(CommandSender sender) {
        BreedingLimiter bl = plugin.getBreedingLimiter();
        boolean enabled = plugin.getConfig().getBoolean("modules.breeding-limiter.enabled", true);

        send(sender, "");
        send(sender, "<red><bold>  ≡ Breeding Limiter ≡");
        send(sender, "");
        send(sender, "  <gray>Status: " + (enabled ? "<green>Enabled" : "<red>Disabled"));
        send(sender, "  <gray>Max animals/chunk: <white>"
                + plugin.getConfig().getInt("modules.breeding-limiter.max-animals-per-chunk", 20));

        if (bl != null && enabled) {
            send(sender, "");
            send(sender, "  <gray>Total breeding blocked: <yellow>" + bl.getTotalBlocked());
            if (!bl.getLastBlockedType().isEmpty()) {
                send(sender, "  <gray>Last blocked: <white>" + bl.getLastBlockedType()
                        + " <gray>in <white>" + bl.getLastBlockedWorld());
            }
        }
        send(sender, "");
    }
}
