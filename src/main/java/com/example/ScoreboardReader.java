package com.example;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads player statistics directly from the running server's in-memory
 * {@link Scoreboard} via {@link MinecraftServer#getScoreboard()}.
 *
 * This intentionally avoids parsing scoreboard.dat as NBT — because the mod
 * runs server-side, it has live access to the scoreboard object and can read
 * the current values without touching disk.
 *
 * Tracked objectives:
 *   - bac_advancements  (number of advancements earned)
 *   - hc_playTimeShow   (play time, raw unit assumed to be minutes — see README)
 */
public final class ScoreboardReader {

    private static final Logger LOGGER = LoggerFactory.getLogger(StatsExporterMod.MOD_ID);

    // Objective names as they appear in the vanilla scoreboard.
    static final String OBJ_ADVANCEMENTS = "bac_advancements";
    static final String OBJ_PLAY_TIME = "hc_playTimeShow";

    private final MinecraftServer server;

    ScoreboardReader(MinecraftServer server) {
        this.server = server;
    }

    /**
     * Build a map of player name -> stats for all players that have at least
     * one of the tracked objectives set.
     *
     * @return an ordered map (player name -> stats); empty if no data exists
     */
    Map<String, PlayerStats> read() {
        Map<String, PlayerStats> result = new LinkedHashMap<>();
        Scoreboard scoreboard = server.getScoreboard();
        if (scoreboard == null) {
            // No scoreboard yet — return empty rather than erroring.
            return result;
        }

        // Collect scores for each tracked objective independently, then merge
        // by player name. A player may have one objective but not the other.
        collectObjective(scoreboard, OBJ_ADVANCEMENTS, result, true);
        collectObjective(scoreboard, OBJ_PLAY_TIME, result, false);

        return result;
    }

    /**
     * Pull every player's score for a single objective and merge it into the
     * result map. Uses {@link Scoreboard#listPlayerScores(Objective)} which
     * returns one {@link PlayerScoreEntry} per tracked player.
     */
    private void collectObjective(Scoreboard scoreboard, String objectiveName,
                                  Map<String, PlayerStats> result, boolean isAdvancements) {
        Objective objective = scoreboard.getObjective(objectiveName);
        if (objective == null) {
            // Objective not registered yet — nothing to read for this metric.
            return;
        }

        try {
            for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
                String name = entry.owner();
                if (name == null || name.isBlank()) {
                    continue;
                }
                int value = entry.value();

                PlayerStats stats = result.computeIfAbsent(name, PlayerStats::new);
                if (isAdvancements) {
                    stats.advancements = value;
                } else {
                    stats.playTimeShow = value;
                }
            }
        } catch (Throwable t) {
            // Defensive: never let a scoreboard read crash the cache refresh.
            LOGGER.warn("Failed to read objective '{}': {}", objectiveName, t.getMessage());
        }
    }

    /** Simple holder for one player's exported stats. */
    static final class PlayerStats {
        final String name;
        int advancements = 0;
        int playTimeShow = 0;

        PlayerStats(String name) {
            this.name = name;
        }
    }
}
