package com.simpleplugins.youer;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class YouerDeathBridgePlugin extends JavaPlugin implements Listener {
    public static final String DEATH_MARKER = "[SIMPLE_RECONNECT_DEATH]";
    private static final long JOIN_GRACE_MS = 5000L;
    private final Map<UUID, Long> joinTimestamps = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> pendingDeathTransfer = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("YouerDeathBridge enabled.");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        joinTimestamps.put(player.getUniqueId(), System.currentTimeMillis());

        // Some hybrid stacks can transiently restore players with invalid dead/dying state.
        // Normalize health right after join to prevent backend safety kicks.
        getServer().getScheduler().runTask(this, () -> normalizePlayerState(player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uniqueId = event.getPlayer().getUniqueId();
        joinTimestamps.remove(uniqueId);
        pendingDeathTransfer.remove(uniqueId);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        EntityDamageEvent lastDamageCause = player.getLastDamageCause();
        long now = System.currentTimeMillis();
        long joinedAt = joinTimestamps.getOrDefault(player.getUniqueId(), 0L);
        boolean inJoinGrace = joinedAt > 0L && now - joinedAt < JOIN_GRACE_MS;

        // Extra guard for hybrid/modded stacks where death-like events can misfire on first transfer.
        if (player.getHealth() > 0.0D || lastDamageCause == null || inJoinGrace) {
            return;
        }

        // Defer transfer until respawn, so we never store a dead player state on disconnect.
        pendingDeathTransfer.put(player.getUniqueId(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        UUID uniqueId = player.getUniqueId();
        if (!pendingDeathTransfer.containsKey(uniqueId)) {
            return;
        }

        // Kick one tick after respawn so the player is alive and state is stable.
        getServer().getScheduler().runTask(this, () -> {
            if (!player.isOnline() || player.isDead()) {
                return;
            }
            pendingDeathTransfer.remove(uniqueId);
            player.kick(Component.text(DEATH_MARKER));
        });
    }

    private void normalizePlayerState(Player player) {
        if (!player.isOnline()) {
            return;
        }

        double minSafeHealth = 1.0D;
        if (player.isDead() || player.getHealth() <= 0.0D) {
            double maxHealth = player.getMaxHealth();
            player.setHealth(Math.min(minSafeHealth, maxHealth));
        }
    }
}
