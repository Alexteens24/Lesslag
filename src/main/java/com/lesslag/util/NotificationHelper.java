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
        if (plugin == null || !plugin.isEnabled())
            return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("lesslag.notify")) {
                LessLag.sendMessage(player, plugin.getPrefix() + message);
            }
        }
    }

    /**
     * Send a message to admins from any thread.
     * Dispatches to main thread automatically if needed.
     * Safe to call from monitor threads and during plugin shutdown.
     */
    public static void notifyAdminsAsync(String message) {
        LessLag plugin = LessLag.getInstance();
        if (plugin == null || !plugin.isEnabled())
            return;
        if (Bukkit.isPrimaryThread()) {
            notifyAdmins(message);
        } else {
            SchedulerAdapter.runGlobal(plugin, () -> {
                // Re-check inside the lambda: plugin may have been disabled
                // between the time the task was queued and when it runs.
                LessLag p = LessLag.getInstance();
                if (p != null && p.isEnabled())
                    notifyAdmins(message);
            });
        }
    }

    /**
     * Send a raw message (without prefix) to all admins.
     * MUST be called from the main thread.
     */
    public static void notifyAdminsRaw(String message) {
        LessLag plugin = LessLag.getInstance();
        if (plugin == null || !plugin.isEnabled())
            return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("lesslag.notify")) {
                LessLag.sendMessage(player, message);
            }
        }
    }

    /**
     * Send a message to all online players.
     * MUST be called from the main thread.
     */
    public static void broadcast(String message) {
        LessLag plugin = LessLag.getInstance();
        if (plugin == null || !plugin.isEnabled())
            return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            LessLag.sendMessage(player, plugin.getPrefix() + message);
        }
    }

    /**
     * Send a message to all players from any thread.
     * Dispatches to main thread automatically if needed.
     */
    public static void broadcastAsync(String message) {
        LessLag plugin = LessLag.getInstance();
        if (plugin == null || !plugin.isEnabled())
            return;
        if (Bukkit.isPrimaryThread()) {
            broadcast(message);
        } else {
            SchedulerAdapter.runGlobal(plugin, () -> {
                LessLag p = LessLag.getInstance();
                if (p != null && p.isEnabled())
                    broadcast(message);
            });
        }
    }
}
