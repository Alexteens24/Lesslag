package com.lesslag.web;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lesslag.LessLag;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * HTTP client for the LessLag Web API.
 * Uses Java 11+ HttpClient for non-blocking requests.
 */
public class LessLagApiClient {

    private static final Gson GSON = new Gson();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final String baseUrl;

    public LessLagApiClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
    }

    /**
     * POST /api/evaluate – run the rules engine remotely.
     *
     * @param input Map representing EvaluationInput JSON
     * @return Future resolving to the raw JSON response string
     */
    public CompletableFuture<String> evaluate(Map<String, Object> input) {
        return postJson("/api/evaluate", input);
    }

    /**
     * POST /api/preset – generate an optimized preset.
     *
     * @param profile       game profile (SMP, SKYBLOCK, etc.)
     * @param tier          hardware tier (LOW, MID, HIGH)
     * @param aggressiveness level (SAFE, BALANCED, AGGRESSIVE)
     * @param playerCount   optional current player count
     * @return Future resolving to preset JSON
     */
    public CompletableFuture<String> generatePreset(String profile, String tier,
                                                     String aggressiveness, Integer playerCount) {
        JsonObject body = new JsonObject();
        body.addProperty("profile", profile);
        body.addProperty("tier", tier);
        body.addProperty("aggressiveness", aggressiveness);
        if (playerCount != null) {
            body.addProperty("playerCount", playerCount);
        }
        return postRawJson("/api/preset", body.toString());
    }

    /**
     * GET /api/health – check API status.
     *
     * @return Future resolving to health JSON
     */
    public CompletableFuture<String> health() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/health"))
                .timeout(TIMEOUT)
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body);
    }

    /**
     * Checks whether the API is reachable (non-blocking).
     */
    public CompletableFuture<Boolean> isReachable() {
        return health()
                .thenApply(body -> {
                    try {
                        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                        return "ok".equals(json.get("status").getAsString());
                    } catch (Exception e) {
                        return false;
                    }
                })
                .exceptionally(ex -> false);
    }

    /**
     * Build basic server info payload for /api/evaluate.
     */
    public static Map<String, Object> buildServerPayload(LessLag plugin,
                                                          String profile, String tier,
                                                          String aggressiveness) {
        // Platform
        Map<String, Object> platform = Map.of(
                "fork", detectFork(),
                "version", org.bukkit.Bukkit.getMinecraftVersion(),
                "isPaper", isPaper()
        );

        // Hardware
        Runtime rt = Runtime.getRuntime();
        Map<String, Object> hardware = Map.of(
                "availableProcessors", rt.availableProcessors(),
                "cpuModel", System.getProperty("os.arch", "unknown"),
                "maxHeapMB", rt.maxMemory() / (1024 * 1024),
                "gcOverheadPercent", 0,
                "averageMspt", 50.0
        );

        // Minimal configs from server.properties
        Map<String, Object> serverProps = Map.of(
                "view-distance", String.valueOf(org.bukkit.Bukkit.getViewDistance()),
                "online-mode", String.valueOf(org.bukkit.Bukkit.getOnlineMode()),
                "max-players", String.valueOf(org.bukkit.Bukkit.getMaxPlayers())
        );

        Map<String, Object> configs = Map.of(
                "server.properties", serverProps
        );

        return Map.of(
                "platform", platform,
                "configs", configs,
                "plugins", java.util.List.of(),
                "hardware", hardware,
                "profile", profile.toUpperCase(),
                "tier", tier.toUpperCase(),
                "aggressiveness", aggressiveness.toUpperCase()
        );
    }

    /**
     * POST /api/sessions – create a web session with server config.
     *
     * @param payload full session payload (use {@link #buildSessionPayload})
     * @return Future resolving to JSON with { token, url, expiresAt }
     */
    public CompletableFuture<String> createSession(Map<String, Object> payload) {
        return postJson("/api/sessions", payload);
    }

    /**
     * Parse the session response to extract the dashboard URL.
     */
    public static String extractSessionUrl(String responseJson) {
        try {
            JsonObject json = JsonParser.parseString(responseJson).getAsJsonObject();
            return json.has("url") ? json.get("url").getAsString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Build comprehensive server payload for session creation.
     * Reads actual config files from disk and captures live server info.
     */
    public static Map<String, Object> buildSessionPayload(LessLag plugin) {
        Map<String, Object> payload = new LinkedHashMap<>();

        // ── Platform ──
        Map<String, Object> platform = new LinkedHashMap<>();
        platform.put("fork", detectFork());
        platform.put("version", Bukkit.getMinecraftVersion());
        platform.put("isPaper", isPaper());
        platform.put("isPurpur", classExists("org.purpurmc.purpur.PurpurConfig"));
        platform.put("isPufferfish", classExists("gg.pufferfish.pufferfish.PufferfishConfig"));
        platform.put("isLeaf", false);
        platform.put("hasFolia", classExists("io.papermc.paper.threadedregions.RegionizedServer"));
        payload.put("platform", platform);

        // ── Hardware ──
        Runtime rt = Runtime.getRuntime();
        Map<String, Object> hardware = new LinkedHashMap<>();
        hardware.put("availableProcessors", rt.availableProcessors());
        hardware.put("cpuModel", System.getProperty("os.arch", "unknown"));
        hardware.put("maxHeapMB", rt.maxMemory() / (1024 * 1024));
        hardware.put("gcOverheadPercent", 0);
        double mspt = plugin.getTpsMonitor() != null ? plugin.getTpsMonitor().getCurrentMSPT() : 50.0;
        hardware.put("averageMspt", mspt);
        payload.put("hardware", hardware);

        // ── Detect hardware tier ──
        long heapMB = rt.maxMemory() / (1024 * 1024);
        int cpus = rt.availableProcessors();
        String tier;
        if (heapMB >= 8192 && cpus >= 6) tier = "HIGH";
        else if (heapMB >= 4096 && cpus >= 4) tier = "MID";
        else tier = "LOW";
        payload.put("tier", tier);

        // ── Configs (read actual files) ──
        File serverDir = new File(".");
        Map<String, Object> configs = new LinkedHashMap<>();

        // server.properties
        configs.put("server.properties", readServerProperties(serverDir));

        // YAML configs
        readYamlInto(configs, serverDir, "bukkit.yml");
        readYamlInto(configs, serverDir, "spigot.yml");
        readYamlInto(configs, new File(serverDir, "config"), "paper-world-defaults.yml");
        readYamlInto(configs, new File(serverDir, "config"), "paper-global.yml");
        readYamlInto(configs, serverDir, "purpur.yml");
        readYamlInto(configs, serverDir, "pufferfish.yml");
        payload.put("configs", configs);

        // ── Plugins ──
        List<String> pluginNames = new ArrayList<>();
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            pluginNames.add(p.getName());
        }
        payload.put("plugins", pluginNames);

        // ── Defaults ──
        payload.put("profile", "SMP");
        payload.put("aggressiveness", "BALANCED");
        payload.put("playerCount", Bukkit.getOnlinePlayers().size());
        payload.put("serverName", net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(Bukkit.getServer().motd()));

        return payload;
    }

    // ── Config file readers ───────────────────────────────

    private static Map<String, Object> readServerProperties(File serverDir) {
        Map<String, Object> props = new LinkedHashMap<>();
        File file = new File(serverDir, "server.properties");
        if (!file.exists()) {
            // Fallback to Bukkit API
            props.put("view-distance", Bukkit.getViewDistance());
            props.put("simulation-distance", Bukkit.getSimulationDistance());
            props.put("online-mode", Bukkit.getOnlineMode());
            props.put("max-players", Bukkit.getMaxPlayers());
            return props;
        }
        try {
            Properties p = new Properties();
            p.load(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            for (String key : p.stringPropertyNames()) {
                props.put(key, p.getProperty(key));
            }
        } catch (Exception e) {
            props.put("view-distance", Bukkit.getViewDistance());
            props.put("online-mode", Bukkit.getOnlineMode());
        }
        return props;
    }

    private static void readYamlInto(Map<String, Object> configs, File dir, String filename) {
        File file = new File(dir, filename);
        if (!file.exists()) return;
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            Map<String, Object> flat = new LinkedHashMap<>();
            for (String key : yaml.getKeys(true)) {
                if (!yaml.isConfigurationSection(key)) {
                    Object val = yaml.get(key);
                    flat.put(key, val != null ? val : "");
                }
            }
            // Determine the config key name (e.g., "config/paper-world-defaults.yml")
            String configKey;
            if (dir.getName().equals("config")) {
                configKey = "config/" + filename;
            } else {
                configKey = filename;
            }
            configs.put(configKey, flat);
        } catch (Exception e) {
            // skip unreadable files
        }
    }

    // ── Internal helpers ──────────────────────────────────

    private CompletableFuture<String> postJson(String path, Map<String, Object> body) {
        return postRawJson(path, GSON.toJson(body));
    }

    private CompletableFuture<String> postRawJson(String path, String jsonBody) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() == 429) {
                        throw new RuntimeException("Rate limited by API. Try again later.");
                    }
                    return resp.body();
                });
    }

    private static String detectFork() {
        try {
            Class.forName("io.papermc.paper.configuration.GlobalConfiguration");
            if (classExists("org.purpurmc.purpur.PurpurConfig")) return "purpur";
            if (classExists("gg.pufferfish.pufferfish.PufferfishConfig")) return "pufferfish";
            return "paper";
        } catch (ClassNotFoundException e) {
            try {
                Class.forName("org.spigotmc.SpigotConfig");
                return "spigot";
            } catch (ClassNotFoundException e2) {
                return "vanilla";
            }
        }
    }

    private static boolean isPaper() {
        return classExists("io.papermc.paper.configuration.GlobalConfiguration");
    }

    private static boolean classExists(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
