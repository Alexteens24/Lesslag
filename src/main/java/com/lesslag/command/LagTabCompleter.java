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
            "tickmonitor", "entities", "thresholds", "sources",
            "chunks", "redstone", "predictive", "frustum",
            "worldguard", "memory", "clear", "ai", "restore", "reload");

    private static final List<String> CLEAR_TYPES = Arrays.asList(
            "items", "xp", "mobs", "hostile", "all");

    private static final List<String> AI_ACTIONS = Arrays.asList(
            "disable", "restore", "status");

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
            }
        }

        return new ArrayList<>();
    }
}
