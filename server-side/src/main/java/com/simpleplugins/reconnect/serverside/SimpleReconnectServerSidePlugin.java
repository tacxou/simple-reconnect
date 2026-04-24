package com.simpleplugins.reconnect.serverside;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;

public final class SimpleReconnectServerSidePlugin extends JavaPlugin implements Listener {
    private static final String DEATH_CHANNEL = "simplereconnect:death";

    @Override
    public void onEnable() {
        getServer().getMessenger().registerOutgoingPluginChannel(this, DEATH_CHANNEL);
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, DEATH_CHANNEL);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();

        // Delay one tick to ensure the player connection is stable for messaging.
        Bukkit.getScheduler().runTask(this, () -> {
            byte[] payload = player.getUniqueId().toString().getBytes(StandardCharsets.UTF_8);
            player.sendPluginMessage(this, DEATH_CHANNEL, payload);
        });
    }
}
