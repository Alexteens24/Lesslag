package com.lesslag.util;

import com.lesslag.LessLag;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Centralized notification utility for sending admin messages.
 * Eliminates duplication across all monitors.
 */
public final class NotificationHelper {

    private NotificationHelper() {
    }

    /**
     * Send a message to all online admins with lesslag.notify permission.
     * MUST be called from the main thread.
     */
    public static void notifyAdmins(String message) {
        LessLag plugin = LessLag.getInstance();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("lesslag.notify")) {
                LessLag.sendMessage(player, plugin.getPrefix() + message);
            }
        }
    }

    /**
     * Send a message to admins from any thread.
     * Dispatches to main thread automatically if needed.
     */
    public static void notifyAdminsAsync(String message) {
        LessLag plugin = LessLag.getInstance();
        if (Bukkit.isPrimaryThread()) {
            notifyAdmins(message);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> notifyAdmins(message));
        }
    }

    /**
     * Send a raw message (without prefix) to all admins.
     * MUST be called from the main thread.
     */
    public static void notifyAdminsRaw(String message) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("lesslag.notify")) {
                LessLag.sendMessage(player, message);
            }
        }
    }
}
