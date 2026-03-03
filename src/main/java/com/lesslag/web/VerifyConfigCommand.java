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
                + "&cNo &flesslag-config.json&c found in &fplugins/LessLag/&c.");
            LessLag.sendMessage(sender, plugin.getPrefix()
                + "&7Generate one via &b/lg web link&7 and the web configurator.");
            return;
        }

        LessLag.sendMessage(sender, plugin.getPrefix() + "&7Scanning server config files...");

        // Run file I/O async to avoid blocking main thread
        SchedulerAdapter.runAsync(plugin, () -> {
            JsonObject root;
            try (FileReader reader = new FileReader(configJson)) {
                root = GSON.fromJson(reader, JsonObject.class);
            } catch (Exception e) {
                SchedulerAdapter.runGlobal(plugin, () ->
                    LessLag.sendMessage(sender, plugin.getPrefix()
                        + "&cFailed to read lesslag-config.json: &f" + e.getMessage())
                );
                return;
            }

            if (!root.has("server_config_expectations")
                    || !root.get("server_config_expectations").isJsonObject()) {
                SchedulerAdapter.runGlobal(plugin, () ->
                    LessLag.sendMessage(sender, plugin.getPrefix()
                        + "&7No server_config_expectations in lesslag-config.json. Nothing to verify.")
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

                report.append("\n  &8&m────────────────────────&r&7 ")
                      .append(fileName);
                if (!filePresent) {
                    report.append(" &7(&cfile not found&7)\n");
                }

                for (Map.Entry<String, JsonElement> keyEntry : keys.entrySet()) {
                    String key = keyEntry.getKey();
                    String expected = jsonElementToString(keyEntry.getValue());

                    if (!filePresent) {
                        report.append("\n    &e⚠ &7").append(key)
                              .append(" &8→ &eexpected: &f").append(expected).append(" &8(file missing)");
                        warned++;
                        continue;
                    }

                    Object actual = adapter.getValue(fileName, key);

                    if (actual == null) {
                        report.append("\n    &e⚠ &7").append(key)
                              .append(" &8→ &enot set &8(default applies) | expected: &f").append(expected);
                        warned++;
                    } else {
                        String actualStr = actual.toString();
                        if (valuesMatch(actualStr, expected)) {
                            report.append("\n    &a✓ &7").append(key)
                                  .append(" &8= &a").append(actualStr);
                            passed++;
                        } else {
                            report.append("\n    &c✗ &7").append(key)
                                  .append(" &8= &c").append(actualStr)
                                  .append(" &8| expected: &f").append(expected);
                            failed++;
                        }
                    }
                }
                report.append("\n");
            }

            // Summary line
            String summary = "&7Verify: &a" + passed + " passed&7, &e" + warned
                + " warnings&7, &c" + failed + " failed";

            final String reportStr = report.toString();
            final String summaryStr = summary;
            final int failCount = failed;

            SchedulerAdapter.runGlobal(plugin, () -> {
                LessLag.sendMessage(sender, "");
                LessLag.sendMessage(sender, "&c&l  ≡ Server Config Verification ≡");
                // Print each line separately (Bukkit sendMessage handles one line at a time)
                for (String line : reportStr.split("\n")) {
                    if (!line.isEmpty()) LessLag.sendMessage(sender, line);
                }
                LessLag.sendMessage(sender, "");
                LessLag.sendMessage(sender, "  " + summaryStr);
                if (failCount > 0) {
                    LessLag.sendMessage(sender, "  &7Edit the listed files manually, then restart the server.");
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
