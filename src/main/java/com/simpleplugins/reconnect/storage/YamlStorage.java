package com.simpleplugins.reconnect.storage;

import com.simpleplugins.reconnect.ReconnectVelocity;
import com.velocitypowered.api.scheduler.ScheduledTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simpleyaml.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public class YamlStorage extends StorageMethod {

    public @Nullable YamlConfiguration data;
    public @NotNull File dataPath = ReconnectVelocity.get()
        .getDataDirectory()
        .resolve("data.yml")
        .toFile();

    private @Nullable ScheduledTask autoSaveTask;

    @Override
    public void init() {
        if (!dataPath.exists()) {
            try {
                dataPath.createNewFile();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        data = YamlConfiguration.loadConfiguration(dataPath);

        autoSaveTask = ReconnectVelocity.get()
            .getProxy()
            .getScheduler()
            .buildTask(ReconnectVelocity.get(), this::saveData)
            .repeat(Duration.ofMinutes(5L))
            .schedule();
    }

    @Override
    public void setLastServer(String uuid, String servername) {
        YamlConfiguration yaml = Objects.requireNonNull(data);
        yaml.set(uuid + ".server", servername);
        saveData();
    }

    @Override
    public String getLastServer(String uuid) {
        YamlConfiguration yaml = Objects.requireNonNull(data);
        String value = yaml.getString(uuid + ".server");
        if (value != null) {
            return value;
        }

        // Backward compatibility with old format: uuid: "servername"
        return yaml.getString(uuid);
    }

    @Override
    public void setLastDisconnectTimestamp(String uuid, long timestamp) {
        Objects.requireNonNull(data).set(uuid + ".lastDisconnectTimestamp", timestamp);
        saveData();
    }

    @Override
    public @Nullable Long getLastDisconnectTimestamp(String uuid) {
        YamlConfiguration yaml = Objects.requireNonNull(data);
        if (!yaml.contains(uuid + ".lastDisconnectTimestamp")) {
            return null;
        }
        return yaml.getLong(uuid + ".lastDisconnectTimestamp");
    }

    @Override
    public void save() {
        saveData();
        Optional.ofNullable(autoSaveTask).ifPresent(ScheduledTask::cancel);
    }

    private void saveData() {
        try {
            Objects.requireNonNull(data).save(dataPath);
        } catch (IOException exception) {
            exception.printStackTrace();
        }
    }

    @Override
    public String getMethod() {
        return "yaml";
    }
}
