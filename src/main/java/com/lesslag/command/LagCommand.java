package com.lesslag.command;

import com.lesslag.LessLag;
import com.lesslag.action.ActionExecutor;
import com.lesslag.action.ThresholdConfig;
import com.lesslag.monitor.ChunkLimiter;
import com.lesslag.monitor.FrustumCuller;
import com.lesslag.monitor.WorldChunkGuard;
import com.lesslag.monitor.MemoryLeakDetector;
import com.lesslag.monitor.GCMonitor;
import com.lesslag.monitor.PredictiveOptimizer;
import com.lesslag.monitor.RedstoneMonitor;
import com.lesslag.monitor.TPSMonitor;
import com.lesslag.monitor.TickMonitor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
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

    public LagCommand(LessLag plugin) {
        this.plugin = plugin;
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
            case "restore":
                doRestore(sender);
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
        send(sender, "  &e/lg chunks      &8- &7Smart Chunk Limiter status");
        send(sender, "  &e/lg redstone    &8- &7Redstone Suppressor status");
        send(sender, "  &e/lg predictive  &8- &7Predictive Optimizer status");
        send(sender, "  &e/lg frustum     &8- &7Frustum Culler status");
        send(sender, "  &e/lg worldguard  &8- &7World Chunk Guard status");
        send(sender, "  &e/lg memory      &8- &7Memory Leak Detector status");
        send(sender, "  &e/lg clear       &8- &7Clear entities &8[items|mobs|hostile|all]");
        send(sender, "  &e/lg ai          &8- &7AI control &8[disable|restore|status]");
        send(sender, "  &e/lg restore     &8- &7Restore all defaults");
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

        send(sender, "");
        send(sender, "&c&l  ≡ LessLag Status ≡");
        send(sender, "");
        send(sender, "  &7TPS: " + tpsColor + String.format("%.1f", tps.getCurrentTPS()) + " &8/ &a20.0");
        send(sender, "  " + tpsBar);
        send(sender, "  &7MSPT: &f" + String.format("%.1f", tps.getCurrentMSPT()) + "ms &8("
                + "&7min: " + String.format("%.1f", tps.getMinMSPT())
                + " &8/ &7max: " + String.format("%.1f", tps.getMaxMSPT()) + "&8)");
        send(sender, "");
        send(sender, "  &7Status: " + statusColor + statusText);
        send(sender, "  &7Modified: " + (tps.isSettingsModified() ? "&eYes &8(settings changed)" : "&aNo"));
        send(sender, "  &7RAM: &f" + plugin.getActionExecutor().getMemoryInfo());
        send(sender, "  &7Players: &f" + Bukkit.getOnlinePlayers().size() + " &8/ &f" + Bukkit.getMaxPlayers());
        send(sender, "  &7Entities: &f" + plugin.getActionExecutor().getTotalEntityCount());
        send(sender, "  &7Thresholds: &f" + allThresholds.size() + " loaded");
        send(sender, "");
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
        send(sender, plugin.getPrefix() + "&7Running garbage collection...");
        long freed = plugin.getActionExecutor().forceGC();
        String message = formatMessage("messages.gc-complete",
                "&7Freed &e{freed}MB &7of memory.", "freed", String.valueOf(freed));
        send(sender, plugin.getPrefix() + message);
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

        send(sender, "");
        send(sender, "&c&l  ≡ Tick Monitor ≡");
        send(sender, "");
        send(sender, "  &7Last tick: &f" + String.format("%.1f", tick.getLastTickMs()) + "ms");
        send(sender, "  &7Worst tick: &f" + String.format("%.1f", tick.getWorstTickMs()) + "ms");
        send(sender, "  &7Spike count: &e" + tick.getSpikeCount() +
                " &8(threshold: " + plugin.getConfig().getDouble("tick-monitor.threshold-ms", 100) + "ms)");
        send(sender, "");
    }

    // ══════════════════════════════════════════════════
    // Entity Breakdown
    // ══════════════════════════════════════════════════

    private void showEntityBreakdown(CommandSender sender) {
        Map<String, Integer> breakdown = plugin.getActionExecutor().getEntityBreakdown();

        send(sender, "");
        send(sender, "&c&l  ≡ Entity Breakdown ≡");
        send(sender, "  &7Total: &f" + plugin.getActionExecutor().getTotalEntityCount());
        send(sender, "");

        breakdown.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(15)
                .forEach(entry -> {
                    String color = entry.getValue() > 100 ? "&c" : entry.getValue() > 50 ? "&e" : "&a";
                    // Percentage
                    int total = plugin.getActionExecutor().getTotalEntityCount();
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

        switch (type) {
            case "items": {
                int count = plugin.getActionExecutor().clearGroundItems();
                String message = formatMessage("messages.items-cleared",
                        "&7Cleared &e{count} &7ground items.", "count", String.valueOf(count));
                send(sender, plugin.getPrefix() + message);
                break;
            }
            case "xp": {
                int count = plugin.getActionExecutor().clearXPOrbs();
                String message = formatMessage("messages.xp-cleared",
                        "&7Cleared &e{count} &7XP orbs.", "count", String.valueOf(count));
                send(sender, plugin.getPrefix() + message);
                break;
            }
            case "mobs": {
                int count = plugin.getActionExecutor().clearExcessMobs();
                String message = formatMessage("messages.mobs-cleared",
                        "&7Removed &e{count} &7excess mobs.", "count", String.valueOf(count));
                send(sender, plugin.getPrefix() + message);
                break;
            }
            case "hostile": {
                int count = plugin.getActionExecutor().killHostileMobs();
                send(sender, plugin.getPrefix() + "&aKilled &e" + count + " &ahostile mobs.");
                break;
            }
            case "all":
            default: {
                int items = plugin.getActionExecutor().clearGroundItems();
                int xp = plugin.getActionExecutor().clearXPOrbs();
                int mobs = plugin.getActionExecutor().clearExcessMobs();
                String itemsMsg = formatMessage("messages.items-cleared",
                        "&7Cleared &e{count} &7ground items.", "count", String.valueOf(items));
                String xpMsg = formatMessage("messages.xp-cleared",
                        "&7Cleared &e{count} &7XP orbs.", "count", String.valueOf(xp));
                String mobsMsg = formatMessage("messages.mobs-cleared",
                        "&7Removed &e{count} &7excess mobs.", "count", String.valueOf(mobs));
                send(sender, plugin.getPrefix() + itemsMsg);
                send(sender, plugin.getPrefix() + xpMsg);
                send(sender, plugin.getPrefix() + mobsMsg);
                break;
            }
        }
    }

    // ══════════════════════════════════════════════════
    // AI Control
    // ══════════════════════════════════════════════════

    private void doAI(CommandSender sender, String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase() : "status";

        switch (action) {
            case "disable": {
                int count = plugin.getActionExecutor().disableMobAI();
                String message = formatMessage("messages.ai-disabled",
                        "&7Disabled AI for &e{count} &7entities.", "count", String.valueOf(count));
                send(sender, plugin.getPrefix() + message);
                break;
            }
            case "restore": {
                int count = plugin.getActionExecutor().restoreMobAI();
                send(sender, plugin.getPrefix() + "&aRestored AI for &e" + count + " &amobs.");
                break;
            }
            case "status":
            default: {
                int noAI = 0, total = 0;

                for (World world : Bukkit.getWorlds()) {
                    for (org.bukkit.entity.Entity entity : world.getEntities()) {
                        if (entity instanceof org.bukkit.entity.Mob) {
                            total++;
                            if (!plugin.isMobAwareSafe((org.bukkit.entity.Mob) entity))
                                noAI++;
                        }
                    }
                }
                send(sender, "");
                send(sender, "&c&l  ≡ AI Status ≡");
                send(sender, "  &7Total mobs: &f" + total);
                send(sender, "  &7AI disabled: &e" + noAI);
                send(sender, "  &7AI active: &a" + (total - noAI));
                send(sender, "  &7Active radius: &f" + plugin.getConfig().getInt("ai-optimization.active-radius", 48)
                        + " blocks");
                send(sender, "  &7Protected types: &f"
                        + plugin.getConfig().getStringList("ai-optimization.protected").size());
                send(sender, "");
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
            Bukkit.getScheduler().runTask(plugin, () -> {
                send(sender, "");
                send(sender, "&c&l  ≡ Lag Source Analysis ≡");
                send(sender, "");

                // Use the full detailed report format
                java.util.List<String> report = plugin.getLagSourceAnalyzer()
                        .formatFullReport(result.sources, result.worldSnapshots, result.taskSnapshots);
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
            Bukkit.getScheduler().runTask(plugin, () -> {
                send(sender, plugin.getPrefix() + "&cFailed to analyze lag sources: " + e.getMessage());
            });
            return null;
        });
    }

    // ══════════════════════════════════════════════════
    // Smart Chunk Limiter
    // ══════════════════════════════════════════════════

    private void showChunkLimiter(CommandSender sender) {
        ChunkLimiter cl = plugin.getChunkLimiter();
        boolean enabled = plugin.getConfig().getBoolean("chunk-limiter.enabled", true);

        send(sender, "");
        send(sender, "&c&l  ≡ Smart Chunk Limiter ≡");
        send(sender, "");
        send(sender, "  &7Status: " + (enabled ? "&aEnabled" : "&cDisabled"));
        send(sender,
                "  &7Max entities/chunk: &f" + plugin.getConfig().getInt("chunk-limiter.max-entities-per-chunk", 50));
        send(sender, "  &7Scan interval: &f" + plugin.getConfig().getInt("chunk-limiter.scan-interval", 30) + "s");

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
        boolean enabled = plugin.getConfig().getBoolean("redstone-suppressor.enabled", true);

        send(sender, "");
        send(sender, "&c&l  ≡ Redstone Suppressor ≡");
        send(sender, "");
        send(sender, "  &7Status: " + (enabled ? "&aEnabled" : "&cDisabled"));
        send(sender, "  &7Max activations/chunk: &f"
                + plugin.getConfig().getInt("redstone-suppressor.max-activations-per-chunk", 200));
        send(sender, "  &7Window: &f" + plugin.getConfig().getInt("redstone-suppressor.window-seconds", 2) + "s");
        send(sender, "  &7Cooldown: &f" + plugin.getConfig().getInt("redstone-suppressor.cooldown-seconds", 10) + "s");

        if (rm != null) {
            send(sender, "");
            send(sender, "  &7Total suppressions: &e" + rm.getTotalSuppressed());
            int active = rm.getActiveSuppressedChunks();
            send(sender, "  &7Currently suppressed: " + (active > 0 ? "&c" : "&a") + active + " chunk(s)");

            if (!rm.getSuppressedChunks().isEmpty()) {
                send(sender, "");
                send(sender, "  &e&lActive Suppressions:");
                long now = System.currentTimeMillis();
                int shown = 0;
                for (Map.Entry<String, Long> entry : rm.getSuppressedChunks().entrySet()) {
                    if (shown >= 5)
                        break;
                    String key = entry.getKey();
                    long remainMs = entry.getValue() - now;
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
        boolean enabled = plugin.getConfig().getBoolean("predictive-optimization.enabled", true);

        send(sender, "");
        send(sender, "&c&l  ≡ Predictive Optimizer ≡");
        send(sender, "");
        send(sender, "  &7Status: " + (enabled ? "&aEnabled" : "&cDisabled"));
        send(sender, "  &7Slope threshold: &f"
                + plugin.getConfig().getDouble("predictive-optimization.slope-threshold", 3.0) + " ms/s");
        send(sender, "  &7MSPT baseline: &f"
                + plugin.getConfig().getDouble("predictive-optimization.mspt-baseline", 30.0) + "ms");
        send(sender, "  &7Window: &f" + plugin.getConfig().getInt("predictive-optimization.window-seconds", 10) + "s");
        send(sender,
                "  &7Cooldown: &f" + plugin.getConfig().getInt("predictive-optimization.cooldown-seconds", 60) + "s");

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
        boolean enabled = plugin.getConfig().getBoolean("ai-optimization.frustum-culling.enabled", true);

        send(sender, "");
        send(sender, "&c&l  ≡ Frustum Culler ≡");
        send(sender, "");
        send(sender, "  &7Status: " + (enabled ? "&aEnabled" : "&cDisabled"));
        send(sender,
                "  &7FOV: &f" + plugin.getConfig().getDouble("ai-optimization.frustum-culling.fov-degrees", 110) + "°");
        send(sender,
                "  &7Max radius: &f" + plugin.getConfig().getDouble("ai-optimization.active-radius", 48) + " blocks");
        send(sender, "  &7Behind safe radius: &f"
                + plugin.getConfig().getDouble("ai-optimization.frustum-culling.behind-safe-radius", 12) + " blocks");
        send(sender, "  &7Interval: &f"
                + plugin.getConfig().getInt("ai-optimization.frustum-culling.interval-ticks", 40) + " ticks");

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
        boolean enabled = plugin.getConfig().getBoolean("world-chunk-guard.enabled", true);

        send(sender, "");
        send(sender, "&c&l  ≡ World Chunk Guard ≡");
        send(sender, "");
        send(sender, "  &7Status: " + (enabled ? "&aEnabled" : "&cDisabled"));
        send(sender, "  &7Overload multiplier: &f"
                + plugin.getConfig().getDouble("world-chunk-guard.overload-multiplier", 2.0) + "x");
        send(sender, "  &7Check interval: &f"
                + plugin.getConfig().getInt("world-chunk-guard.check-interval", 10) + "s");
        send(sender, "  &7Max retries before evacuate: &f"
                + plugin.getConfig().getInt("world-chunk-guard.max-retry-before-evacuate", 3));
        send(sender, "  &7Evacuate world: &f"
                + plugin.getConfig().getString("world-chunk-guard.evacuate-world", "world"));

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
        boolean enabled = plugin.getConfig().getBoolean("memory-leak-detector.enabled", true);

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
                + plugin.getConfig().getInt("memory-leak-detector.check-interval", 30) + "s");
        send(sender, "    &7Slope threshold: &f"
                + plugin.getConfig().getDouble("memory-leak-detector.slope-threshold-mb-per-min", 5.0)
                + " MB/min");
        send(sender, "    &7Window size: &f"
                + plugin.getConfig().getInt("memory-leak-detector.window-size", 20) + " samples");
        send(sender, "    &7Notify: &f"
                + plugin.getConfig().getBoolean("memory-leak-detector.notify", true));
        send(sender, "");
    }

    // ══════════════════════════════════════════════════
    // Restore & Reload
    // ══════════════════════════════════════════════════

    private void doRestore(CommandSender sender) {
        plugin.getActionExecutor().restoreDefaults();
        send(sender, plugin.getPrefix() + "&aAll server settings restored to defaults.");
    }

    private void doReload(CommandSender sender) {
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

    private String formatMessage(String path, String fallback, String key, String value) {
        String message = plugin.getConfig().getString(path, fallback);
        return message.replace("{" + key + "}", value);
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
}
