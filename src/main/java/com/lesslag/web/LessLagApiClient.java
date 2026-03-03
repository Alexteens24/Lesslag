package com.lesslag.web;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lesslag.LessLag;
import com.lesslag.monitor.LagSourceAnalyzer;
import com.lesslag.monitor.GCMonitor;
import com.lesslag.monitor.TPSMonitor;
import com.lesslag.setup.detect.ConfigAdapter;
import com.lesslag.setup.rules.RuleEngine;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
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

    /** Server identity — set after registration. May be null before first registration. */
    private volatile String serverId;
    private volatile String serverSecret;

    public LessLagApiClient(String baseUrl) {
        this(baseUrl, null, null);
    }

    public LessLagApiClient(String baseUrl, String serverId, String serverSecret) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.serverId = serverId;
        this.serverSecret = serverSecret;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
    }

    /** Update server credentials (call after first-time registration). */
    public void setCredentials(String serverId, String serverSecret) {
        this.serverId = serverId;
        this.serverSecret = serverSecret;
    }

    public String getServerId() { return serverId; }

    // ── Server identity ──────────────────────────────────────────────────────

    /**
     * POST /api/servers/register — register this server and obtain credentials.
     * The returned JSON contains {@code serverId} and {@code serverSecret}.
     *
     * @param serverName Human-readable server name (e.g. MOTD)
     * @return Future resolving to raw JSON response
     */
    public CompletableFuture<String> registerServer(String serverName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serverName", serverName);  // matched by API body.serverName check
        return postJson("/api/servers/register", body);
    }

    // ── Heartbeat ────────────────────────────────────────────────────────────

    /**
     * POST /api/servers/:id/heartbeat — push a live-metrics snapshot.
     *
     * @param payload heartbeat payload (build with {@link #buildHeartbeatPayload})
     * @return Future resolving to raw JSON
     */
    public CompletableFuture<String> sendHeartbeat(Map<String, Object> payload) {
        if (serverId == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No serverId set"));
        }
        return postAuthJson("/api/servers/" + serverId + "/heartbeat", payload);
    }

    /**
     * Build a heartbeat payload from live monitor data.
     */
    public static Map<String, Object> buildHeartbeatPayload(LessLag plugin) {
        Map<String, Object> p = new LinkedHashMap<>();

        TPSMonitor tps = plugin.getTpsMonitor();
        if (tps != null) {
            p.put("tps",  tps.getCurrentTPS());
            p.put("tps1m", tps.getTPS1m());
            // Nested mspt shape expected by the TypeScript consumer
            Map<String, Object> msptObj = new LinkedHashMap<>();
            msptObj.put("current", tps.getCurrentMSPT());
            msptObj.put("min",     tps.getMinMSPT());
            msptObj.put("max",     tps.getMaxMSPT());
            p.put("mspt", msptObj);
        }

        GCMonitor gc = plugin.getGcMonitor();
        if (gc != null) {
            p.put("gcOverheadPercent", gc.getGCOverheadPercent());
        }

        Runtime rt = Runtime.getRuntime();
        long usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMB  = rt.maxMemory() / (1024 * 1024);
        p.put("heapUsedMB", usedMB);
        p.put("heapMaxMB",  maxMB);

        // "onlinePlayers" matches the TypeScript HeartbeatSnapshot interface
        p.put("onlinePlayers", Bukkit.getOnlinePlayers().size());
        p.put("timestamp",    System.currentTimeMillis());
        return p;
    }

    // ── Apply-queue ──────────────────────────────────────────────────────────

    /**
     * GET /api/servers/:id/apply-queue — poll queued config patches.
     *
     * @return Future resolving to list of patch maps (empty list on error)
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<List<Map<String, Object>>> pollApplyQueue() {
        if (serverId == null) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        return getAuth("/api/servers/" + serverId + "/apply-queue")
                .thenApply(json -> {
                    try {
                        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                        if (!obj.has("patches") || !obj.get("patches").isJsonArray()) {
                            return Collections.<Map<String, Object>>emptyList();
                        }
                        List<Map<String, Object>> result = new ArrayList<>();
                        for (var el : obj.getAsJsonArray("patches")) {
                            result.add((Map<String, Object>) GSON.fromJson(el, Map.class));
                        }
                        return result;
                    } catch (Exception e) {
                        return Collections.<Map<String, Object>>emptyList();
                    }
                })
                .exceptionally(ex -> Collections.emptyList());
    }

    /**
     * DELETE /api/servers/:id/apply-queue/:patchId — confirm or reject a patch.
     *
     * @param patchId     UUID of the patch
     * @param status      "APPLIED" or "REJECTED"
     * @param msptBefore  MSPT before applying (0 = unknown)
     * @param msptAfter   MSPT after applying (0 = unknown)
     * @return Future resolving to raw JSON
     */
    public CompletableFuture<String> confirmPatch(String patchId, String status,
                                                   double msptBefore, double msptAfter) {
        if (serverId == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No serverId set"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status",     status);
        body.put("msptBefore", msptBefore);
        body.put("msptAfter",  msptAfter);
        return deleteAuthJson("/api/servers/" + serverId + "/apply-queue/" + patchId, body);
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
     * Parse the session response to extract session token.
     */
    public static String extractSessionToken(String responseJson) {
        try {
            JsonObject json = JsonParser.parseString(responseJson).getAsJsonObject();
            return json.has("token") ? json.get("token").getAsString() : null;
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

        // ── Configs — delegate to ConfigAdapter for full coverage
        // (server.properties, bukkit.yml, spigot.yml, paper-global.yml,
        //  paper-world-defaults.yml, paper.yml (legacy), purpur.yml,
        //  pufferfish.yml, leaves.yml, and per-world paper-world-<name>.yml) ──
        ConfigAdapter configAdapter = new ConfigAdapter(new File("."));
        configAdapter.scan();
        payload.put("configs", configAdapter.toFlatConfigMap());

        // ── Plugins ──
        List<String> pluginNames = new ArrayList<>();
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            pluginNames.add(p.getName());
        }
        payload.put("plugins", pluginNames);

        // ── Diagnostics — lag sources from last cached analysis ──
        List<Map<String, Object>> diagnostics = new ArrayList<>();
        LagSourceAnalyzer lagAnalyzer = plugin.getLagSourceAnalyzer();
        if (lagAnalyzer != null) {
            for (LagSourceAnalyzer.LagSource src : lagAnalyzer.getCachedAnalysis()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("type", src.type.name());
                // Strip Bukkit color codes (&X format) before sending to the API
                entry.put("description", src.description.replaceAll("&[0-9a-fklmnorA-FKLMNOR]", "").trim());
                entry.put("count", src.count);
                diagnostics.add(entry);
            }
        }
        payload.put("diagnostics", diagnostics);

        // ── Metadata ──
        payload.put("profile", "SMP");
        payload.put("aggressiveness", "BALANCED");
        payload.put("playerCount", Bukkit.getOnlinePlayers().size());
        payload.put("serverName", net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(Bukkit.getServer().motd()));
        payload.put("rulesVersion", RuleEngine.RULES_VERSION);

        return payload;
    }

    /**
     * Checks the API's {@code rulesVersion} against the plugin's {@link RuleEngine#RULES_VERSION}.
     * Logs a warning when the major versions differ, indicating the rule sets may have drifted.
     * Call once on startup after the API client is initialized; failures are silently ignored.
     */
    public CompletableFuture<Void> warnOnRulesVersionDrift(LessLag plugin) {
        return health()
                .thenAccept(body -> {
                    try {
                        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                        if (!json.has("rulesVersion")) return;
                        String apiVersion    = json.get("rulesVersion").getAsString();
                        String pluginVersion = RuleEngine.RULES_VERSION;
                        int apiMajor    = Integer.parseInt(apiVersion.split("\\.")[0]);
                        int pluginMajor = Integer.parseInt(pluginVersion.split("\\.")[0]);
                        if (apiMajor != pluginMajor) {
                            plugin.getLogger().warning(
                                "[LessLag] Rules version mismatch: plugin=" + pluginVersion
                                    + ", api=" + apiVersion
                                    + ". Some web recommendations may be inaccurate."
                                    + " Update the plugin or the API deployment.");
                        }
                    } catch (Exception ignored) { /* non-critical */ }
                })
                .exceptionally(ex -> null);
    }

    // ── Internal helpers ──────────────────────────────────

    private CompletableFuture<String> postJson(String path, Map<String, Object> body) {
        return postRawJson(path, GSON.toJson(body));
    }

    private CompletableFuture<String> postAuthJson(String path, Map<String, Object> body) {
        return postAuthRawJson(path, GSON.toJson(body));
    }

    private CompletableFuture<String> postRawJson(String path, String jsonBody) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return sendAndUnpack(request);
    }

    private CompletableFuture<String> postAuthRawJson(String path, String jsonBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json");
        if (serverId != null)     builder.header("X-Server-Id",     serverId);
        if (serverSecret != null) builder.header("X-Server-Secret", serverSecret);
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();
        return sendAndUnpack(request);
    }

    private CompletableFuture<String> getAuth(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(TIMEOUT)
                .GET();
        if (serverId != null)     builder.header("X-Server-Id",     serverId);
        if (serverSecret != null) builder.header("X-Server-Secret", serverSecret);
        return sendAndUnpack(builder.build());
    }

    private CompletableFuture<String> deleteAuthJson(String path, Map<String, Object> body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json");
        if (serverId != null)     builder.header("X-Server-Id",     serverId);
        if (serverSecret != null) builder.header("X-Server-Secret", serverSecret);
        HttpRequest request = builder.method("DELETE",
                HttpRequest.BodyPublishers.ofString(GSON.toJson(body))).build();
        return sendAndUnpack(request);
    }

    private CompletableFuture<String> sendAndUnpack(HttpRequest request) {
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    int status = resp.statusCode();
                    String respBody = resp.body();
                    if (status >= 200 && status < 300) return respBody;
                    String message = extractErrorMessage(respBody);
                    if (status == 429) throw new RuntimeException("Rate limited by API. " + message);
                    throw new RuntimeException("API request failed (" + status + "): " + message);
                });
    }

    private static String extractErrorMessage(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "No response body";
        }

        try {
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();

            if (json.has("message")) {
                return json.get("message").getAsString();
            }
            if (json.has("error")) {
                return json.get("error").getAsString();
            }
            if (json.has("messages") && json.get("messages").isJsonArray()) {
                StringBuilder combined = new StringBuilder();
                for (var element : json.getAsJsonArray("messages")) {
                    if (combined.length() > 0) {
                        combined.append("; ");
                    }
                    combined.append(element.getAsString());
                }
                if (combined.length() > 0) {
                    return combined.toString();
                }
            }
        } catch (Exception ignored) {
        }

        String compact = responseBody.replace('\n', ' ').replace('\r', ' ').trim();
        return compact.length() > 200 ? compact.substring(0, 200) + "..." : compact;
    }

    static String detectFork() {
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

    /**
     * Extract a top-level string value from a JSON string.
     * Returns {@code null} if the key is absent or parsing fails.
     */
    public static String extractFromJson(String json, String key) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            return obj.has(key) ? obj.get(key).getAsString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
