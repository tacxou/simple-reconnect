package com.simpleplugins.youer;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class YouerDeathBridgePlugin extends JavaPlugin implements Listener {
    public static final String DEATH_MARKER = "[SIMPLE_RECONNECT_DEATH]";

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("YouerDeathBridge enabled.");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();

        // Kick on next tick so death processing completes server-side first.
        getServer().getScheduler().runTask(this, () ->
            player.kick(Component.text(DEATH_MARKER))
        );
    }
}
