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
     * <p>
     * On Paper: dispatches to main thread if not already there.<br>
     * On Folia: <em>always</em> dispatches through {@code runGlobal} because
     * {@code Bukkit.isPrimaryThread()} is only {@code true} on the global-region
     * thread — other region threads would incorrectly skip the dispatch and call
     * player API from the wrong thread.
     */
    public static void notifyAdminsAsync(String message) {
        LessLag plugin = LessLag.getInstance();
        if (plugin == null || !plugin.isEnabled())
            return;
        if (!SchedulerAdapter.isFolia() && Bukkit.isPrimaryThread()) {
            notifyAdmins(message);
        } else {
            SchedulerAdapter.runGlobal(plugin, () -> {
                LessLag p = LessLag.getInstance();
                if (p != null && p.isEnabled())
                    notifyAdmins(message);
            });
        }
    }

    /**
     * Send a message to all admins without prefix.
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
     * <p>
     * On Paper: dispatches to main thread if needed.<br>
     * On Folia: always dispatches through {@code runGlobal} for the same reason
     * as {@link #notifyAdminsAsync(String)}.
     */
    public static void broadcastAsync(String message) {
        LessLag plugin = LessLag.getInstance();
        if (plugin == null || !plugin.isEnabled())
            return;
        if (!SchedulerAdapter.isFolia() && Bukkit.isPrimaryThread()) {
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
