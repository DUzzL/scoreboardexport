package com.example;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the Stats Exporter mod.
 *
 * The config is stored as a simple JSON file inside the server's config
 * directory. It is loaded on startup. The mod is designed to be generic —
 * server operators choose which scoreboard objectives to expose, whether to
 * hide banned players, and the CORS origin for their website.
 *
 * Config fields:
 *   - port (int): the port the HTTP server listens on. Set to "your_port"
 *     in the default config as a placeholder — operators must replace it.
 *   - cacheIntervalMinutes (int): how often the stats snapshot is recomputed.
 *     Clamped to 5–15.
 *   - allowedOrigin (string): the website origin allowed via CORS.
 *   - objectives (list of strings): the scoreboard objective names to expose.
 *     Each objective becomes a field in the JSON response.
 *   - hideBannedPlayers (bool): if true, banned players are excluded from
 *     the JSON response.
 */
public final class StatsConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(StatsExporterMod.MOD_ID);

    // Default values — kept in code so a missing config file still works.
    static final int DEFAULT_PORT = 8790;
    static final int DEFAULT_CACHE_INTERVAL_MINUTES = 10;
    static final String DEFAULT_ALLOWED_ORIGIN = "*";

    // Default objectives exposed by the mod. Operators can change this list
    // in the config to expose any scoreboard objectives they want.
    static final List<String> DEFAULT_OBJECTIVES = List.of("bac_advancements", "hc_playTimeShow");
    static final boolean DEFAULT_HIDE_BANNED_PLAYERS = false;

    // Allowed range for the cache interval, per spec (5–15 minutes).
    static final int MIN_CACHE_INTERVAL_MINUTES = 5;
    static final int MAX_CACHE_INTERVAL_MINUTES = 15;

    private final Path configPath;
    private volatile int port = DEFAULT_PORT;
    private volatile int cacheIntervalMinutes = DEFAULT_CACHE_INTERVAL_MINUTES;
    private volatile String allowedOrigin = DEFAULT_ALLOWED_ORIGIN;
    private volatile List<String> objectives = new ArrayList<>(DEFAULT_OBJECTIVES);
    private volatile boolean hideBannedPlayers = DEFAULT_HIDE_BANNED_PLAYERS;

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
                config.port = parsePort(root.get("port"));
                config.cacheIntervalMinutes = clampCacheInterval(
                        root.has("cacheIntervalMinutes") ? root.get("cacheIntervalMinutes").getAsInt() : DEFAULT_CACHE_INTERVAL_MINUTES);
                config.allowedOrigin = root.has("allowedOrigin") ? root.get("allowedOrigin").getAsString() : DEFAULT_ALLOWED_ORIGIN;
                config.objectives = parseObjectives(root);
                config.hideBannedPlayers = root.has("hideBannedPlayers") && root.get("hideBannedPlayers").getAsBoolean();
                LOGGER.info("Loaded Stats Exporter config from {}", path);
            } catch (Exception e) {
                LOGGER.warn("Failed to parse config '{}', using defaults and rewriting: {}", path, e.getMessage());
                config.writeDefaults();
            }
        } else {
            LOGGER.info("No Stats Exporter config found at '{}', creating one with defaults", path);
            config.writeDefaults();
        }

        LOGGER.info("Stats Exporter config: port={}, cacheIntervalMinutes={}, allowedOrigin='{}', objectives={}, hideBannedPlayers={}",
                config.port, config.cacheIntervalMinutes, config.allowedOrigin, config.objectives, config.hideBannedPlayers);
        return config;
    }

    /** Parse the port from the config — accepts an int or a numeric string. */
    private static int parsePort(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return DEFAULT_PORT;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            return element.getAsInt();
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            try {
                return Integer.parseInt(element.getAsString().trim());
            } catch (NumberFormatException e) {
                LOGGER.warn("Port '{}' is not a valid number, using default {}", element.getAsString(), DEFAULT_PORT);
                return DEFAULT_PORT;
            }
        }
        return DEFAULT_PORT;
    }

    /** Parse the objectives list from the config JSON. */
    private static List<String> parseObjectives(JsonObject root) {
        if (!root.has("objectives") || !root.get("objectives").isJsonArray()) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        for (JsonElement el : root.getAsJsonArray("objectives")) {
            String name = el.getAsString();
            if (name != null && !name.isBlank()) {
                result.add(name.trim());
            }
        }
        return result;
    }

    /** Write the default config file. */
    private void writeDefaults() {
        String content = """
                {
                  "port": "your_port",
                  "cacheIntervalMinutes": 10,
                  "allowedOrigin": "*",
                  "objectives": [],
                  "hideBannedPlayers": false
                }
                """;
        try {
            Files.writeString(configPath, content);
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

    List<String> objectives() {
        return objectives;
    }

    boolean hideBannedPlayers() {
        return hideBannedPlayers;
    }
}
