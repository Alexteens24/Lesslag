package com.lesslag.command;

import com.lesslag.LessLag;
import com.lesslag.action.ActionExecutor;
import com.lesslag.action.ThresholdConfig;
import com.lesslag.setup.SetupCommandHandler;
import com.lesslag.web.LessLagApiClient;
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
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

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
    private SetupCommandHandler setupCommandHandler;

    public LagCommand(LessLag plugin) {
        this.plugin = plugin;
        File msgFile = new File(plugin.getDataFolder(), "messages.yml");
        if (msgFile.exists()) {
            messagesConfig = YamlConfiguration.loadConfiguration(msgFile);
        }
        if (plugin.getSetupAdvisor() != null) {
            setupCommandHandler = new SetupCommandHandler(plugin, plugin.getSetupAdvisor());
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
            sender.sendMessage(LessLag.colorize("&cYou don't have permission to use this command!"));
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
                if (setupCommandHandler != null) {
                    setupCommandHandler.handle(sender, args);
                } else {
                    send(sender, "&cSetup Advisor is disabled in config.");
                }
                break;
            case "web":
                handleWeb(sender, args);
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
        send(sender, "&c&l  ≡ LessLag v" + plugin.getPluginMeta().getVersion() + " ≡");
        send(sender, "&8  Server Performance Guardian");
        send(sender, "");
        send(sender, "  &e/lg status      &8- &7Quick server overview");
        send(sender, "  &e/lg health      &8- &7Full diagnostics report");
        send(sender, "  &e/lg tps         &8- &7TPS history (5s/10s/1m/5m/15m)");
        send(sender, "  &e/lg gc          &8- &7Force garbage collection");
        send(sender, "  &e/lg gcinfo      &8- &7GC collector statistics");
        send(sender, "  &e/lg tickmonitor &8- &7Tick spike statistics");
        send(sender, "  &e/lg entities    &8- &7Entity type breakdown");
        send(sender, "  &e/lg thresholds  &8- &7View threshold config & status");
        send(sender, "  &e/lg sources     &8- &7Analyze lag sources (async)");
        send(sender, "  &e/lg trace       &8- &7Shows Bottleneck Analyzer config");
        send(sender, "  &e/lg chunks      &8- &7Smart Chunk Limiter status");
        send(sender, "  &e/lg redstone    &8- &7Redstone Suppressor status");
        send(sender, "  &e/lg predictive  &8- &7Predictive Optimizer status");
        send(sender, "  &e/lg frustum     &8- &7Frustum Culler status");
        send(sender, "  &e/lg worldguard  &8- &7World Chunk Guard status");
        send(sender, "  &e/lg memory      &8- &7Memory Leak Detector status");
        send(sender, "  &e/lg villager    &8- &7Villager Optimizer status");
        send(sender, "  &e/lg density     &8- &7Density Optimizer status");
        send(sender, "  &e/lg breeding    &8- &7Breeding Limiter status");
        send(sender, "  &e/lg clear       &8- &7Clear entities &8[items|mobs|hostile|all]");
        send(sender, "  &e/lg ai          &8- &7AI control &8[disable|restore|status]");
        send(sender, "  &e/lg restore     &8- &7Restore all defaults");
        send(sender, "  &e/lg setup       &8- &7Setup Advisor wizard");
        send(sender, "  &e/lg web         &8- &7Web optimizer & remote analysis");
        send(sender, "  &e/lg reload      &8- &7Reload configuration");
        send(sender, "");
        send(sender, "  &8Permissions: &7lesslag.admin &8(commands) &7lesslag.notify &8(alerts)");
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
            statusColor = "&a";
            statusText = "✔ NORMAL";
        }

        // Health Score (0-100) computed from TPS, MSPT, memory
        int healthScore = computeHealthScore(tps);
        String healthColor = healthScore >= 80 ? "&a" : healthScore >= 50 ? "&e" : "&c";
        String healthBar = buildBar(healthScore, 100, 20);

        // TPS bar with gradient
        StringBuilder tpsBar = new StringBuilder();
        int filled = (int) Math.round(tps.getCurrentTPS());
        for (int i = 0; i < 20; i++) {
            if (i < filled) {
                tpsBar.append(i < 8 ? "&c" : i < 16 ? "&e" : "&a").append("█");
            } else {
                tpsBar.append("&8█");
            }
        }

        // Uptime
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        String uptime = formatDuration(uptimeMs);

        send(sender, "");
        send(sender, "&c&l  ≡ LessLag Status ≡");
        send(sender, "");
        send(sender, "  &7Health: " + healthColor + healthScore + "/100 " + healthBar);
        send(sender, "  &7TPS: " + tpsColor + String.format("%.1f", tps.getCurrentTPS()) + " &8/ &a20.0");
        send(sender, "  " + tpsBar);
        send(sender, "  &7MSPT: &f" + String.format("%.1f", tps.getCurrentMSPT()) + "ms &8("
                + "&7min: " + String.format("%.1f", tps.getMinMSPT())
                + " &8/ &7max: " + String.format("%.1f", tps.getMaxMSPT()) + "&8)");

        // MSPT percentiles
        double[] pct = tps.getMSPTPercentiles();
        send(sender, "  &7MSPT &8P50: &f" + String.format("%.1f", pct[0])
                + "ms &8P95: &f" + String.format("%.1f", pct[1])
                + "ms &8P99: &f" + String.format("%.1f", pct[2]) + "ms");
        send(sender, "");
        send(sender, "  &7Status: " + statusColor + statusText);
        send(sender, "  &7Modified: " + (tps.isSettingsModified() ? "&eYes &8(settings changed)" : "&aNo"));
        send(sender, "  &7RAM: &f" + plugin.getActionExecutor().getMemoryInfo());
        send(sender, "  &7Players: &f" + Bukkit.getOnlinePlayers().size() + " &8/ &f" + Bukkit.getMaxPlayers());
        send(sender, "  &7Entities: &f" + plugin.getActionExecutor().getTotalEntityCount());
        send(sender, "  &7Uptime: &f" + uptime);

        // Active optimizers summary
        send(sender, "");
        send(sender, "  &e&lActive Optimizers");
        PredictiveOptimizer po = plugin.getPredictiveOptimizer();
        String predState = po != null && po.isPredictiveTriggered() ? "&c⚠ TRIGGERED" : "&a✔ Idle";
        send(sender, "    &7Predictive: " + predState
                + (po != null && po.getTriggerCount() > 0 ? " &8(" + po.getTriggerCount() + " total)" : ""));

        VillagerOptimizer vo = plugin.getVillagerOptimizer();
        if (vo != null) {
            send(sender, "    &7Villagers: &e" + vo.getOptimizedCount() + " &7optimized, &a"
                    + vo.getActiveRestoredCount() + " &7active");
        }

        DensityOptimizer dens = plugin.getDensityOptimizer();
        if (dens != null && dens.isEnabled()) {
            send(sender, "    &7Density: &e" + dens.getTotalMobsOptimized() + " &7mobs suppressed");
        }

        FrustumCuller fc = plugin.getFrustumCuller();
        if (fc != null) {
            send(sender, "    &7Frustum: &e" + fc.getLastCulled() + " &7culled, &a"
                    + fc.getLastRestored() + " &7restored");
        }

        // Workload queue
        com.lesslag.WorkloadDistributor wd = plugin.getWorkloadDistributor();
        if (wd != null) {
            int queueSize = wd.getQueueSize();
            String qColor = queueSize == 0 ? "&a" : queueSize < 50 ? "&e" : "&c";
            send(sender, "    &7Workload Queue: " + qColor + queueSize
                    + (wd.isProcessing() ? " &8(processing)" : " &8(idle)"));
        }

        // Tick spikes
        TickMonitor tick = plugin.getTickMonitor();
        if (tick != null && tick.getSpikeCount() > 0) {
            send(sender, "    &7Tick Spikes: &e" + tick.getSpikeCount()
                    + " &8(worst: &f" + String.format("%.0f", tick.getWorstTickMs()) + "ms&8)");
        }

        // Bottleneck spikes
        BottleneckAnalyzer ba = plugin.getBottleneckAnalyzer();
        if (ba != null && ba.getTotalSpikes() > 0) {
            send(sender, "    &7Bottlenecks: &c" + ba.getTotalSpikes()
                    + " &8(worst: &f" + ba.getWorstSpikeDurationMs() + "ms&8)");
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
        double memUsed = (double)(rt.totalMemory() - rt.freeMemory()) / rt.maxMemory();
        double memScore = Math.max(0, Math.min(30, (1.0 - memUsed) * 30));
        return (int) Math.round(tpsScore + msptScore + memScore);
    }

    private String buildBar(int value, int max, int width) {
        int filled = (int) Math.round((double) value / max * width);
        StringBuilder bar = new StringBuilder();
        String color = value * 100 / max >= 80 ? "&a" : value * 100 / max >= 50 ? "&e" : "&c";
        for (int i = 0; i < width; i++) {
            bar.append(i < filled ? color + "█" : "&8█");
        }
        return bar.toString();
    }

    private String formatDuration(long ms) {
        long hours = TimeUnit.MILLISECONDS.toHours(ms);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60;
        if (hours > 0) return hours + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m " + seconds + "s";
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
        send(sender, "&c&l  ≡ Threshold Configuration ≡");
        send(sender, "  &7Current TPS: " + getTpsColor(tps.getCurrentTPS())
                + String.format("%.1f", tps.getCurrentTPS()));
        send(sender, "");

        if (thresholds.isEmpty()) {
            send(sender, "  &7No thresholds configured.");
        } else {
            for (ThresholdConfig threshold : thresholds) {
                String color = threshold.getColor(thresholds);
                boolean isActive = threshold.equals(active);
                String marker = isActive ? " &c&l◄ ACTIVE" : "";

                // Header line
                send(sender, "  " + color + (isActive ? "▶" : "▸") + " " + threshold.getName().toUpperCase()
                        + " &8(TPS ≤ " + color + threshold.getTps() + "&8)" + marker);

                // Actions
                if (!threshold.getActions().isEmpty()) {
                    send(sender, "    &7Actions: &f" + String.join("&8, &f", threshold.getActions()));
                }

                // Commands
                if (!threshold.getCommands().isEmpty()) {
                    send(sender, "    &7Commands: &f" + threshold.getCommands().size() + " configured");
                }

                // Notification summary
                StringBuilder notifyInfo = new StringBuilder("    &7Notify: ");
                if (threshold.isNotifyChat())
                    notifyInfo.append("&aChat ");
                if (threshold.isNotifyActionbar())
                    notifyInfo.append("&aActionBar ");
                if (threshold.isNotifySound())
                    notifyInfo.append("&aSound&8(&f")
                            .append(threshold.getSoundType()).append("&8) ");
                if (threshold.isBroadcast())
                    notifyInfo.append("&6Broadcast ");
                send(sender, notifyInfo.toString());

                send(sender, "");
            }
        }

        // Available actions
        send(sender, "&8  ─────────────────────────────────────");
        send(sender, "  &7Available actions:");
        for (String action : ActionExecutor.ACTIONS_SORTED) {
            send(sender, "    &8• &f" + action + " &8- &7" + getActionDescription(action));
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
        send(sender, "&c&l  ≡ Server Health Report ≡");
        send(sender, "&8  ─────────────────────────────────────");

        if (!showTps && !showMspt && !showCpu && !showMemory && !showDisk && !showWorlds && !showEntityBreakdown) {
            send(sender, "");
            send(sender, "  &7All health-report sections are disabled in config.");
            send(sender, "");
            send(sender, "&8  ─────────────────────────────────────");
            send(sender, "");
            return;
        }

        // TPS Section
        if (showTps) {
            send(sender, "");
            send(sender, "  &e&lTPS &8(Ticks Per Second)");
            send(sender, "    &7 5s: " + formatTPS(tps.getTPS5s()));
            send(sender, "    &710s: " + formatTPS(tps.getTPS10s()));
            send(sender, "    &7 1m: " + formatTPS(tps.getTPS1m()));
            send(sender, "    &7 5m: " + formatTPS(tps.getTPS5m()));
            send(sender, "    &715m: " + formatTPS(tps.getTPS15m()));
        }

        // MSPT Section
        if (showMspt) {
            send(sender, "");
            send(sender, "  &e&lMSPT &8(Milliseconds Per Tick)");
            send(sender, "    &7Avg: " + formatMSPT(tps.getCurrentMSPT()));
            send(sender, "    &7Min: " + formatMSPT(tps.getMinMSPT()));
            send(sender, "    &7Max: " + formatMSPT(tps.getMaxMSPT()));
        }

        // CPU Section
        if (showCpu) {
            send(sender, "");
            send(sender, "  &e&lCPU");
            try {
                OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
                double loadAvg = os.getSystemLoadAverage();
                int cpus = os.getAvailableProcessors();
                send(sender, "    &7Cores: &f" + cpus);
                send(sender, "    &7Load Avg: &f" + (loadAvg >= 0 ? String.format("%.2f", loadAvg) : "N/A"));
            } catch (Exception e) {
                send(sender, "    &7CPU info unavailable");
            }
        }

        // Memory Section
        if (showMemory) {
            send(sender, "");
            send(sender, "  &e&lMemory");
            Runtime rt = Runtime.getRuntime();
            long usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
            long allocMB = rt.totalMemory() / (1024 * 1024);
            long maxMB = rt.maxMemory() / (1024 * 1024);
            long freePercent = 100 - (usedMB * 100 / maxMB);
            String memColor = freePercent > 30 ? "&a" : freePercent > 15 ? "&e" : "&c";

            // Memory bar
            int memFilled = (int) ((usedMB * 20) / maxMB);
            StringBuilder memBar = new StringBuilder();
            for (int i = 0; i < 20; i++) {
                memBar.append(i < memFilled ? memColor + "█" : "&8█");
            }

            send(sender, "    &7Used:      " + memColor + usedMB + "MB &8/ &f" + maxMB + "MB &8("
                    + memColor + (usedMB * 100 / maxMB) + "%&8)");
            send(sender, "    " + memBar);
            send(sender, "    &7Allocated: &f" + allocMB + "MB");
            send(sender, "    &7Free:      &f" + (maxMB - usedMB) + "MB");
        }

        // Disk Section
        if (showDisk) {
            send(sender, "");
            send(sender, "  &e&lDisk");
            File root = new File(".");
            long diskFreeMB = root.getFreeSpace() / (1024 * 1024);
            long diskTotalMB = root.getTotalSpace() / (1024 * 1024);
            long diskUsedMB = diskTotalMB - diskFreeMB;
            String diskColor = diskFreeMB > 5000 ? "&a" : diskFreeMB > 1000 ? "&e" : "&c";
            send(sender, "    &7Used: " + diskColor + diskUsedMB + "MB &8/ &f" + diskTotalMB + "MB");
            send(sender, "    &7Free: " + diskColor + diskFreeMB + "MB");
        }

        // Uptime
        send(sender, "");
        send(sender, "  &e&lServer");
        RuntimeMXBean runtimeMX = ManagementFactory.getRuntimeMXBean();
        long uptimeMs = runtimeMX.getUptime();
        long hours = TimeUnit.MILLISECONDS.toHours(uptimeMs);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(uptimeMs) % 60;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(uptimeMs) % 60;
        send(sender, "    &7Uptime: &f" + hours + "h " + minutes + "m " + seconds + "s");
        send(sender, "    &7Java: &f" + System.getProperty("java.version"));
        send(sender, "    &7OS: &f" + System.getProperty("os.name") + " " + System.getProperty("os.arch"));

        // World Overview
        if (showWorlds) {
            send(sender, "");
            send(sender, "  &e&lWorlds");
            for (World world : Bukkit.getWorlds()) {
                int entities = world.getEntities().size();
                int chunks = world.getLoadedChunks().length;
                int players = world.getPlayers().size();
                String entColor = entities > 500 ? "&c" : entities > 200 ? "&e" : "&a";
                String chkColor = chunks > 1000 ? "&c" : chunks > 500 ? "&e" : "&a";
                Integer simDistance = plugin.getSimulationDistanceSafe(world);
                String simText = simDistance != null ? simDistance.toString() : "N/A";
                send(sender, "    &8▸ &f" + world.getName()
                        + " &8| &7E: " + entColor + entities
                        + " &8| &7C: " + chkColor + chunks
                        + " &8| &7P: &e" + players
                        + " &8| &7VD: &e" + world.getViewDistance()
                        + " &8| &7SD: &e" + simText);
            }
        }

        if (showEntityBreakdown) {
            Map<String, Integer> breakdown = plugin.getActionExecutor().getEntityBreakdown();
            if (!breakdown.isEmpty()) {
                send(sender, "");
                send(sender, "  &e&lEntity Breakdown");
                int total = plugin.getActionExecutor().getTotalEntityCount();
                breakdown.entrySet().stream()
                        .sorted((a, b) -> b.getValue() - a.getValue())
                        .limit(10)
                        .forEach(entry -> {
                            String color = entry.getValue() > 100 ? "&c" : entry.getValue() > 50 ? "&e" : "&a";
                            String pct = total > 0 ? String.format("%.0f", entry.getValue() * 100.0 / total) + "%" : "";
                            send(sender, "    &8▸ &f" + entry.getKey() + " &8- " + color
                                    + entry.getValue() + " &8(" + pct + ")");
                        });
            }
        }

        send(sender, "");
        send(sender, "&8  ─────────────────────────────────────");
        send(sender, "");
    }

    // ══════════════════════════════════════════════════
    // TPS History
    // ══════════════════════════════════════════════════

    private void showTPS(CommandSender sender) {
        TPSMonitor tps = plugin.getTpsMonitor();

        send(sender, "");
        send(sender, "&c&l  ≡ TPS History ≡");
        send(sender, "");
        send(sender, "  &7 5s avg: " + formatTPS(tps.getTPS5s()));
        send(sender, "  &710s avg: " + formatTPS(tps.getTPS10s()));
        send(sender, "  &7 1m avg: " + formatTPS(tps.getTPS1m()));
        send(sender, "  &7 5m avg: " + formatTPS(tps.getTPS5m()));
        send(sender, "  &715m avg: " + formatTPS(tps.getTPS15m()));
        send(sender, "");
        send(sender, "  &7MSPT: &f" + String.format("%.1f", tps.getCurrentMSPT()) + "ms " +
                "&8(&7min " + String.format("%.1f", tps.getMinMSPT()) +
                " / max " + String.format("%.1f", tps.getMaxMSPT()) + "&8)");
        send(sender, "");
    }

    // ══════════════════════════════════════════════════
    // GC
    // ══════════════════════════════════════════════════

    private void doGC(CommandSender sender) {
        plugin.getLogger().info(sender.getName() + " requested manual GC (disabled — use /lg gcinfo for stats).");
        send(sender, plugin.getPrefix() + "&7Manual GC is &cdisabled&7 to prevent stop-the-world pauses.");
        send(sender, plugin.getPrefix() + "&7Use &f/lg gcinfo&7 for Garbage Collection statistics.");
        send(sender, plugin.getPrefix() + "&7RAM: &f" + plugin.getActionExecutor().getMemoryInfo());
    }

    private void showGCInfo(CommandSender sender) {
        GCMonitor gc = plugin.getGcMonitor();

        send(sender, "");
        send(sender, "&c&l  ≡ GC Information ≡");
        send(sender, "");
        send(sender, "  &7Total collections: &e" + gc.getTotalCollections());
        send(sender, "  &7Total GC time: &e" + gc.getTotalTimeMs() + "ms");
        send(sender, "");
        send(sender, "  &7&lCollectors:");
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
        send(sender, "&c&l  ≡ Tick Monitor ≡");
        send(sender, "");
        send(sender, "  &7Last tick: &f" + String.format("%.1f", tick.getLastTickMs()) + "ms");
        send(sender, "  &7Worst tick: &f" + String.format("%.1f", tick.getWorstTickMs()) + "ms");
        send(sender, "  &7Spike count: &e" + tick.getSpikeCount() +
                " &8(threshold: " + plugin.getConfig().getDouble("system.tick-monitor.threshold-ms", 100) + "ms)");

        // MSPT percentiles from TPSMonitor's buffer
        if (tps != null) {
            double[] pct = tps.getMSPTPercentiles();
            send(sender, "");
            send(sender, "  &e&lMSPT Distribution");
            send(sender, "    &7P50: &f" + String.format("%.1f", pct[0]) + "ms &8(median)");
            send(sender, "    &7P95: &f" + String.format("%.1f", pct[1]) + "ms &8(95th percentile)");
            send(sender, "    &7P99: &f" + String.format("%.1f", pct[2]) + "ms &8(99th percentile)");
            send(sender, "    &7Avg: &f" + String.format("%.1f", tps.getCurrentMSPT()) + "ms");
            send(sender, "    &7Min: &f" + String.format("%.1f", tps.getMinMSPT()) + "ms");
            send(sender, "    &7Max: &f" + String.format("%.1f", tps.getMaxMSPT()) + "ms");
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
        send(sender, "&c&l  ≡ Entity Breakdown ≡");
        send(sender, "  &7Total: &f" + total);
        send(sender, "");

        breakdown.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(15)
                .forEach(entry -> {
                    String color = entry.getValue() > 100 ? "&c" : entry.getValue() > 50 ? "&e" : "&a";
                    String pct = total > 0 ? String.format("%.0f", entry.getValue() * 100.0 / total) + "%" : "";
                    send(sender, "  &8▸ &f" + entry.getKey() + " &8- " + color + entry.getValue()
                            + " &8(" + pct + ")");
                });

        if (breakdown.size() > 15) {
            send(sender, "  &8... and " + (breakdown.size() - 15) + " more types");
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
                String msg = getMessage("messages.items-cleared", "&aScheduled clearing of ground items.");
                send(sender, plugin.getPrefix() + msg);
                break;
            }
            case "xp": {
                plugin.getActionExecutor().clearXPOrbs();
                String msg = getMessage("messages.xp-cleared", "&aScheduled clearing of XP orbs.");
                send(sender, plugin.getPrefix() + msg);
                break;
            }
            case "mobs": {
                plugin.getActionExecutor().clearExcessMobs();
                String msg = getMessage("messages.mobs-cleared", "&aScheduled removal of excess mobs.");
                send(sender, plugin.getPrefix() + msg);
                break;
            }
            case "hostile": {
                plugin.getActionExecutor().killHostileMobs();
                String msg = getMessage("messages.hostile-killed", "&aScheduled killing of hostile mobs.");
                send(sender, plugin.getPrefix() + msg);
                break;
            }
            case "all":
            default: {
                plugin.getActionExecutor().clearAll();
                String msg = getMessage("messages.all-cleared",
                        "&aScheduled clearing of all entities (items, xp, mobs).");
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
                String msg = getMessage("messages.ai-disabled", "&aScheduled AI disable task.");
                send(sender, plugin.getPrefix() + msg);
                break;
            }
            case "restore": {
                plugin.getActionExecutor().restoreMobAI();
                send(sender, plugin.getPrefix()
                        + "&aScheduled AI restoration for all mobs. This process is batched to prevent lag.");
                break;
            }
            case "status":
            default: {
                // Snapshot world/entity references on main thread first,
                // then count async to avoid stalling the tick with a full entity scan.
                send(sender, plugin.getPrefix() + "&7Counting mobs (async)...");
                final List<World> worlds = Bukkit.getWorlds();
                final int activeRadius = plugin.getConfig().getInt("modules.mob-ai.active-radius", 48);
                final int protectedTypes = plugin.getConfig().getStringList("modules.mob-ai.protected").size();

                SchedulerAdapter.runAsync(plugin, () -> {
                    int countTotal = 0, countNoAI = 0;
                    for (World world : worlds) {
                        for (org.bukkit.entity.Entity entity : world.getEntities()) {
                            if (entity instanceof org.bukkit.entity.Mob) {
                                countTotal++;
                                if (!plugin.isMobAwareSafe((org.bukkit.entity.Mob) entity))
                                    countNoAI++;
                            }
                        }
                    }
                    final int ft = countTotal, fn = countNoAI;
                    SchedulerAdapter.runGlobal(plugin, () -> {
                        send(sender, "");
                        send(sender, "&c&l  ≡ AI Status ≡");
                        send(sender, "  &7Total mobs: &f" + ft);
                        send(sender, "  &7AI disabled: &e" + fn);
                        send(sender, "  &7AI active: &a" + (ft - fn));
                        send(sender, "  &7Active radius: &f" + activeRadius + " blocks");
                        send(sender, "  &7Protected types: &f" + protectedTypes);
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
        send(sender, plugin.getPrefix() + "&7Analyzing lag sources (async)...");

        plugin.getLagSourceAnalyzer().analyzeFullAsync().thenAccept(result -> {
            // Dispatch display back to main thread
            SchedulerAdapter.runGlobal(plugin, () -> {
                send(sender, "");
                send(sender, "&c&l  ≡ Lag Source Analysis ≡");
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
                    send(sender, "  &c&lWARNINGS &8(" + result.sources.size() + " issues detected)");
                    int shown = 0;
                    for (var source : result.sources) {
                        if (shown >= 5)
                            break;
                        send(sender, "    &c⚠ " + source.description);
                        shown++;
                    }
                    if (result.sources.size() > 5) {
                        send(sender, "    &8  ... and " + (result.sources.size() - 5) + " more");
                    }
                } else {
                    send(sender, "");
                    send(sender, "  &a✔ No significant lag sources detected.");
                }

                send(sender, "");
            });
        }).exceptionally(e -> {
            SchedulerAdapter.runGlobal(plugin, () -> {
                send(sender, plugin.getPrefix() + "&cFailed to analyze lag sources: " + e.getMessage());
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
        send(sender, "&c&l  ≡ Bottleneck Analyzer (Trace) ≡");
        send(sender, "");
        send(sender, "  &7Status: " + (enabled ? "&aEnabled (Running as Watchdog)" : "&cDisabled"));
        send(sender, "  &7Lag Threshold: &f"
                + plugin.getConfig().getLong("system.bottleneck-analyzer.threshold-ms", 100L) + "ms");
        send(sender, "  &7Sampling Interval: &f"
                + plugin.getConfig().getLong("system.bottleneck-analyzer.sample-interval-ms", 5L) + "ms");

        if (ba != null && ba.getTotalSpikes() > 0) {
            send(sender, "");
            send(sender, "  &e&lRuntime Statistics");
            send(sender, "    &7Total spikes detected: &c" + ba.getTotalSpikes());
            send(sender, "    &7Worst spike: &c" + ba.getWorstSpikeDurationMs() + "ms");
            if (!ba.getWorstSpikeCulprit().isEmpty()) {
                String culprit = ba.getWorstSpikeCulprit();
                if (culprit.length() > 50) culprit = "..." + culprit.substring(culprit.length() - 47);
                send(sender, "    &7Worst culprit: &e" + culprit);
            }
            if (!ba.getLastSpikeCulprit().isEmpty()) {
                String last = ba.getLastSpikeCulprit();
                if (last.length() > 50) last = "..." + last.substring(last.length() - 47);
                send(sender, "    &7Last culprit: &e" + last);
                long ago = (System.currentTimeMillis() - ba.getLastSpikeTimeMs()) / 1000;
                send(sender, "    &7Last spike: &f" + ago + "s ago");
            }
        } else {
            send(sender, "");
            send(sender, "  &a✔ No lag spikes detected yet.");
        }

        send(sender, "");
        send(sender, "  &8The watchdog constantly monitors the main thread.");
        send(sender, "  &8If a tick exceeds the threshold, it samples the stack trace");
        send(sender, "  &8and identifies the exact method causing the hang.");
        send(sender, "");
    }

    // ══════════════════════════════════════════════════
    // Smart Chunk Limiter
    // ══════════════════════════════════════════════════

    private void showChunkLimiter(CommandSender sender) {
        ChunkLimiter cl = plugin.getChunkLimiter();
        boolean enabled = plugin.getConfig().getBoolean("modules.entities.chunk-limiter.enabled", true);

        send(sender, "");
        send(sender, "&c&l  ≡ Smart Chunk Limiter ≡");
        send(sender, "");
        send(sender, "  &7Status: " + (enabled ? "&aEnabled" : "&cDisabled"));
        send(sender,
                "  &7Max entities/chunk: &f"
                        + plugin.getConfig().getInt("modules.entities.chunk-limiter.max-entities-per-chunk", 50));
        send(sender, "  &7Scan interval: &f"
                + plugin.getConfig().getInt("modules.entities.chunk-limiter.scan-interval", 30) + "s");

        if (cl != null && cl.getLastScanTime() > 0) {
            long ago = (System.currentTimeMillis() - cl.getLastScanTime()) / 1000;
            send(sender, "");
            send(sender, "  &e&lLast Scan &8(" + ago + "s ago)");
            send(sender,
                    "    &7Hot chunks found: " + (cl.getLastHotChunks() > 0 ? "&c" : "&a") + cl.getLastHotChunks());
            send(sender, "    &7Entities removed: " + (cl.getLastEntitiesRemoved() > 0 ? "&e" : "&a")
                    + cl.getLastEntitiesRemoved());
        } else {
            send(sender, "  &8No scan data yet.");
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
        send(sender, "&c&l  ≡ Redstone Suppressor ≡");
        send(sender, "");
        send(sender, "  &7Status: " + (enabled ? "&aEnabled" : "&cDisabled"));
        send(sender, "  &7Max activations/chunk: &f"
                + plugin.getConfig().getInt("modules.redstone.max-activations-per-chunk", 200));
        send(sender, "  &7Window: &f" + plugin.getConfig().getInt("modules.redstone.window-seconds", 2) + "s");
        send(sender, "  &7Cooldown: &f" + plugin.getConfig().getInt("modules.redstone.cooldown-seconds", 10) + "s");

        if (rm != null) {
            send(sender, "");
            send(sender, "  &7Total suppressions: &e" + rm.getTotalSuppressed());
            int active = rm.getActiveSuppressedChunks();
            send(sender, "  &7Currently suppressed: " + (active > 0 ? "&c" : "&a") + active + " chunk(s)");

            if (!rm.getSuppressedChunks().isEmpty()) {
                send(sender, "");
                send(sender, "  &e&lActive Suppressions:");

                // Advanced stats
                if (plugin.getConfig().getBoolean("modules.redstone.advanced.enabled", true)) {
                    send(sender,
                            "  &7Max Frequency: &f"
                                    + plugin.getConfig().getInt("modules.redstone.advanced.max-frequency")
                                    + "/s");
                    send(sender,
                            "  &7Piston Limit: &f"
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
                    send(sender, "    &8▸ &fChunk (" + chunkInfo + ") &8- &e"
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
        send(sender, "&c&l  ≡ Predictive Optimizer ≡");
        send(sender, "");
        send(sender, "  &7Status: " + (enabled ? "&aEnabled" : "&cDisabled"));
        send(sender, "  &7Slope threshold: &f"
                + plugin.getConfig().getDouble("automation.predictive-optimization.slope-threshold", 3.0) + " ms/s");
        send(sender, "  &7MSPT baseline: &f"
                + plugin.getConfig().getDouble("automation.predictive-optimization.mspt-baseline", 30.0) + "ms");
        send(sender, "  &7Window: &f"
                + plugin.getConfig().getInt("automation.predictive-optimization.window-seconds", 10) + "s");
        send(sender,
                "  &7Cooldown: &f" + plugin.getConfig().getInt("automation.predictive-optimization.cooldown", 60)
                        + "s");

        if (po != null) {
            send(sender, "");
            send(sender, "  &e&lCurrent State");
            send(sender, "    &7MSPT trend slope: "
                    + (po.getLastSlope() > 0 ? "&c+" : "&a") + String.format("%.2f", po.getLastSlope()) + " ms/s");
            send(sender, "    &7Avg MSPT: &f" + String.format("%.1f", po.getLastAvgMSPT()) + "ms");
            send(sender, "    &7Triggered: " + (po.isPredictiveTriggered() ? "&c⚠ YES" : "&aNo"));
            send(sender, "    &7Total triggers: &e" + po.getTriggerCount());
            if (po.getLastTriggerTime() > 0) {
                long ago = (System.currentTimeMillis() - po.getLastTriggerTime()) / 1000;
                send(sender, "    &7Last trigger: &f" + ago + "s ago");
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
        send(sender, "&c&l  ≡ Frustum Culler ≡");
        send(sender, "");
        send(sender, "  &7Status: " + (enabled ? "&aEnabled" : "&cDisabled"));
        send(sender,
                "  &7FOV: &f" + plugin.getConfig().getDouble("modules.mob-ai.fov-degrees", 110) + "°");
        send(sender,
                "  &7Max radius: &f" + plugin.getConfig().getDouble("modules.mob-ai.active-radius", 48) + " blocks");
        send(sender, "  &7Behind safe radius: &f"
                + plugin.getConfig().getDouble("modules.mob-ai.behind-safe-radius", 12) + " blocks");
        send(sender, "  &7Interval: &f"
                + plugin.getConfig().getInt("modules.mob-ai.update-interval", 40) + " ticks");

        if (fc != null) {
            send(sender, "");
            send(sender, "  &e&lLast Cycle");
            send(sender, "    &7Mobs processed: &f" + fc.getLastProcessed());
            send(sender, "    &7AI culled (behind): " + (fc.getLastCulled() > 0 ? "&e" : "&a") + fc.getLastCulled());
            send(sender,
                    "    &7AI restored (in view): " + (fc.getLastRestored() > 0 ? "&e" : "&a") + fc.getLastRestored());
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
        send(sender, "&c&l  ≡ World Chunk Guard ≡");
        send(sender, "");
        send(sender, "  &7Status: " + (enabled ? "&aEnabled" : "&cDisabled"));
        send(sender, "  &7Overload multiplier: &f"
                + plugin.getConfig().getDouble("modules.chunks.world-guard.overload-multiplier", 2.0) + "x");
        send(sender, "  &7Check interval: &f"
                + plugin.getConfig().getInt("modules.chunks.world-guard.check-interval", 10) + "s");
        send(sender, "  &7Max retries before evacuate: &f"
                + plugin.getConfig().getInt("modules.chunks.world-guard.max-retries", 3));
        send(sender, "  &7Evacuate world: &f"
                + plugin.getConfig().getString("modules.chunks.world-guard.evacuate-world", "world"));

        if (wg != null && wg.getLastCheckTime() > 0) {
            long ago = (System.currentTimeMillis() - wg.getLastCheckTime()) / 1000;
            send(sender, "");
            send(sender, "  &e&lLast Check &8(" + ago + "s ago)");
            send(sender, "    &7Chunks unloaded: " + (wg.getLastTotalUnloaded() > 0 ? "&e" : "&a")
                    + wg.getLastTotalUnloaded());
        }

        // Per-world status
        if (wg != null && !wg.getWorldStatuses().isEmpty()) {
            send(sender, "");
            send(sender, "  &e&lWorld Status");
            for (WorldChunkGuard.WorldChunkStatus ws : wg.getWorldStatuses().values()) {
                String statusColor = ws.overloaded ? "&c" : "&a";
                String statusIcon = ws.overloaded ? "⚠" : "✔";
                send(sender, "    &8▸ &f" + ws.worldName
                        + " &8| " + statusColor + statusIcon
                        + " &8| &7C: " + (ws.overloaded ? "&c" : "&a") + ws.loadedChunks
                        + "&8/&7" + ws.expectedMax
                        + " &8| &7P: &e" + ws.playerCount
                        + " &8| &7VD: &e" + ws.viewDistance);
                if (ws.overloaded || !"OK".equals(ws.lastAction)) {
                    send(sender, "      &7Action: " + (ws.overloaded ? "&c" : "&7") + ws.lastAction);
                }
            }
        } else {
            send(sender, "  &8No world data yet.");
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
        send(sender, "&c&l  ≡ Memory Leak Detector ≡");
        send(sender, "");
        send(sender, "  &7Status: " + (enabled ? "&aEnabled" : "&cDisabled"));
        send(sender, "  &7Heap: &f" + usedMB + "MB &8/ &f" + maxMB + "MB &8(" + String.format("%.0f", pct) + "%)");

        if (mld != null) {
            // Leak status
            send(sender, "  &7Leak Suspected: "
                    + (mld.isLeakSuspected() ? "&c&lYES" : "&aNo"));
            if (mld.getLastSlope() != 0) {
                String slopeColor = mld.getLastSlope() > 0 ? "&c+" : "&a";
                send(sender, "  &7Post-GC Trend: " + slopeColor
                        + String.format("%.1f", mld.getLastSlope()) + " MB/min");
            }
            if (mld.getLastPostGCBaseline() > 0) {
                send(sender, "  &7Post-GC Baseline: &f"
                        + String.format("%.0f", mld.getLastPostGCBaseline()) + "MB");
            }
            send(sender, "  &7GC Rate: &e" + String.format("%.1f", mld.getGcFrequency()) + " &7/min");

            // Per-player memory
            int playerCount = Bukkit.getOnlinePlayers().size();
            if (playerCount > 0) {
                send(sender, "  &7Per-Player: &f"
                        + String.format("%.0f", mld.getPerPlayerMemoryMB()) + " MB/player"
                        + " &8(" + playerCount + " online)");
            }

            // Heap Pools
            if (!mld.getHeapPools().isEmpty()) {
                send(sender, "");
                send(sender, "  &e&lHeap Pools");
                for (MemoryLeakDetector.PoolInfo pool : mld.getHeapPools().values()) {
                    String maxStr = pool.maxMB > 0 ? pool.maxMB + "MB" : "?";
                    String postGC = pool.postGCMB >= 0 ? " &8(post-GC: &f" + pool.postGCMB + "MB&8)" : "";
                    send(sender, "    &8▸ &f" + pool.name
                            + " &8| &7Used: &e" + pool.usedMB + "MB"
                            + " &8/ &7" + maxStr + postGC);
                }
            }

            // Baseline History (mini sparkline)
            java.util.List<MemoryLeakDetector.PostGCSample> history = mld.getBaselineHistory();
            if (!history.isEmpty()) {
                send(sender, "");
                send(sender, "  &e&lBaseline Trend &8(" + history.size() + " samples)");
                StringBuilder sparkline = new StringBuilder("    &7");
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
                sparkline.append(" &8[").append(String.format("%.0f", min))
                        .append("-").append(String.format("%.0f", max)).append("MB]");
                send(sender, sparkline.toString());
            }

            // Consecutive signals
            if (mld.getConsecutiveLeakSignals() > 0) {
                send(sender, "  &7Leak signal streak: &c" + mld.getConsecutiveLeakSignals()
                        + " &8(alerts at 3+)");
            }
        }

        // Config
        send(sender, "");
        send(sender, "  &e&lConfig");
        send(sender, "    &7Check interval: &f"
                + plugin.getConfig().getInt("system.memory-leak-detection.check-interval-minutes", 30) + "m");
        send(sender, "    &7Slope threshold: &f"
                + plugin.getConfig().getDouble("system.memory-leak-detection.warn-slope-threshold", 5.0)
                + " MB/min");
        send(sender, "    &7Window size: &f"
                + plugin.getConfig().getInt("system.memory-leak-detection.window-size", 20) + " samples");
        send(sender, "    &7Notify: &f"
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
            send(sender, plugin.getPrefix() + "&7Checking API status...");
            LessLagApiClient client = new LessLagApiClient(apiUrl);
            client.isReachable().thenAccept(reachable -> {
                SchedulerAdapter.runGlobal(plugin, () -> {
                    if (reachable) {
                        send(sender, plugin.getPrefix() + "&aLessLag API is online!");
                    } else {
                        send(sender, plugin.getPrefix() + "&cAPI is unreachable at &f" + apiUrl);
                    }
                });
            });
            return;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("link")) {
            // Generate a session link for the web configurator
            send(sender, plugin.getPrefix() + "&7Capturing server config and generating link...");

            LessLagApiClient client = new LessLagApiClient(apiUrl);
            var payload = LessLagApiClient.buildSessionPayload(plugin);
            client.createSession(payload).thenAccept(response -> {
                SchedulerAdapter.runGlobal(plugin, () -> {
                    String url = LessLagApiClient.extractSessionUrl(response);
                    if (url == null) {
                        send(sender, plugin.getPrefix() + "&cFailed to create session. Response: &f" + response);
                        return;
                    }

                    send(sender, "");
                    send(sender, "&a&l  ✓ Session created!");
                    send(sender, "");

                    if (sender instanceof Player player) {
                        // Send clickable link using Adventure API
                        Component clickable = Component.text("  ▸ ")
                                .color(NamedTextColor.GRAY)
                                .append(
                                        Component.text("Click here to open the Web Configurator")
                                                .color(NamedTextColor.AQUA)
                                                .decorate(TextDecoration.UNDERLINED)
                                                .clickEvent(ClickEvent.openUrl(url))
                                                .hoverEvent(HoverEvent.showText(
                                                        Component.text("Open: " + url)
                                                                .color(NamedTextColor.YELLOW)))
                                );
                        player.sendMessage(clickable);
                    } else {
                        send(sender, "  &bURL: &f" + url);
                    }

                    send(sender, "");
                    send(sender, "  &7Your server config has been uploaded.");
                    send(sender, "  &7The link expires in &f7 days&7.");
                    send(sender, "");
                });
            }).exceptionally(ex -> {
                SchedulerAdapter.runGlobal(plugin, () -> {
                    send(sender, plugin.getPrefix() + "&cFailed to generate link: &f" + ex.getMessage());
                });
                return null;
            });
            return;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("analyze")) {
            // Send server info to API for remote analysis
            send(sender, plugin.getPrefix() + "&7Sending server data for analysis...");
            String profile = args.length >= 3 ? args[2] : "SMP";
            String tier = args.length >= 4 ? args[3] : "MID";
            String level = args.length >= 5 ? args[4] : "BALANCED";

            LessLagApiClient client = new LessLagApiClient(apiUrl);
            var payload = LessLagApiClient.buildServerPayload(plugin, profile, tier, level);
            client.evaluate(payload).thenAccept(response -> {
                SchedulerAdapter.runGlobal(plugin, () -> {
                    send(sender, plugin.getPrefix() + "&aAnalysis complete! Results:");
                    // Show a summary (first 500 chars)
                    String preview = response.length() > 500 ? response.substring(0, 500) + "..." : response;
                    send(sender, "&7" + preview);
                    send(sender, plugin.getPrefix() + "&7Full results at: &b" + webUrl);
                });
            }).exceptionally(ex -> {
                SchedulerAdapter.runGlobal(plugin, () -> {
                    send(sender, plugin.getPrefix() + "&cAnalysis failed: &f" + ex.getMessage());
                });
                return null;
            });
            return;
        }

        // Default: show web info
        send(sender, "");
        send(sender, "&b&l  ≡ LessLag Web Optimizer ≡");
        send(sender, "");
        send(sender, "  &7Dashboard: &b" + webUrl);
        send(sender, "  &7API:       &b" + apiUrl);
        send(sender, "");
        send(sender, "  &fCommands:");
        send(sender, "    &b/lg web          &8- &7Show this info");
        send(sender, "    &b/lg web status   &8- &7Check API health");
        send(sender, "    &b/lg web link     &8- &7Generate a shareable config link");
        send(sender, "    &b/lg web analyze  &8- &7Send server data for optimization");
        send(sender, "    &b/lg web analyze <profile> <tier> <level>");
        send(sender, "");
    }

    // ══════════════════════════════════════════════════
    // Restore & Reload
    // ══════════════════════════════════════════════════

    private void doRestore(CommandSender sender) {
        plugin.getLogger().info(sender.getName() + " restored default settings.");
        plugin.getActionExecutor().restoreDefaults();
        send(sender, plugin.getPrefix() + "&aAll server settings restored to defaults.");
    }

    private void doReload(CommandSender sender) {
        plugin.getLogger().info(sender.getName() + " reloaded the plugin configuration.");
        plugin.reloadPlugin();
        send(sender, plugin.getPrefix() + "&aConfiguration reloaded successfully!");
        send(sender,
                plugin.getPrefix() + "&7Loaded &f" + plugin.getTpsMonitor().getThresholds().size() + " &7thresholds.");
    }

    // ══════════════════════════════════════════════════
    // Utility
    // ══════════════════════════════════════════════════

    private void send(CommandSender sender, String message) {
        LessLag.sendMessage(sender, message);
    }

    private String formatTPS(double tps) {
        return getTpsColor(tps) + String.format("%.1f", tps) + " &8(" + formatTPSBar(tps) + "&8)";
    }

    private String formatTPSBar(double tps) {
        StringBuilder bar = new StringBuilder();
        int filled = (int) Math.round(tps);
        for (int i = 0; i < 20; i++) {
            bar.append(i < filled ? "&a|" : "&8|");
        }
        return bar.toString();
    }

    private String formatMSPT(double mspt) {
        String color;
        if (mspt <= 40)
            color = "&a";
        else if (mspt <= 50)
            color = "&e";
        else
            color = "&c";
        return color + String.format("%.1f", mspt) + "ms";
    }

    private String getTpsColor(double tps) {
        if (tps >= 18)
            return "&a";
        if (tps >= 16)
            return "&e";
        if (tps >= 12)
            return "&c";
        return "&4";
    }

    // ══════════════════════════════════════════════════
    // Villager Optimizer
    // ══════════════════════════════════════════════════

    private void showVillagerOptimizer(CommandSender sender) {
        VillagerOptimizer vo = plugin.getVillagerOptimizer();
        boolean enabled = plugin.getConfig().getBoolean("modules.villager-optimizer.enabled", true);

        send(sender, "");
        send(sender, "&c&l  ≡ Villager Optimizer ≡");
        send(sender, "");
        send(sender, "  &7Status: " + (enabled ? "&aEnabled" : "&cDisabled"));
        send(sender, "  &7Optimize Trapped Only: "
                + (plugin.getConfig().getBoolean("modules.villager-optimizer.optimize-trapped", true) ? "&aYes"
                        : "&cNo"));
        send(sender, "  &7Check Interval: &f"
                + plugin.getConfig().getInt("modules.villager-optimizer.check-interval", 600) + " ticks");
        send(sender, "  &7Restore Duration: &f"
                + plugin.getConfig().getInt("modules.villager-optimizer.ai-restore-duration", 30) + "s");

        if (vo != null && enabled) {
            send(sender, "");
            send(sender, "  &7Optimized (AI Disabled): &e" + vo.getOptimizedCount());
            send(sender, "  &7Active (Restored): &a" + vo.getActiveRestoredCount());
            send(sender, "  &8(Villagers in trading halls are lobotomized until interaction)");
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
        send(sender, "&c&l  ≡ Density Optimizer ≡");
        send(sender, "");
        send(sender, "  &7Status: " + (enabled ? "&aEnabled" : "&cDisabled"));
        send(sender, "  &7Check Interval: &f"
                + plugin.getConfig().getInt("modules.density-optimizer.check-interval", 40) + " ticks");
        send(sender, "  &7Bypass: "
                + (plugin.getConfig().getBoolean("modules.density-optimizer.bypass-tamed", true) ? "&aTamed " : "")
                + (plugin.getConfig().getBoolean("modules.density-optimizer.bypass-named", true) ? "&aNamed " : "")
                + (plugin.getConfig().getBoolean("modules.density-optimizer.bypass-leashed", true) ? "&aLeashed" : ""));

        if (dens != null && enabled) {
            // Show limits
            send(sender, "");
            send(sender, "  &e&lEntity Limits");
            for (var entry : dens.getLimits().entrySet()) {
                send(sender, "    &8▸ &f" + entry.getKey().name() + " &8- max &e" + entry.getValue() + " &7/chunk");
            }

            // Stats
            send(sender, "");
            send(sender, "  &e&lRuntime Statistics");
            send(sender, "    &7Total mobs suppressed: &e" + dens.getTotalMobsOptimized());
            send(sender, "    &7Total chunks scanned: &f" + dens.getTotalChunksScanned());
            send(sender, "    &7Last pass: &e" + dens.getLastPassOptimized()
                    + " mobs &7in &f" + dens.getLastPassChunks() + " chunks");
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
        send(sender, "&c&l  ≡ Breeding Limiter ≡");
        send(sender, "");
        send(sender, "  &7Status: " + (enabled ? "&aEnabled" : "&cDisabled"));
        send(sender, "  &7Max animals/chunk: &f"
                + plugin.getConfig().getInt("modules.breeding-limiter.max-animals-per-chunk", 20));

        if (bl != null && enabled) {
            send(sender, "");
            send(sender, "  &7Total breeding blocked: &e" + bl.getTotalBlocked());
            if (!bl.getLastBlockedType().isEmpty()) {
                send(sender, "  &7Last blocked: &f" + bl.getLastBlockedType()
                        + " &7in &f" + bl.getLastBlockedWorld());
            }
        }
        send(sender, "");
    }
}
