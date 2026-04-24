package com.simpleplugins.reconnect;

import com.simpleplugins.reconnect.hook.LiteBansHook;
import com.simpleplugins.reconnect.util.MessageHelper;
import com.simpleplugins.reconnect.util.VelocityChat;
import com.simpleplugins.reconnect.util.updater.UpdateChecker;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.messages.ChannelMessageSink;
import com.velocitypowered.api.proxy.messages.ChannelMessageSource;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class ReconnectListener {
    public static final MinecraftChannelIdentifier DEATH_CHANNEL =
        MinecraftChannelIdentifier.from("simplereconnect:death");

    private final @NotNull ReconnectVelocity plugin;

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
        ReconnectVelocity.get()
            .getStorageManager()
            .getStorageMethod()
            .setLastServer(event.getPlayer().getUniqueId(), event.getServer().getServerInfo().getName());
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
        plugin.getStorageManager()
            .getStorageMethod()
            .setLastDisconnectTimestamp(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @Subscribe
    public void onPluginMessage(@NotNull PluginMessageEvent event) {
        if (!DEATH_CHANNEL.equals(event.getIdentifier())) {
            return;
        }

        event.setResult(PluginMessageEvent.ForwardResult.handled());
        Optional<Player> targetPlayer = resolveTargetPlayer(event.getSource(), event.getTarget(), event.getData());
        targetPlayer.ifPresent(this::connectPlayerToTryServer);
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
        if (isPlayerDeathKick(event)) {
            Optional<RegisteredServer> tryServer = findTryServer();
            tryServer.ifPresent(server -> event.setResult(KickedFromServerEvent.RedirectPlayer.create(server)));
            return;
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

    private boolean isPlayerDeathKick(@NotNull KickedFromServerEvent event) {
        Component reason = event.getServerKickReason().orElse(null);
        if (reason == null) {
            return false;
        }

        String plainReason = PlainTextComponentSerializer.plainText().serialize(reason).toLowerCase(Locale.ROOT);
        return plainReason.contains("you died")
            || plainReason.contains("died")
            || plainReason.contains("mort")
            || plainReason.contains("est mort");
    }

    private Optional<RegisteredServer> findTryServer() {
        List<String> attemptOrder = plugin.getProxy().getConfiguration().getAttemptConnectionOrder();
        if (attemptOrder == null || attemptOrder.isEmpty()) {
            return Optional.empty();
        }

        for (String serverName : attemptOrder) {
            if (plugin.getConfig().blacklist.contains(serverName)) {
                continue;
            }

            Optional<RegisteredServer> server = plugin.getProxy().getServer(serverName);
            if (server.isPresent()) {
                return server;
            }
        }

        return Optional.empty();
    }

    private void connectPlayerToTryServer(@NotNull Player player) {
        String currentServer = player.getCurrentServer()
            .map(connection -> connection.getServerInfo().getName())
            .orElse("");

        Optional<RegisteredServer> tryServer = findTryServer(currentServer);
        if (tryServer.isEmpty()) {
            return;
        }

        try {
            player.createConnectionRequest(tryServer.get()).fireAndForget();
        } catch (Exception failure) {
            if (plugin.getConfig().debug) {
                failure.printStackTrace();
            }
        }
    }

    private Optional<Player> resolveTargetPlayer(
        @NotNull ChannelMessageSource source,
        @NotNull ChannelMessageSink target,
        byte @NotNull [] data
    ) {
        if (target instanceof Player) {
            return Optional.of((Player) target);
        }

        if (source instanceof Player) {
            return Optional.of((Player) source);
        }

        String payload = new String(data, StandardCharsets.UTF_8).trim();
        if (payload.isEmpty()) {
            return Optional.empty();
        }

        try {
            return plugin.getProxy().getPlayer(UUID.fromString(payload));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private Optional<RegisteredServer> findTryServer(@NotNull String excludedServerName) {
        List<String> attemptOrder = plugin.getProxy().getConfiguration().getAttemptConnectionOrder();
        if (attemptOrder == null || attemptOrder.isEmpty()) {
            return Optional.empty();
        }

        for (String serverName : attemptOrder) {
            if (serverName.equalsIgnoreCase(excludedServerName)) {
                continue;
            }

            if (plugin.getConfig().blacklist.contains(serverName)) {
                continue;
            }

            Optional<RegisteredServer> server = plugin.getProxy().getServer(serverName);
            if (server.isPresent()) {
                return server;
            }
        }

        return Optional.empty();
    }
}
