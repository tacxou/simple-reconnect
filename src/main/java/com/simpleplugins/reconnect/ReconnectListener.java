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
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class ReconnectListener {
    private static final String TRY_SERVER_NAME = "try";
    private static final String DEATH_MARKER = "[SIMPLE_RECONNECT_DEATH]";

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

        if (plugin.getConfig().debug) {
            plugin.getLogger().info(
                "[SimpleReconnect] Kick debug | player={} | from={} | reason='{}' | deathKick={} | currentResult={}",
                event.getPlayer().getUsername(),
                event.getServer().getServerInfo().getName(),
                getKickReasonPlainText(event),
                deathKick,
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
        Component reasonComponent = event.getServerKickReason().orElse(null);
        if (reasonComponent == null) {
            return false;
        }

        if (containsDeathTranslationKey(reasonComponent)) {
            return true;
        }

        String plainText = PlainTextComponentSerializer.plainText()
            .serialize(reasonComponent)
            .trim()
            .toLowerCase(Locale.ROOT);

        return plainText.contains(DEATH_MARKER.toLowerCase(Locale.ROOT))
            || plainText.equals("you died!")
            || plainText.equals("you died")
            || plainText.equals("vous etes mort !")
            || plainText.equals("vous etes mort")
            || plainText.equals("vous êtes mort !")
            || plainText.equals("vous êtes mort")
            || plainText.contains(" was slain")
            || plainText.contains(" was shot")
            || plainText.contains(" was killed")
            || plainText.contains(" drowned")
            || plainText.contains(" blew up")
            || plainText.contains(" hit the ground too hard")
            || plainText.contains(" fell")
            || plainText.contains(" burned")
            || plainText.contains(" went up in flames")
            || plainText.contains(" tried to swim in lava")
            || plainText.contains(" suffocated")
            || plainText.contains(" starved")
            || plainText.contains(" withered away")
            || plainText.contains(" froze to death")
            || plainText.contains(" est mort")
            || plainText.contains(" a ete tue")
            || plainText.contains(" a été tué");
    }

    private @NotNull String getKickReasonPlainText(@NotNull KickedFromServerEvent event) {
        return event.getServerKickReason()
            .map(PlainTextComponentSerializer.plainText()::serialize)
            .map(String::trim)
            .orElse("<empty>");
    }

    private boolean containsDeathTranslationKey(@NotNull Component component) {
        if (component instanceof TranslatableComponent translatable) {
            if (translatable.key().startsWith("death.")) {
                return true;
            }
        }

        for (Component child : component.children()) {
            if (containsDeathTranslationKey(child)) {
                return true;
            }
        }

        return false;
    }
}
