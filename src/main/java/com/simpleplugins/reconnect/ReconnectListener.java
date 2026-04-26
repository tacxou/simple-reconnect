package com.simpleplugins.reconnect;

import com.simpleplugins.reconnect.hook.LiteBansHook;
import com.simpleplugins.reconnect.util.MessageHelper;
import com.simpleplugins.reconnect.util.VelocityChat;
import com.simpleplugins.reconnect.util.updater.UpdateChecker;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class ReconnectListener {
    private static final String TRY_SERVER_NAME = "try";
    private static final String DEATH_MARKER = "[SIMPLE_RECONNECT_DEATH]";
    private static final long TRANSIENT_RETRY_WINDOW_MS = 15000L;
    private static final long TRANSIENT_RETRY_DELAY_SECONDS = 2L;
    private static final long QUICK_BOUNCE_WINDOW_MS = 5000L;
    private static final long QUICK_BOUNCE_RETRY_DELAY_SECONDS = 1L;

    private final @NotNull ReconnectVelocity plugin;
    private final Map<UUID, Long> transientRetryWindow = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastConnectedServer = new ConcurrentHashMap<>();
    private final Map<UUID, String> previousConnectedServer = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastConnectedAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> quickBounceRetryWindow = new ConcurrentHashMap<>();

    public ReconnectListener(@NotNull ReconnectVelocity plugin) {
        this.plugin = plugin;
    }

    @Subscribe
    public void onChooseInitialServer(@NotNull PlayerChooseInitialServerEvent event) {
        if (EventUtils.isForcedHost(event)) return;

        Player player = event.getPlayer();
        Long lastDisconnectTimestamp = plugin.getStorageManager()
            .getStorageMethod()
            .getLastDisconnectTimestamp(player.getUniqueId());

        long reconnectExpirySeconds = plugin.getConfig().reconnectExpirySeconds;
        if (reconnectExpirySeconds > 0 && lastDisconnectTimestamp != null) {
            long now = System.currentTimeMillis();
            if (now - lastDisconnectTimestamp > reconnectExpirySeconds * 1000L) {
                // Let Velocity select initial/fallback server with default behavior.
                return;
            }
        }

        String previousServerName = plugin.getStorageManager()
            .getStorageMethod()
            .getLastServer(player.getUniqueId());

        if (previousServerName == null) return;

        // Check if per-server-permissions is enabled, and check if they have permissions
        if (plugin.getConfig().perServerPermission && !player.hasPermission("velocity.reconnect." + previousServerName))
            return;

        // Check if the server is blacklisted
        if (plugin.getConfig().blacklist.contains(previousServerName)) return;

        RegisteredServer server = plugin.getProxy()
            .getServer(previousServerName)
            .orElse(null);

        // Server was likely unregistered
        if (server == null) return;

        if (LiteBansHook.isBannedFromServer(player, previousServerName)) {
            return;
        }

        try {
            server.ping().get();
        } catch (Exception failure) {

            if (plugin.getConfig().debug) {
                failure.printStackTrace();
            }

            if (plugin.getConfig().notAvailable) {
                plugin.getProxy()
                    .getScheduler()
                    .buildTask(plugin, () -> MessageHelper.sendMessage(player, plugin.getConfig().notAvailableMessage))
                    .delay(1, TimeUnit.SECONDS)
                    .schedule();
            }

            return;
        }

        event.setInitialServer(server);

        if (plugin.getConfig().messageOnReconnect) {
            plugin.getProxy()
                .getScheduler()
                .buildTask(plugin, () -> MessageHelper.sendMessage(player, plugin.getConfig().reconnectMessage))
                .delay(1, TimeUnit.SECONDS)
                .schedule();
        }
    }

    @Subscribe
    public void onChangeServer(@NotNull ServerConnectedEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        String newServerName = event.getServer().getServerInfo().getName();
        String oldCurrent = lastConnectedServer.get(playerId);
        String oldPrevious = previousConnectedServer.get(playerId);
        long now = System.currentTimeMillis();
        long lastSwitchAt = lastConnectedAt.getOrDefault(playerId, 0L);

        boolean quickBounce = oldCurrent != null
            && oldPrevious != null
            && newServerName.equals(oldPrevious)
            && now - lastSwitchAt <= QUICK_BOUNCE_WINDOW_MS;

        if (quickBounce) {
            Long lastRetry = quickBounceRetryWindow.get(playerId);
            if (!isInWindow(lastRetry, now, TRANSIENT_RETRY_WINDOW_MS)) {
                quickBounceRetryWindow.put(playerId, now);
                String bouncedFromServerName = oldCurrent;

                plugin.getProxy().getServer(bouncedFromServerName).ifPresent(targetServer ->
                    plugin.getProxy().getScheduler()
                        .buildTask(plugin, () -> {
                            if (!event.getPlayer().isActive()) {
                                return;
                            }

                            event.getPlayer()
                                .createConnectionRequest(targetServer)
                                .connect();
                        })
                        .delay(QUICK_BOUNCE_RETRY_DELAY_SECONDS, TimeUnit.SECONDS)
                        .schedule()
                );

                if (plugin.getConfig().debug) {
                    plugin.getLogger().info(
                        "[SimpleReconnect] Quick bounce detected after death. Retrying '{}' for {}.",
                        bouncedFromServerName,
                        event.getPlayer().getUsername()
                    );
                }
            }
        }

        if (oldCurrent == null) {
            previousConnectedServer.remove(playerId);
        } else {
            previousConnectedServer.put(playerId, oldCurrent);
        }
        lastConnectedServer.put(playerId, newServerName);
        lastConnectedAt.put(playerId, now);

        ReconnectVelocity.get()
            .getStorageManager()
            .getStorageMethod()
            .setLastServer(playerId, newServerName);
    }

    @Subscribe
    public void onPlayerLogin(@NotNull LoginEvent event) {
        if (!plugin.getConfig().checkUpdates) return;

        UpdateChecker checker = plugin.getUpdateChecker();

        if (!event.getPlayer().hasPermission("velocity.reconnect.admin")) return;
        if (checker.isLatest()) return;

        event.getPlayer()
            .sendMessage(VelocityChat.color("<gold><bold>Simple Reconnect</bold> <gray>» <blue>Newer version available! <white>v" + checker.getLatest())
                .clickEvent(ClickEvent.clickEvent(ClickEvent.Action.OPEN_URL, checker.getLink()))
                .hoverEvent(HoverEvent.showText(VelocityChat.color("<gold>Click to update!"))));
    }

    @Subscribe
    public void onPlayerDisconnect(@NotNull DisconnectEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();

        plugin.getStorageManager()
            .getStorageMethod()
            .setLastDisconnectTimestamp(playerId, System.currentTimeMillis());

        lastConnectedServer.remove(playerId);
        previousConnectedServer.remove(playerId);
        lastConnectedAt.remove(playerId);
        quickBounceRetryWindow.remove(playerId);
        transientRetryWindow.remove(playerId);
    }

    /**
     * Prevents switching to a fallback server if the server is not on the blacklist.
     * Off by default and enabled in the configuration.
     * Also uses alternative message, if available.
     *
     * @param event injected event
     */
    @Subscribe
    public void onPlayerKicked(@NotNull KickedFromServerEvent event) {
        boolean deathKick = isDeathKick(event);
        boolean transientJoinError = isTransientJoinError(event);

        if (plugin.getConfig().debug) {
            plugin.getLogger().info(
                "[SimpleReconnect] Kick debug | player={} | from={} | reason='{}' | deathKick={} | transientJoinError={} | currentResult={}",
                event.getPlayer().getUsername(),
                event.getServer().getServerInfo().getName(),
                getKickReasonPlainText(event),
                deathKick,
                transientJoinError,
                event.getResult().getClass().getSimpleName()
            );
        }

        if (deathKick) {
            RegisteredServer tryServer = plugin.getProxy()
                .getServer(TRY_SERVER_NAME)
                .orElse(null);

            if (tryServer != null) {
                event.setResult(KickedFromServerEvent.RedirectPlayer.create(tryServer));
                if (plugin.getConfig().debug) {
                    plugin.getLogger().info(
                        "[SimpleReconnect] Death kick redirect applied -> '{}'",
                        TRY_SERVER_NAME
                    );
                }
            } else if (plugin.getConfig().debug) {
                plugin.getLogger().warn(
                    "[SimpleReconnect] Death kick detected but fallback server '{}' is missing.",
                    TRY_SERVER_NAME
                );
            }

            return;
        }

        if (transientJoinError && event.getResult() instanceof KickedFromServerEvent.RedirectPlayer) {
            UUID playerId = event.getPlayer().getUniqueId();
            long now = System.currentTimeMillis();
            Long lastRetry = transientRetryWindow.get(playerId);

            if (lastRetry == null || now - lastRetry > TRANSIENT_RETRY_WINDOW_MS) {
                transientRetryWindow.put(playerId, now);
                RegisteredServer failedServer = event.getServer();

                plugin.getProxy().getScheduler()
                    .buildTask(plugin, () -> {
                        if (!event.getPlayer().isActive()) {
                            return;
                        }

                        event.getPlayer()
                            .createConnectionRequest(failedServer)
                            .connect();
                    })
                    .delay(TRANSIENT_RETRY_DELAY_SECONDS, TimeUnit.SECONDS)
                    .schedule();

                if (plugin.getConfig().debug) {
                    plugin.getLogger().info(
                        "[SimpleReconnect] Transient join error detected. Scheduling delayed retry to '{}' for {}.",
                        event.getServer().getServerInfo().getName(),
                        event.getPlayer().getUsername()
                    );
                }
                return;
            }
        }

        if (!plugin.getConfig().preventFallback) return;

        RegisteredServer server = event.getServer();
        if (plugin.getConfig().blacklist.contains(server.getServerInfo().getName())) return;

        KickedFromServerEvent.ServerKickResult result = event.getResult();
        if (result instanceof KickedFromServerEvent.RedirectPlayer) {
            List<String> stringMsg = plugin.getConfig().preventFallbackMessage;

            Component msg = MessageHelper.toComponent(event.getPlayer(), stringMsg);

            event.setResult(KickedFromServerEvent.DisconnectPlayer.create(msg));
        }
    }

    private boolean isDeathKick(@NotNull KickedFromServerEvent event) {
        return getKickReasonPlainText(event)
            .toLowerCase(Locale.ROOT)
            .contains(DEATH_MARKER.toLowerCase(Locale.ROOT));
    }

    private @NotNull String getKickReasonPlainText(@NotNull KickedFromServerEvent event) {
        return event.getServerKickReason()
            .map(PlainTextComponentSerializer.plainText()::serialize)
            .map(String::trim)
            .orElse("<empty>");
    }

    private boolean isTransientJoinError(@NotNull KickedFromServerEvent event) {
        String reason = getKickReasonPlainText(event).toLowerCase(Locale.ROOT);
        return reason.contains("error occurred while creating playerentity")
            || reason.contains("please login again");
    }

    private boolean isInWindow(Long timestamp, long now, long windowMs) {
        return timestamp != null && now - timestamp <= windowMs;
    }

}
