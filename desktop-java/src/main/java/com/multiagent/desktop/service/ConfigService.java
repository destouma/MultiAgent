package com.multiagent.desktop.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiagent.desktop.model.AppSettings;
import com.multiagent.desktop.model.ServerProfile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Settings singleton backed by a JSON file, mirroring desktop/electron/config.ts's
 * electron-store usage (same default values, same one-time legacy-fields migration).
 * Uses its own "MultiAgentJava" app-data folder rather than the Electron app's
 * "MultiAgent" one, since the two clients are independent and this file's shape isn't
 * guaranteed to round-trip through electron-store's own format.
 */
public class ConfigService {
    private final Path filePath;
    private final ObjectMapper mapper = new ObjectMapper();
    private AppSettings cached;

    public ConfigService() {
        this(defaultConfigPath());
    }

    public ConfigService(Path filePath) {
        this.filePath = filePath;
        load();
    }

    private static Path defaultConfigPath() {
        String appData = System.getenv("APPDATA");
        Path base = appData != null
                ? Path.of(appData)
                : Path.of(System.getProperty("user.home"), ".config");
        return base.resolve("MultiAgentJava").resolve("config.json");
    }

    private synchronized void load() {
        if (Files.exists(filePath)) {
            try {
                cached = mapper.readValue(filePath.toFile(), AppSettings.class);
                return;
            } catch (IOException e) {
                // Corrupt/unreadable config - fall back to defaults rather than crashing startup.
            }
        }
        cached = new AppSettings();
    }

    public synchronized AppSettings getSettings() {
        return cached;
    }

    public synchronized AppSettings updateSettings(Consumer<AppSettings> mutator) {
        mutator.accept(cached);
        persist();
        return cached;
    }

    private void persist() {
        try {
            Files.createDirectories(filePath.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(filePath.toFile(), cached);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Seeds one server profile from the current active-connection fields, once, so an existing configured connection isn't lost. */
    public synchronized AppSettings ensureDefaultServer() {
        if (!cached.getServers().isEmpty()) {
            return cached;
        }
        ServerProfile profile = new ServerProfile(
                UUID.randomUUID().toString(),
                "Default",
                cached.getProviderType(),
                cached.getBaseUrl(),
                cached.getApiKey(),
                cached.getMaxHistory());
        return updateSettings(settings -> {
            List<ServerProfile> servers = new ArrayList<>();
            servers.add(profile);
            settings.setServers(servers);
            settings.setActiveServerId(profile.getId());
        });
    }
}
