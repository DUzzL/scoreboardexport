package com.example;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuration for the Stats Exporter mod.
 *
 * The config is stored as a simple JSON file inside the server's config
 * directory. It is loaded on startup and re-read on every call so that
 * operators can tweak values without a full restart (the HTTP server port
 * change still requires a restart, but cache interval and CORS origin take
 * effect on the next cache refresh).
 */
public final class StatsConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(StatsExporterMod.MOD_ID);

    // Default values — kept in code so a missing config file still works.
    static final int DEFAULT_PORT = 8790;
    static final int DEFAULT_CACHE_INTERVAL_MINUTES = 10;
    static final String DEFAULT_ALLOWED_ORIGIN = "https://web.xaprosmp.xyz";

    // Allowed range for the cache interval, per spec (5–15 minutes).
    static final int MIN_CACHE_INTERVAL_MINUTES = 5;
    static final int MAX_CACHE_INTERVAL_MINUTES = 15;

    private final Path configPath;
    private volatile int port = DEFAULT_PORT;
    private volatile int cacheIntervalMinutes = DEFAULT_CACHE_INTERVAL_MINUTES;
    private volatile String allowedOrigin = DEFAULT_ALLOWED_ORIGIN;

    private StatsConfig(Path configPath) {
        this.configPath = configPath;
    }

    /**
     * Load (or create) the config file from the given directory.
     *
     * @param configDir the server's config directory
     * @return a loaded StatsConfig instance
     */
    static StatsConfig load(Path configDir) {
        try {
            Files.createDirectories(configDir);
        } catch (IOException e) {
            LOGGER.warn("Could not create config directory '{}': {}", configDir, e.getMessage());
        }

        Path path = configDir.resolve("statsexporter.json");
        StatsConfig config = new StatsConfig(path);

        if (Files.exists(path)) {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                config.port = root.has("port") ? root.get("port").getAsInt() : DEFAULT_PORT;
                config.cacheIntervalMinutes = clampCacheInterval(
                        root.has("cacheIntervalMinutes") ? root.get("cacheIntervalMinutes").getAsInt() : DEFAULT_CACHE_INTERVAL_MINUTES);
                config.allowedOrigin = root.has("allowedOrigin") ? root.get("allowedOrigin").getAsString() : DEFAULT_ALLOWED_ORIGIN;
                LOGGER.info("Loaded Stats Exporter config from {}", path);
            } catch (Exception e) {
                LOGGER.warn("Failed to parse config '{}', using defaults and rewriting: {}", path, e.getMessage());
                config.writeDefaults();
            }
        } else {
            LOGGER.info("No Stats Exporter config found at '{}', creating one with defaults", path);
            config.writeDefaults();
        }

        LOGGER.info("Stats Exporter config: port={}, cacheIntervalMinutes={}, allowedOrigin='{}'",
                config.port, config.cacheIntervalMinutes, config.allowedOrigin);
        return config;
    }

    /** Rewrite the config file with the current in-memory defaults. */
    private void writeDefaults() {
        JsonObject root = new JsonObject();
        root.addProperty("port", port);
        root.addProperty("cacheIntervalMinutes", cacheIntervalMinutes);
        root.addProperty("allowedOrigin", allowedOrigin);
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try {
            Files.writeString(configPath, gson.toJson(root));
        } catch (IOException e) {
            LOGGER.warn("Could not write config file '{}': {}", configPath, e.getMessage());
        }
    }

    /** Clamp the cache interval to the allowed 5–15 minute range. */
    private static int clampCacheInterval(int minutes) {
        if (minutes < MIN_CACHE_INTERVAL_MINUTES) {
            LOGGER.warn("cacheIntervalMinutes {} is below minimum {}, clamping to {}", minutes, MIN_CACHE_INTERVAL_MINUTES, MIN_CACHE_INTERVAL_MINUTES);
            return MIN_CACHE_INTERVAL_MINUTES;
        }
        if (minutes > MAX_CACHE_INTERVAL_MINUTES) {
            LOGGER.warn("cacheIntervalMinutes {} is above maximum {}, clamping to {}", minutes, MAX_CACHE_INTERVAL_MINUTES, MAX_CACHE_INTERVAL_MINUTES);
            return MAX_CACHE_INTERVAL_MINUTES;
        }
        return minutes;
    }

    int port() {
        return port;
    }

    int cacheIntervalMinutes() {
        return cacheIntervalMinutes;
    }

    String allowedOrigin() {
        return allowedOrigin;
    }
}
