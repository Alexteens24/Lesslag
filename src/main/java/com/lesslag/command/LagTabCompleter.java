package com.lesslag.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class LagTabCompleter implements TabCompleter {

    private static final List<String> SUB_COMMANDS = Arrays.asList(
            "status", "health", "tps", "gc", "gcinfo",
            "tickmonitor", "entities", "thresholds", "sources", "trace",
            "chunks", "redstone", "predictive", "frustum",
            "worldguard", "memory", "villager", "clear", "ai", "restore", "setup", "web", "reload");

    private static final List<String> CLEAR_TYPES = Arrays.asList(
            "items", "xp", "mobs", "hostile", "all");

    private static final List<String> AI_ACTIONS = Arrays.asList(
            "disable", "restore", "status");

    private static final List<String> WEB_ACTIONS = Arrays.asList(
            "status", "analyze");

    private static final List<String> SETUP_ACTIONS = Arrays.asList(
            "start", "profile", "tier", "level", "review", "select", "confirm", "abort", "rollback");

    private static final List<String> SETUP_PROFILES = Arrays.asList(
            "smp", "skyblock", "minigame", "creative");

    private static final List<String> SETUP_TIERS = Arrays.asList(
            "low", "mid", "high");

    private static final List<String> SETUP_LEVELS = Arrays.asList(
            "safe", "balanced", "aggressive");

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("lesslag.admin")) {
            return new ArrayList<>();
        }

        if (args.length == 1) {
            return SUB_COMMANDS.stream()
                    .filter(sub -> sub.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "clear":
                    return CLEAR_TYPES.stream()
                            .filter(type -> type.startsWith(args[1].toLowerCase()))
                            .collect(Collectors.toList());
                case "ai":
                    return AI_ACTIONS.stream()
                            .filter(action -> action.startsWith(args[1].toLowerCase()))
                            .collect(Collectors.toList());
                case "web":
                    return WEB_ACTIONS.stream()
                            .filter(action -> action.startsWith(args[1].toLowerCase()))
                            .collect(Collectors.toList());
                case "setup":
                    return SETUP_ACTIONS.stream()
                            .filter(action -> action.startsWith(args[1].toLowerCase()))
                            .collect(Collectors.toList());
            }
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("setup")) {
            switch (args[1].toLowerCase()) {
                case "profile":
                    return SETUP_PROFILES.stream()
                            .filter(p -> p.startsWith(args[2].toLowerCase()))
                            .collect(Collectors.toList());
                case "tier":
                    return SETUP_TIERS.stream()
                            .filter(t -> t.startsWith(args[2].toLowerCase()))
                            .collect(Collectors.toList());
                case "level":
                    return SETUP_LEVELS.stream()
                            .filter(l -> l.startsWith(args[2].toLowerCase()))
                            .collect(Collectors.toList());
            }
        }

        // /lg web analyze <profile>
        if (args.length == 3 && args[0].equalsIgnoreCase("web") && args[1].equalsIgnoreCase("analyze")) {
            return SETUP_PROFILES.stream()
                    .filter(p -> p.startsWith(args[2].toLowerCase()))
                    .collect(Collectors.toList());
        }

        // /lg web analyze <profile> <tier>
        if (args.length == 4 && args[0].equalsIgnoreCase("web") && args[1].equalsIgnoreCase("analyze")) {
            return SETUP_TIERS.stream()
                    .filter(t -> t.startsWith(args[3].toLowerCase()))
                    .collect(Collectors.toList());
        }

        // /lg web analyze <profile> <tier> <level>
        if (args.length == 5 && args[0].equalsIgnoreCase("web") && args[1].equalsIgnoreCase("analyze")) {
            return SETUP_LEVELS.stream()
                    .filter(l -> l.startsWith(args[4].toLowerCase()))
                    .collect(Collectors.toList());
        }

        return new ArrayList<>();
    }
}
