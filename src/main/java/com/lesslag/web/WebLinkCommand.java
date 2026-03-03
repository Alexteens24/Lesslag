package com.lesslag.web;

import com.google.gson.Gson;
import com.lesslag.LessLag;
import com.lesslag.setup.detect.HardwareDetector;
import com.lesslag.setup.model.HardwareAssessment;
import com.lesslag.util.SchedulerAdapter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;

/**
 * Generates a pre-filled web configurator URL by encoding server hardware and
 * platform info directly into the URL as a gzip+base64url query param {@code ?s=}.
 * No API call required — the payload is decoded client-side in the browser.
 */
public class WebLinkCommand {

    private static final Gson GSON = new Gson();

    private final LessLag plugin;

    public WebLinkCommand(LessLag plugin) {
        this.plugin = plugin;
    }

    /**
     * Run on the main thread (Bukkit) then dispatch gzip/encode off-thread.
     */
    public void execute(CommandSender sender) {
        String webUrl = plugin.getConfig().getString("web.dashboard-url",
                "https://lesslag-web.vercel.app");

        LessLag.sendMessage(sender, plugin.getPrefix() + "&7Capturing server data...");

        // Collect on main thread (needs Bukkit API)
        final Map<String, Object> payload = buildPayload();

        // Encoding is CPU-bound — do it async
        SchedulerAdapter.runAsync(plugin, () -> {
            String encoded;
            try {
                encoded = encodePayload(payload);
            } catch (Exception e) {
                SchedulerAdapter.runGlobal(plugin, () ->
                    LessLag.sendMessage(sender, plugin.getPrefix() + "&cFailed to encode payload: &f" + e.getMessage())
                );
                return;
            }

            final String url = webUrl + "?s=" + encoded;

            SchedulerAdapter.runGlobal(plugin, () -> {
                LessLag.sendMessage(sender, "");
                LessLag.sendMessage(sender, "&a&l  ✓ Link ready!");
                LessLag.sendMessage(sender, "");

                if (sender instanceof Player player) {
                    Component clickable = Component.text("  ▸ ")
                            .color(NamedTextColor.GRAY)
                            .append(
                                Component.text("Click to open the Web Configurator")
                                        .color(NamedTextColor.AQUA)
                                        .decorate(TextDecoration.UNDERLINED)
                                        .clickEvent(ClickEvent.openUrl(url))
                                        .hoverEvent(HoverEvent.showText(
                                            Component.text("Hardware data pre-filled — Step 1 is skipped!")
                                                     .color(NamedTextColor.YELLOW)))
                            );
                    player.sendMessage(clickable);
                } else {
                    LessLag.sendMessage(sender, "  &bURL: &f" + url);
                }

                LessLag.sendMessage(sender, "");
                LessLag.sendMessage(sender, "  &7Your hardware info is encoded in the link.");
                LessLag.sendMessage(sender, "  &7The web page will skip the hardware step automatically.");
                LessLag.sendMessage(sender, "");
            });
        });
    }

    // ─── Payload construction ────────────────────────────────────────────────

    private Map<String, Object> buildPayload() {
        Map<String, Object> p = new LinkedHashMap<>();

        // Hardware (run via HardwareDetector for accurate CPU model detection)
        HardwareDetector detector = new HardwareDetector();
        HardwareAssessment hw = detector.assess();

        p.put("cpuModel", hw.getCpuModel());
        p.put("cores", hw.getAvailableProcessors());

        Runtime rt = Runtime.getRuntime();
        long maxHeapMb = rt.maxMemory() / (1024L * 1024L);
        p.put("maxHeapMb", maxHeapMb);
        p.put("physicalRamMb", detectPhysicalRamMb(maxHeapMb));

        // Java version (feature-release number: 17, 21, etc.)
        p.put("javaVersion", Runtime.version().feature());

        // JVM flags (raw)
        List<String> flags = ManagementFactory.getRuntimeMXBean().getInputArguments();
        p.put("jvmFlags", new ArrayList<>(flags));

        // Platform
        p.put("fork", LessLagApiClient.detectFork());
        p.put("mcVersion", Bukkit.getMinecraftVersion());

        // Plugins
        List<String> pluginNames = new ArrayList<>();
        for (Plugin pl : Bukkit.getPluginManager().getPlugins()) {
            pluginNames.add(pl.getName());
        }
        p.put("pluginNames", pluginNames);

        // Live performance
        double tps = 20.0;
        double mspt = 50.0;
        if (plugin.getTpsMonitor() != null) {
            tps = plugin.getTpsMonitor().getTPS1m();
            mspt = plugin.getTpsMonitor().getCurrentMSPT();
        }
        p.put("tps", tps);
        p.put("mspt", mspt);

        return p;
    }

    private static long detectPhysicalRamMb(long fallbackMb) {
        try {
            java.lang.management.OperatingSystemMXBean osMxBean =
                ManagementFactory.getOperatingSystemMXBean();
            if (osMxBean instanceof com.sun.management.OperatingSystemMXBean sunOs) {
                long physBytes = sunOs.getTotalMemorySize();
                if (physBytes > 0) return physBytes / (1024L * 1024L);
            }
        } catch (Exception ignored) {}
        return fallbackMb; // best-effort fallback
    }

    // ─── gzip + base64url encoding ───────────────────────────────────────────

    static String encodePayload(Map<String, Object> payload) throws Exception {
        byte[] jsonBytes = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            gzip.write(jsonBytes);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(baos.toByteArray());
    }
}
