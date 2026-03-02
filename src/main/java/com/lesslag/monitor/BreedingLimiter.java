package com.lesslag.monitor;

import com.lesslag.LessLag;
import org.bukkit.Chunk;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityBreedEvent;

public class BreedingLimiter implements Listener {

    private final LessLag plugin;
    private boolean enabled;
    private int maxAnimalsPerChunk;
    private String message;

    // Runtime stats
    private volatile long totalBlocked = 0;
    private volatile String lastBlockedType = "";
    private volatile String lastBlockedWorld = "";

    public BreedingLimiter(LessLag plugin) {
        this.plugin = plugin;
        reloadConfig();
    }

    public void reloadConfig() {
        this.enabled = plugin.getConfig().getBoolean("modules.breeding-limiter.enabled", true);
        this.maxAnimalsPerChunk = plugin.getConfig().getInt("modules.breeding-limiter.max-animals-per-chunk", 20);
        this.message = plugin.getConfig().getString("modules.breeding-limiter.message",
                "&cFarm limit reached! Cannot breed more animals in this chunk.");
    }

    public void setMaxAnimalsPerChunk(int maxAnimalsPerChunk) {
        this.maxAnimalsPerChunk = maxAnimalsPerChunk;
    }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void stop() {
        HandlerList.unregisterAll(this);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (!enabled)
            return;

        if (!(event.getEntity() instanceof Animals))
            return;

        Chunk chunk = event.getEntity().getLocation().getChunk();
        EntityType type = event.getEntity().getType();

        // Proactive check: count triggers of same type in chunk
        int count = 0;
        for (Entity e : chunk.getEntities()) {
            if (e.getType() == type) {
                count++;
                if (count >= maxAnimalsPerChunk) {
                    break;
                }
            }
        }

        if (count >= maxAnimalsPerChunk) {
            event.setCancelled(true);
            totalBlocked++;
            lastBlockedType = type.name();
            lastBlockedWorld = chunk.getWorld().getName();

            if (event.getBreeder() instanceof Player) {
                Player player = (Player) event.getBreeder();
                if (message != null && !message.isEmpty()) {
                    LessLag.sendActionBar(player, message);
                }
            }
        }
    }

    // ── Getters ──
    public long getTotalBlocked()     { return totalBlocked; }
    public String getLastBlockedType() { return lastBlockedType; }
    public String getLastBlockedWorld(){ return lastBlockedWorld; }
    public boolean isEnabled()         { return enabled; }
    public int getMaxAnimalsPerChunk() { return maxAnimalsPerChunk; }
}
