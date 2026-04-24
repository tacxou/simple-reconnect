package com.simpleplugins.reconnect.storage;

import com.simpleplugins.reconnect.ReconnectVelocity;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.MetaNode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class LuckPermsStorage extends StorageMethod {
    private static final @NotNull String NODE_NAME = "velocity.reconnect";
    private static final @NotNull String NODE_TIMESTAMP_NAME = "velocity.reconnect.lastdisconnect";

    @Override
    public void init() {
        try {
            Class.forName("net.luckperms.api.LuckPermsProvider");
        } catch (ClassNotFoundException exception) {
            ReconnectVelocity.get().getLogger().warn("LuckPerms is not installed!");
            exception.printStackTrace();
        }
        ReconnectVelocity.get().getLogger().info("LuckPerms found!");
    }

    @Override
    @Deprecated(forRemoval = true)
    public void setLastServer(String uuid, String servername) {
        ReconnectVelocity.get().getLogger().warn("Call to depreciated API");
	    setLastServer(UUID.fromString(uuid), servername);
    }
    @Override
    public void setLastServer(@NotNull UUID uuid, String servername) {
        User user = LuckPermsProvider.get()
            .getUserManager()
            .getUser(uuid);

        if (user == null) {
		ReconnectVelocity.get().getLogger().error("Unable to retireve user object for {}", uuid);
		return;
	}

        MetaNode node = MetaNode.builder(NODE_NAME, servername)
            .build();

        user.data().clear(NodeType.META.predicate((mn) -> mn.getMetaKey().equals(NODE_NAME)));
        user.data().add(node);

        LuckPermsProvider.get()
            .getUserManager()
            .saveUser(user);
    }

    @Override
    @Deprecated(forRemoval = true)
    public String getLastServer(String uuid) {
        ReconnectVelocity.get().getLogger().warn("Call to depreciated API");
	    return getLastServer(UUID.fromString(uuid));
    }
    @Override
    public @Nullable String getLastServer(@NotNull UUID uuid) {
        User user = LuckPermsProvider.get()
            .getUserManager()
            .getUser(uuid);

        if (user == null) return null;

        return user.getCachedData()
            .getMetaData()
            .getMetaValue(NODE_NAME);
    }

    @Override
    public void setLastDisconnectTimestamp(@NotNull UUID uuid, long timestamp) {
        User user = LuckPermsProvider.get()
            .getUserManager()
            .getUser(uuid);

        if (user == null) return;

        MetaNode node = MetaNode.builder(NODE_TIMESTAMP_NAME, Long.toString(timestamp))
            .build();

        user.data().clear(NodeType.META.predicate((mn) -> mn.getMetaKey().equals(NODE_TIMESTAMP_NAME)));
        user.data().add(node);

        LuckPermsProvider.get()
            .getUserManager()
            .saveUser(user);
    }

    @Override
    public @Nullable Long getLastDisconnectTimestamp(@NotNull UUID uuid) {
        User user = LuckPermsProvider.get()
            .getUserManager()
            .getUser(uuid);

        if (user == null) return null;

        String value = user.getCachedData()
            .getMetaData()
            .getMetaValue(NODE_TIMESTAMP_NAME);

        if (value == null) return null;

        try {
            long timestamp = Long.parseLong(value);
            if (timestamp <= 0) {
                return null;
            }
            return timestamp;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Override
    @Deprecated(forRemoval = true)
    public String getMethod() {
        return "luckperms";
    }
}
