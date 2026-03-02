package com.lesslag.web;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lesslag.LessLag;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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
