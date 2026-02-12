package com.lesslag.monitor;

import com.lesslag.LessLag;
import com.lesslag.monitor.LagSourceAnalyzer.WorldSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;

import java.util.*;
import java.util.function.Consumer;

public class IncrementalSnapshotBuilder implements Runnable {

    private final LessLag plugin;
    private final Consumer<WorldSnapshot[]> callback;

    private final List<World> worlds;
    private final WorldSnapshot[] snapshots;

    private int currentWorldIndex = 0;
    private int currentChunkIndex = 0;
    private Chunk[] currentWorldChunks;

    // Accumulators for current world
    private Map<String, Integer> entityCounts;
    private Map<Long, Integer> chunkEntityCounts;
    private int totalEntities;

    private static final long MAX_NANOS_PER_TICK = 1_000_000; // 1ms budget

    public IncrementalSnapshotBuilder(LessLag plugin, Consumer<WorldSnapshot[]> callback) {
        this.plugin = plugin;
        this.callback = callback;
        this.worlds = Bukkit.getWorlds();
        this.snapshots = new WorldSnapshot[worlds.size()];
    }

    public void start() {
        Bukkit.getScheduler().runTask(plugin, this);
    }

    @Override
    public void run() {
        long stopTime = System.nanoTime() + MAX_NANOS_PER_TICK;

        while (currentWorldIndex < worlds.size()) {
            World world = worlds.get(currentWorldIndex);

            // Initialize world processing
            if (currentWorldChunks == null) {
                currentWorldChunks = world.getLoadedChunks();
                entityCounts = new HashMap<>();
                chunkEntityCounts = new HashMap<>();
                totalEntities = 0;
                currentChunkIndex = 0;
            }

            // Process chunks
            while (currentChunkIndex < currentWorldChunks.length) {
                if (System.nanoTime() > stopTime) {
                    // Time up, reschedule
                    Bukkit.getScheduler().runTask(plugin, this);
                    return;
                }

                Chunk chunk = currentWorldChunks[currentChunkIndex];
                if (chunk.isLoaded()) {
                    for (Entity entity : chunk.getEntities()) {
                        String typeName = entity.getType().name();
                        entityCounts.merge(typeName, 1, Integer::sum);
                        totalEntities++;

                        long chunkKey = ((long) chunk.getX() << 32) | (chunk.getZ() & 0xFFFFFFFFL);
                        chunkEntityCounts.merge(chunkKey, 1, Integer::sum);
                    }
                }
                currentChunkIndex++;
            }

            // Finish world
            snapshots[currentWorldIndex] = new WorldSnapshot(
                    world.getName(),
                    totalEntities,
                    currentWorldChunks.length,
                    entityCounts,
                    chunkEntityCounts);

            currentWorldIndex++;
            currentWorldChunks = null; // Reset for next world
        }

        // All done
        callback.accept(snapshots);
    }
}
