package com.example;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lightweight embedded HTTP server that exposes the cached player stats
 * snapshot at {@code GET /api/stats}.
 *
 * The snapshot is recomputed on a fixed schedule (every cacheIntervalMinutes)
 * by the {@link ScoreboardReader}, and HTTP requests simply serve the cached
 * JSON. This keeps request handling cheap even under load.
 *
 * CORS is configured to allow the configured website origin to fetch the
 * endpoint cross-origin.
 */
public final class StatsHttpServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(StatsExporterMod.MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final StatsConfig config;
    private final ScoreboardReader reader;
    private final AtomicReference<String> cachedJson = new AtomicReference<>("");

    // Simple per-second rate limiter: allows up to MAX_REQUESTS_PER_SECOND
    // requests per second before returning 429 Too Many Requests.
    private static final int MAX_REQUESTS_PER_SECOND = 30;
    private final AtomicInteger requestCounter = new AtomicInteger(0);

    private HttpServer server;
    private ScheduledExecutorService scheduler;

    StatsHttpServer(StatsConfig config, ScoreboardReader reader) {
        this.config = config;
        this.reader = reader;
    }

    /** Start the HTTP server and schedule the periodic cache refresh. */
    void start() {
        // Compute an initial snapshot so the first request doesn't hit an empty cache.
        refreshCache();

        try {
            server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        } catch (IOException e) {
            LOGGER.error("Failed to bind Stats Exporter HTTP server on port {}: {}", config.port(), e.getMessage());
            LOGGER.error("Make sure the port is open as an additional allocation in your Pterodactyl/Folium panel.");
            return;
        }

        server.createContext("/api/stats", new StatsHandler());
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
        LOGGER.info("Stats Exporter HTTP server listening on port {} (endpoint: GET /api/stats)", config.port());

        // Schedule periodic cache refresh aligned with the configured interval.
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "statsexporter-cache");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::refreshCache, config.cacheIntervalMinutes(),
                config.cacheIntervalMinutes(), TimeUnit.MINUTES);

        // Reset the per-second request counter every second.
        scheduler.scheduleAtFixedRate(() -> requestCounter.set(0), 1, 1, TimeUnit.SECONDS);

        LOGGER.info("Stats cache will refresh every {} minute(s)", config.cacheIntervalMinutes());
    }

    /** Stop the HTTP server and the cache scheduler. */
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (server != null) {
            server.stop(0);
            LOGGER.info("Stats Exporter HTTP server stopped");
        }
    }

    /** Recompute the stats snapshot from the live scoreboard and cache it as JSON. */
    private void refreshCache() {
        try {
            Map<String, ScoreboardReader.PlayerStats> data = reader.read();
            String json = buildJson(data);
            cachedJson.set(json);
            LOGGER.info("Refreshed stats cache: {} player(s)", data.size());
        } catch (Throwable t) {
            // Never let a cache refresh crash the scheduler thread.
            LOGGER.error("Failed to refresh stats cache: {}", t.getMessage());
        }
    }

    /** Build the JSON response string from the current scoreboard data. */
    private String buildJson(Map<String, ScoreboardReader.PlayerStats> data) {
        List<PlayerJson> players = data.values().stream()
                .map(p -> new PlayerJson(p.name, p.advancements, p.playTimeShow))
                .toList();

        StatsJson root = new StatsJson(
                DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                players);
        return GSON.toJson(root);
    }

    /** HTTP handler for GET /api/stats — serves the cached JSON snapshot. */
    private final class StatsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Only GET is supported; respond to OPTIONS for CORS preflight.
            String method = exchange.getRequestMethod();
            if ("OPTIONS".equalsIgnoreCase(method)) {
                applyCorsHeaders(exchange);
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            if (!"GET".equalsIgnoreCase(method)) {
                applyCorsHeaders(exchange);
                byte[] body = "Method Not Allowed\n".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(405, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
                exchange.close();
                return;
            }

            // Rate limiting: reject requests beyond the per-second cap.
            if (requestCounter.incrementAndGet() > MAX_REQUESTS_PER_SECOND) {
                applyCorsHeaders(exchange);
                byte[] body = "{\"error\":\"rate limited\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(429, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
                exchange.close();
                return;
            }

            byte[] body = cachedJson.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            applyCorsHeaders(exchange);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
            exchange.close();
        }

        /** Apply CORS response headers. Must be called before sendResponseHeaders. */
        private void applyCorsHeaders(HttpExchange exchange) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", config.allowedOrigin());
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            exchange.getResponseHeaders().set("Access-Control-Max-Age", "600");
        }
    }

    // ── JSON DTOs ──────────────────────────────────────────────────────

    /** Top-level JSON object. */
    private static final class StatsJson {
        final String lastUpdated;
        final List<PlayerJson> players;

        StatsJson(String lastUpdated, List<PlayerJson> players) {
            this.lastUpdated = lastUpdated;
            this.players = players;
        }
    }

    /** Per-player JSON object. */
    private static final class PlayerJson {
        final String name;
        final int bac_advancements;
        final int hc_playTimeShow;

        PlayerJson(String name, int advancements, int playTimeShow) {
            this.name = name;
            this.bac_advancements = advancements;
            this.hc_playTimeShow = playTimeShow;
        }
    }
}
