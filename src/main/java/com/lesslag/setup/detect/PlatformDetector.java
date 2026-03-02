package com.lesslag.setup.detect;

import com.lesslag.util.SchedulerAdapter;
import org.bukkit.Bukkit;

import java.io.File;
import java.util.logging.Logger;

/**
 * Detects which server fork is running (Paper, Purpur, Pufferfish, Leaf)
 * using runtime class probing and config-file signatures.
 */
public class PlatformDetector {

    private static final Logger LOG = Logger.getLogger("LessLag-Setup");

    private String detectedPlatform = "Unknown";
    private String platformVersion = "";
    private String minecraftVersion = "";
    private boolean folia;

    public void detect() {
        minecraftVersion = Bukkit.getMinecraftVersion();
        platformVersion = Bukkit.getVersion();
        folia = SchedulerAdapter.isFolia();

        // Probe in specificity order (most specific fork first)
        if (folia) {
            detectedPlatform = "Folia";
        } else if (hasClass("org.leavesmc.leaves.LeavesConfig")
                || hasClass("top.leavesmc.leaves.LeavesConfig")) {
            detectedPlatform = "Leaf";
        } else if (hasClass("gg.pufferfish.pufferfish.PufferfishConfig")
                || hasClass("net.pufferfish.pufferfish.PufferfishConfig")) {
            detectedPlatform = "Pufferfish";
        } else if (hasClass("org.purpurmc.purpur.PurpurConfig")
                || hasClass("net.pl3x.purpur.PurpurConfig")) {
            detectedPlatform = "Purpur";
        } else if (hasClass("io.papermc.paper.configuration.GlobalConfiguration")
                || hasClass("com.destroystokyo.paper.PaperConfig")) {
            detectedPlatform = "Paper";
        } else if (hasClass("org.spigotmc.SpigotConfig")) {
            detectedPlatform = "Spigot";
        } else {
            detectedPlatform = "CraftBukkit";
        }

        // Config-file cross-check for forks that don't load their class at discovery time
        File serverRoot = new File(".");
        if (detectedPlatform.equals("Paper") || detectedPlatform.equals("Spigot")) {
            if (new File(serverRoot, "purpur.yml").exists()) {
                detectedPlatform = "Purpur";
            } else if (new File(serverRoot, "pufferfish.yml").exists()) {
                detectedPlatform = "Pufferfish";
            } else if (new File(serverRoot, "leaves.yml").exists()) {
                detectedPlatform = "Leaf";
            }
        }

        LOG.info("Detected platform: " + detectedPlatform + " (" + platformVersion + ")");
    }

    public String getDetectedPlatform() { return detectedPlatform; }
    public String getPlatformVersion() { return platformVersion; }
    public String getMinecraftVersion() { return minecraftVersion; }
    public boolean isFolia() { return folia; }

    public boolean isPaper() {
        return !detectedPlatform.equals("Spigot") && !detectedPlatform.equals("CraftBukkit");
    }

    public boolean isPurpur() { return detectedPlatform.equals("Purpur"); }
    public boolean isPufferfish() { return detectedPlatform.equals("Pufferfish"); }
    public boolean isLeaf() { return detectedPlatform.equals("Leaf"); }

    private static boolean hasClass(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
