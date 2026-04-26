package com.simpleplugins.youer;

import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.FileConfiguration;
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
import java.util.List;
import java.util.Locale;
import java.lang.reflect.Method;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public final class YouerDeathBridgePlugin extends JavaPlugin implements Listener {
    public static final String DEATH_MARKER = "[SIMPLE_RECONNECT_DEATH]";
    private static final String BUNGEE_CHANNEL = "BungeeCord";
    private static final long JOIN_GRACE_MS = 5000L;
    private static final int JOIN_PORTAL_COOLDOWN_TICKS = 100;
    private final Map<UUID, Long> joinTimestamps = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> pendingDeathTransfer = new ConcurrentHashMap<>();
    private String serverId;
    private List<String> disabledServerIds;
    private String deathTransferServer;
    private boolean usePluginMessageTransfer;
    private List<String> tryOrderServers;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();
        getServer().getMessenger().registerOutgoingPluginChannel(this, BUNGEE_CHANNEL);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("YouerDeathBridge enabled on serverId='" + serverId + "' (disabledOn=" + disabledServerIds + ").");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        joinTimestamps.put(player.getUniqueId(), System.currentTimeMillis());
        // Prevent immediate back-teleport loops when a player joins inside/near transfer portals.
        player.setPortalCooldown(JOIN_PORTAL_COOLDOWN_TICKS);

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

        if (!isDeathTransferEnabledOnThisServer()) {
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

        // Run next tick to let respawn state fully settle on hybrid/modded stacks.
        getServer().getScheduler().runTask(this, () -> {
            pendingDeathTransfer.remove(uniqueId);
            if (!player.isOnline() || player.isDead()) {
                return;
            }

            syncFtbChunksForTransfer(player);
            if (!transferToVelocityServer(player)) {
                player.kick(Component.text(DEATH_MARKER));
            }
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

    private void loadConfigValues() {
        FileConfiguration config = getConfig();
        serverId = config.getString("server-id", "").trim();
        disabledServerIds = config.getStringList("disable-death-transfer-on-servers");
        deathTransferServer = config.getString("death-transfer-server", "survie").trim();
        usePluginMessageTransfer = config.getBoolean("use-plugin-message-transfer", true);
        tryOrderServers = config.getStringList("try-order-servers");
    }

    private boolean isDeathTransferEnabledOnThisServer() {
        if (serverId.isEmpty()) {
            // If not configured, keep previous behavior to avoid surprise disabling.
            return true;
        }

        String normalizedServerId = serverId.toLowerCase(Locale.ROOT);
        return disabledServerIds.stream()
            .map(entry -> entry.toLowerCase(Locale.ROOT))
            .noneMatch(normalizedServerId::equals);
    }

    private void syncFtbChunksForTransfer(Player player) {
        try {
            Class<?> ftbChunksClass = Class.forName("dev.ftb.mods.ftbchunks.FTBChunks");
            Object instance = ftbChunksClass.getField("instance").get(null);
            if (instance == null) {
                return;
            }

            Method getHandle = player.getClass().getMethod("getHandle");
            Object serverPlayer = getHandle.invoke(player);
            if (serverPlayer == null) {
                return;
            }

            Class<?> serverPlayerClass = Class.forName("net.minecraft.server.level.ServerPlayer");
            Method loggedOut = ftbChunksClass.getMethod("loggedOut", serverPlayerClass);
            loggedOut.invoke(instance, serverPlayer);
        } catch (Throwable ignored) {
            // Optional compat: ignore if FTB Chunks or NMS bridge is unavailable.
        }
    }

    private boolean transferToVelocityServer(Player player) {
        String targetServer = resolveDeathTransferTarget();
        if (!usePluginMessageTransfer || targetServer.isEmpty()) {
            return false;
        }

        if (transferViaBblPortalsBridge(player, targetServer)) {
            return true;
        }

        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(byteStream)) {
            out.writeUTF("Connect");
            out.writeUTF(targetServer);
        } catch (IOException e) {
            return false;
        }

        player.sendPluginMessage(this, BUNGEE_CHANNEL, byteStream.toByteArray());
        return true;
    }

    private boolean transferViaBblPortalsBridge(Player player, String targetServer) {
        try {
            Method getHandle = player.getClass().getMethod("getHandle");
            Object serverPlayer = getHandle.invoke(player);
            if (serverPlayer == null) {
                return false;
            }

            Class<?> serverPlayerClass = Class.forName("net.minecraft.server.level.ServerPlayer");
            Class<?> velocityBridgeClass = Class.forName("com.benbenlaw.portals.integration.velocity.VelocityBridge");
            Method sendPlayerToServer = velocityBridgeClass.getMethod("sendPlayerToServer", serverPlayerClass, String.class);
            sendPlayerToServer.invoke(null, serverPlayer, targetServer);
            return true;
        } catch (Throwable ignored) {
            // BBL-Portals bridge unavailable; fallback to Bungee plugin message transfer.
            return false;
        }
    }

    private String resolveDeathTransferTarget() {
        if (!"try".equalsIgnoreCase(deathTransferServer)) {
            return deathTransferServer;
        }

        for (String server : tryOrderServers) {
            String trimmed = server == null ? "" : server.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }

        return "";
    }
}
