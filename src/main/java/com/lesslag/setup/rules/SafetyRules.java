package com.lesslag.setup.rules;

import com.lesslag.setup.detect.ConfigAdapter;
import com.lesslag.setup.detect.PlatformDetector;
import com.lesslag.setup.detect.PluginScanner;
import com.lesslag.setup.model.*;

import java.util.List;

/**
 * Safety rules prevent dangerous defaults and common footguns.
 * Fires with CRITICAL or WARNING severity.
 * Values sourced from Paper Chan's optimisation guide:
 *   https://paper-chan.moe/paper-optimization/
 */
public class SafetyRules implements Rule {

    @Override public String getId() { return "safety"; }
    @Override public String getGroup() { return "safety"; }
    @Override public int getPriority() { return 10; } // highest priority

    @Override
    public void evaluate(PlatformDetector platform, ConfigAdapter configs, PluginScanner plugins,
                          HardwareAssessment hardware, GameProfile profile, HardwareTier tier,
                          AggressivenessLevel level, List<RuleResult> results, List<PatchProposal> proposals) {

        checkOnlineMode(configs, results);
        checkWorldGuardSafety(configs, results, proposals);
        checkHeapSize(hardware, results);
        checkGcOverhead(hardware, results);
        checkAllowFlight(configs, results, proposals);
        checkPauseWhenEmpty(configs, results, proposals);
        checkProcessorCount(hardware, results);
        checkRedstoneDefaults(configs, tier, level, results, proposals);
        checkBreedingLimits(configs, profile, tier, results, proposals);
    }

    private void checkOnlineMode(ConfigAdapter configs, List<RuleResult> results) {
        String onlineMode = configs.getString("server.properties", "online-mode", "true");
        if ("false".equalsIgnoreCase(onlineMode)) {
            results.add(RuleResult.builder("safety-online-mode")
                .group("safety").severity(Severity.WARNING).confidence(1.0)
                .why("Server is running in offline mode (online-mode=false)")
                .impact("Players can join without Mojang authentication — security risk")
                .tradeoff("Required for BungeeCord/Velocity proxied setups; otherwise a vulnerability")
                .recommendation("Ensure this is intentional. Use a proxy with ip-forwarding if behind Bungee/Velocity.")
                .manualSteps("If using a proxy, verify ip-forwarding is correctly configured in the proxy config.")
                .impactedKey("server.properties:online-mode")
                .build());
        }
    }

    private void checkWorldGuardSafety(ConfigAdapter configs, List<RuleResult> results,
                                        List<PatchProposal> proposals) {
        // If world-guard is enabled in LessLag with unload-unused, warn about data loss risk
        // This is about LessLag's own config
        results.add(RuleResult.builder("safety-world-guard-defaults")
            .group("safety").severity(Severity.INFO).confidence(0.9)
            .why("LessLag World Chunk Guard has safe defaults (disabled by default)")
            .impact("When enabled, aggressive chunk unloading can cause brief visual artifacts")
            .tradeoff("Keep disabled unless experiencing chunk overload issues")
            .recommendation("Leave world-guard disabled unless specifically needed for chunk overload")
            .impactedKey("modules.chunks.world-guard.enabled")
            .build());
    }

    private void checkHeapSize(HardwareAssessment hardware, List<RuleResult> results) {
        long maxMb = hardware.getMaxHeapBytes() / (1024 * 1024);
        if (maxMb < 2048) {
            results.add(RuleResult.builder("safety-low-heap")
                .group("safety").severity(Severity.CRITICAL).confidence(0.95)
                .why("Server heap is only " + hardware.getMaxHeapFormatted() + " — critically low")
                .impact("Frequent GC pauses, out-of-memory crashes, poor TPS under any load")
                .tradeoff("Increasing heap requires more physical RAM on the host")
                .recommendation("Allocate at least 4GB heap (-Xmx4G). " +
                        "Paper Chan: 10GB is sufficient for most servers. Set -Xms equal to -Xmx")
                .manualSteps("Edit your startup script: change -Xmx to at least 4G and set -Xms equal to -Xmx")
                .build());
        } else if (maxMb < 4096) {
            results.add(RuleResult.builder("safety-moderate-heap")
                .group("safety").severity(Severity.WARNING).confidence(0.8)
                .why("Server heap is " + hardware.getMaxHeapFormatted() + " — sufficient for small servers only")
                .impact("May experience GC pressure with 20+ players or large worlds")
                .tradeoff("More heap = better headroom but requires available host RAM. " +
                        "Paper Chan: 10GB is sufficient for most servers")
                .recommendation("Consider 6-10GB for 20+ concurrent players. Set -Xms equal to -Xmx")
                .manualSteps("Edit your startup script: change -Xmx to 6G-10G and set -Xms to the same value")
                .build());
        }
    }

    private void checkGcOverhead(HardwareAssessment hardware, List<RuleResult> results) {
        if (hardware.getGcOverheadPercent() > 15) {
            results.add(RuleResult.builder("safety-gc-overhead")
                .group("safety").severity(Severity.WARNING).confidence(0.85)
                .why("GC overhead is " + String.format("%.1f%%", hardware.getGcOverheadPercent()) + " — high")
                .impact("Server spending significant time on garbage collection instead of ticking")
                .tradeoff("Switching GC algorithm may require JDK 17+ features")
                .recommendation("Paper Chan: use Aikar's flags for G1GC, or ZGC (-XX:+UseZGC) " +
                        "for Java 21+ (no extra tuning needed). Set -Xms equal to -Xmx")
                .manualSteps("For G1GC: use Aikar's flags (https://docs.papermc.io/paper/aikars-flags)\n" +
                        "For ZGC (Java 21+): add -XX:+UseZGC -XX:+ZGenerational to start script\n" +
                        "Always set -Xms equal to -Xmx")
                .build());
        }
    }

    /**
     * Paper Chan: allow-flight=true prevents false positive kicks.
     */
    private void checkAllowFlight(ConfigAdapter configs,
                                   List<RuleResult> results, List<PatchProposal> proposals) {
        String allowFlight = configs.getString("server.properties", "allow-flight", "false");
        if ("false".equalsIgnoreCase(allowFlight)) {
            results.add(RuleResult.builder("safety-allow-flight")
                .group("safety").severity(Severity.WARNING).confidence(0.9)
                .why("allow-flight is false — Vanilla flight detection is unreliable")
                .impact("Players get kicked for 'flying' during normal gameplay (lag, " +
                        "elytra, jumping on boats/slimes). Paper Chan recommends always true")
                .tradeoff("Use a proper anti-cheat plugin instead of Vanilla flight detection")
                .recommendation("Set allow-flight=true in server.properties")
                .manualSteps("In server.properties, set allow-flight=true")
                .impactedKey("server.properties:allow-flight")
                .build());

            proposals.add(new PatchProposal("server.properties", "allow-flight",
                "false", "true",
                RiskTag.LOW, ApplyScope.RECOMMEND, "safety-allow-flight",
                "Enable allow-flight to prevent false kicks (Paper Chan recommended)"));
        }
    }

    /**
     * Paper Chan: pause-when-empty-seconds=-1 (disables the feature).
     */
    private void checkPauseWhenEmpty(ConfigAdapter configs,
                                      List<RuleResult> results, List<PatchProposal> proposals) {
        String pause = configs.getString("server.properties",
            "pause-when-empty-seconds", "60");

        if (!"−1".equals(pause) && !"-1".equals(pause)) {
            int val;
            try { val = Integer.parseInt(pause.trim()); }
            catch (NumberFormatException e) { val = 60; }

            if (val >= 0) {
                results.add(RuleResult.builder("safety-pause-when-empty")
                    .group("safety").severity(Severity.INFO).confidence(0.8)
                    .why("pause-when-empty-seconds is " + val + " — server pauses when empty")
                    .impact("Can cause issues with scheduled tasks, cron-based backups, and " +
                            "plugins that expect the server to always be running")
                    .tradeoff("Saves resources when no players are online, but breaks some functionality")
                    .recommendation("Set pause-when-empty-seconds=-1 to disable")
                    .manualSteps("In server.properties, set pause-when-empty-seconds=-1")
                    .impactedKey("server.properties:pause-when-empty-seconds")
                    .build());

                proposals.add(new PatchProposal("server.properties", "pause-when-empty-seconds",
                    pause, "-1",
                    RiskTag.LOW, ApplyScope.RECOMMEND, "safety-pause-when-empty",
                    "Disable pause-when-empty to prevent task/plugin issues (Paper Chan recommended)"));
            }
        }
    }

    /**
     * Paper Chan: minimum 4 threads/cores recommended.
     */
    private void checkProcessorCount(HardwareAssessment hardware, List<RuleResult> results) {
        int processors = hardware.getAvailableProcessors();
        if (processors > 0 && processors < 4) {
            results.add(RuleResult.builder("safety-low-threads")
                .group("safety").severity(Severity.WARNING).confidence(0.85)
                .why("Server has only " + processors + " available processor(s) — " +
                        "Paper Chan recommends a minimum of 4 threads/cores")
                .impact("Modern Minecraft servers need at least 4 threads for main thread, " +
                        "chunk loading, networking, and GC")
                .tradeoff("Consider upgrading hosting plan or dedicating more cores")
                .recommendation("Use a host with at least 4 threads/cores")
                .build());
        }
    }

    private void checkRedstoneDefaults(ConfigAdapter configs, HardwareTier tier,
                                        AggressivenessLevel level,
                                        List<RuleResult> results, List<PatchProposal> proposals) {
        // Recommend LessLag redstone limits based on tier
        int recMaxActivations;
        switch (tier) {
            case LOW:  recMaxActivations = 150; break;
            case HIGH: recMaxActivations = 350; break;
            default:   recMaxActivations = 250; break;
        }
        if (level == AggressivenessLevel.AGGRESSIVE) {
            recMaxActivations = (int) (recMaxActivations * 0.6);
        }

        proposals.add(new PatchProposal("config.yml",
            "modules.redstone.max-activations-per-chunk",
            "250", String.valueOf(recMaxActivations),
            RiskTag.MEDIUM, ApplyScope.LESSLAG_APPLY, "safety-redstone",
            "Tune redstone activation limit for " + tier.getDisplayName() + " hardware"));
    }

    private void checkBreedingLimits(ConfigAdapter configs, GameProfile profile, HardwareTier tier,
                                      List<RuleResult> results, List<PatchProposal> proposals) {
        int recBreeding;
        switch (tier) {
            case LOW:  recBreeding = 10; break;
            case HIGH: recBreeding = 25; break;
            default:   recBreeding = 20; break;
        }
        if (profile == GameProfile.SKYBLOCK) {
            recBreeding = (int) (recBreeding * 0.7); // tighter on skyblock
        }

        proposals.add(new PatchProposal("config.yml",
            "modules.breeding-limiter.max-animals-per-chunk",
            "20", String.valueOf(recBreeding),
            RiskTag.LOW, ApplyScope.LESSLAG_APPLY, "safety-breeding",
            "Set breeding limit for " + profile.getDisplayName() + " / " + tier.getDisplayName()));
    }
}
