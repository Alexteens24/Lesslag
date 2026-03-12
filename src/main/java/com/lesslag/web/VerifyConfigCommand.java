package com.lesslag.web;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lesslag.LessLag;
import com.lesslag.setup.detect.ConfigAdapter;
import com.lesslag.util.SchedulerAdapter;

import org.bukkit.command.CommandSender;

import java.io.File;
import java.io.FileReader;
import java.util.Map;

/**
 * Reads the {@code server_config_expectations} block from {@code lesslag-config.json}
 * and verifies it against the actual server config files on disk.
 *
 * <p>Outputs a per-key color-coded report:
 * <ul>
 *   <li>&aGreen ✓&r — value matches expectation</li>
 *   <li>&eYellow ⚠&r — key not found in config (default may apply)</li>
 *   <li>&cRed ✗&r — value is set but does not match expectation</li>
 * </ul>
 */
public class VerifyConfigCommand {

    private static final Gson GSON = new Gson();
    private static final String CONFIG_JSON_NAME = "lesslag-config.json";

    private final LessLag plugin;

    public VerifyConfigCommand(LessLag plugin) {
        this.plugin = plugin;
    }

    public void execute(CommandSender sender) {
        File configJson = new File(plugin.getDataFolder(), CONFIG_JSON_NAME);
        if (!configJson.exists()) {
            LessLag.sendMessage(sender, plugin.getPrefix()
                + "<red>No <white>lesslag-config.json<red> found in <white>plugins/LessLag/<red>.");
            LessLag.sendMessage(sender, plugin.getPrefix()
                + "<gray>Generate one via <aqua>/lg web link<gray> and the web configurator.");
            return;
        }

        LessLag.sendMessage(sender, plugin.getPrefix() + "<gray>Scanning server config files...");

        // Run file I/O async to avoid blocking main thread
        SchedulerAdapter.runAsync(plugin, () -> {
            JsonObject root;
            try (FileReader reader = new FileReader(configJson)) {
                root = GSON.fromJson(reader, JsonObject.class);
            } catch (Exception e) {
                SchedulerAdapter.runGlobal(plugin, () ->
                    LessLag.sendMessage(sender, plugin.getPrefix()
                        + "<red>Failed to read lesslag-config.json: <white>" + e.getMessage())
                );
                return;
            }

            if (!root.has("server_config_expectations")
                    || !root.get("server_config_expectations").isJsonObject()) {
                SchedulerAdapter.runGlobal(plugin, () ->
                    LessLag.sendMessage(sender, plugin.getPrefix()
                        + "<gray>No server_config_expectations in lesslag-config.json. Nothing to verify.")
                );
                return;
            }

            // Scan configs from server root  (working dir = server root)
            ConfigAdapter adapter = new ConfigAdapter(new File("."));
            adapter.scan();

            JsonObject expectations = root.getAsJsonObject("server_config_expectations");

            int passed = 0, warned = 0, failed = 0;
            StringBuilder report = new StringBuilder();

            for (Map.Entry<String, JsonElement> fileEntry : expectations.entrySet()) {
                String fileName = fileEntry.getKey();
                JsonObject keys = fileEntry.getValue().getAsJsonObject();
                boolean filePresent = adapter.isPresent(fileName);

                report.append("\n  <dark_gray><strikethrough>────────────────────────<reset><gray> ")
                      .append(fileName);
                if (!filePresent) {
                    report.append(" <gray>(<red>file not found<gray>)\n");
                }

                for (Map.Entry<String, JsonElement> keyEntry : keys.entrySet()) {
                    String key = keyEntry.getKey();
                    String expected = jsonElementToString(keyEntry.getValue());

                    if (!filePresent) {
                        report.append("\n    <yellow>⚠ <gray>").append(key)
                              .append(" <dark_gray>→ <yellow>expected: <white>").append(expected).append(" <dark_gray>(file missing)");
                        warned++;
                        continue;
                    }

                    Object actual = adapter.getValue(fileName, key);

                    if (actual == null) {
                        report.append("\n    <yellow>⚠ <gray>").append(key)
                              .append(" <dark_gray>→ <yellow>not set <dark_gray>(default applies) | expected: <white>").append(expected);
                        warned++;
                    } else {
                        String actualStr = actual.toString();
                        if (valuesMatch(actualStr, expected)) {
                            report.append("\n    <green>✓ <gray>").append(key)
                                  .append(" <dark_gray>= <green>").append(actualStr);
                            passed++;
                        } else {
                            report.append("\n    <red>✗ <gray>").append(key)
                                  .append(" <dark_gray>= <red>").append(actualStr)
                                  .append(" <dark_gray>| expected: <white>").append(expected);
                            failed++;
                        }
                    }
                }
                report.append("\n");
            }

            // Summary line
            String summary = "<gray>Verify: <green>" + passed + " passed<gray>, <yellow>" + warned
                + " warnings<gray>, <red>" + failed + " failed";

            final String reportStr = report.toString();
            final String summaryStr = summary;
            final int failCount = failed;

            SchedulerAdapter.runGlobal(plugin, () -> {
                LessLag.sendMessage(sender, "");
                LessLag.sendMessage(sender, "<red><bold>  ≡ Server Config Verification ≡");
                // Print each line separately (Bukkit sendMessage handles one line at a time)
                for (String line : reportStr.split("\n")) {
                    if (!line.isEmpty()) LessLag.sendMessage(sender, line);
                }
                LessLag.sendMessage(sender, "");
                LessLag.sendMessage(sender, "  " + summaryStr);
                if (failCount > 0) {
                    LessLag.sendMessage(sender, "  <gray>Edit the listed files manually, then restart the server.");
                }
                LessLag.sendMessage(sender, "");
            });
        });
    }

    /** Normalize and compare two string-valued config entries. */
    private static boolean valuesMatch(String actual, String expected) {
        return actual.trim().equalsIgnoreCase(expected.trim());
    }

    private static String jsonElementToString(JsonElement el) {
        if (el.isJsonNull()) return "null";
        if (el.isJsonPrimitive()) return el.getAsString();
        return el.toString();
    }
}
