package eu.nordtal.s2.steward.web;

import com.google.gson.JsonObject;
import eu.nordtal.s2.database.inbox.HungerGamesRequest;
import eu.nordtal.s2.database.inbox.SmpRequest;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * The SMP's and the hunger games' admin actions, each one typed request written through {@link CommandApi}.
 *
 * The track is read from {@code smp_milestone} and {@code smp_objective}, as the running server holds it.
 */
final class GameActions {

    private final @Nullable DataSource dataSource;
    private final CommandApi commands;

    GameActions(final @Nullable DataSource dataSource, final CommandApi commands) {
        this.dataSource = dataSource;
        this.commands = commands;
    }

    private DataSource dataSource() {
        return Objects.requireNonNull(dataSource, "no database - this route is not available without one");
    }

    /** {@code GET /api/smp/track}: every milestone with its state and objectives; the page orders them by the file. */
    void track(final Context ctx) {
        ctx.json(readTrack());
    }

    /** The whole track as {@code GET /api/smp/track} answers. */
    Map<String, Object> readTrack() {
        final Map<String, Map<String, Object>> milestones = new LinkedHashMap<>();
        try (Connection connection = dataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                     SELECT milestone.key AS milestone, milestone.state, milestone.unlocked,
                            objective.key, objective.type, objective.amount, objective.target,
                            objective.completed
                     FROM smp_milestone milestone
                              LEFT JOIN smp_objective objective ON objective.milestone_key = milestone.key
                     ORDER BY CASE milestone.state WHEN 'UNLOCKED' THEN 0 WHEN 'ACTIVE' THEN 1 ELSE 2 END,
                              milestone.unlocked, milestone.key, objective.key
                     """);
                ResultSet rows = statement.executeQuery()) {
            final Map<String, List<Map<String, Object>>> objectives = new LinkedHashMap<>();
            while (rows.next()) {
                final String key = rows.getString("milestone");
                final String state = rows.getString("state");
                final java.sql.@Nullable Timestamp unlocked = rows.getTimestamp("unlocked");
                final List<Map<String, Object>> list = objectives.computeIfAbsent(key, fresh -> {
                    final Map<String, Object> milestone = new LinkedHashMap<>();
                    milestone.put("key", fresh);
                    milestone.put("state", state);
                    putInstant(milestone, "unlocked", unlocked);
                    final List<Map<String, Object>> created = new ArrayList<>();
                    milestone.put("objectives", created);
                    milestones.put(fresh, milestone);
                    return created;
                });
                if (rows.getString("key") == null) continue;
                final Map<String, Object> objective = new LinkedHashMap<>();
                objective.put("key", rows.getString("key"));
                objective.put("type", rows.getString("type"));
                objective.put("amount", rows.getLong("amount"));
                objective.put("target", rows.getLong("target"));
                final java.sql.@Nullable Timestamp completed = rows.getTimestamp("completed");
                objective.put("completed", completed != null);
                putInstant(objective, "completedAt", completed);
                list.add(objective);
            }
        } catch (final SQLException failure) {
            throw new IllegalStateException("could not read the SMP's track", failure);
        }
        return Map.of("milestones", new ArrayList<>(milestones.values()));
    }

    private static void putInstant(
            final Map<String, Object> into, final String key, final java.sql.@Nullable Timestamp at) {
        if (at != null) into.put(key, at.toInstant().toString());
    }

    /** {@code POST /api/smp/objective} with {@code {key}}, an open objective of the active milestone. */
    void completeObjective(final Context ctx) {
        final String key = key(ctx);
        if (!exists("""
                SELECT 1 FROM smp_objective objective
                         JOIN smp_milestone milestone ON milestone.key = objective.milestone_key
                WHERE milestone.state = 'ACTIVE' AND objective.key = ? AND objective.completed IS NULL
                """, key)) {
            throw new BadRequestResponse(key + " is not an open objective of the active milestone.");
        }
        answer(ctx, commands.submit(ctx, new SmpRequest.CompleteObjective(key), "objective " + key));
    }

    /** {@code POST /api/smp/milestone} with {@code {key}}, the active milestone. */
    void unlockMilestone(final Context ctx) {
        final String key = key(ctx);
        if (!exists("SELECT 1 FROM smp_milestone WHERE state = 'ACTIVE' AND key = ?", key)) {
            throw new BadRequestResponse(key + " is not the active milestone.");
        }
        answer(ctx, commands.submit(ctx, new SmpRequest.UnlockMilestone(key), "milestone " + key));
    }

    /**
     * {@code GET /api/hunger-games/round}: the open round's state, or empty when none is open.
     *
     * The count is players on the roster, not resolved participants.
     */
    void round(final Context ctx) {
        ctx.json(readRound());
    }

    /** The open round as {@code GET /api/hunger-games/round} answers. */
    Map<String, Object> readRound() {
        final Map<String, Object> answer = new LinkedHashMap<>();
        try (Connection connection = dataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                     SELECT game.state, (SELECT count(*) FROM hg_member member
                                         WHERE member.game_id = game.id) AS registered
                     FROM hg_game game
                     WHERE game.state <> 'DECIDED'
                     """);
                ResultSet rows = statement.executeQuery()) {
            if (rows.next()) {
                answer.put("state", rows.getString("state"));
                answer.put("registered", rows.getLong("registered"));
            }
        } catch (final SQLException failure) {
            throw new IllegalStateException("could not read the hunger games round", failure);
        }
        return answer;
    }

    /**
     * {@code POST /api/hunger-games/start} with {@code {confirm}}, true only after the browser has shown the numbers.
     */
    void startRound(final Context ctx) {
        final JsonObject body = body(ctx);
        final boolean confirm = body.has("confirm")
                && body.get("confirm").isJsonPrimitive()
                && body.get("confirm").getAsBoolean();
        answer(ctx, commands.submit(ctx, new HungerGamesRequest.StartGame(confirm), "the hunger games start"));
    }

    private static void answer(final Context ctx, final String id) {
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("id", id);
        answer.put("status", "PENDING");
        ctx.status(202).json(answer);
    }

    private static JsonObject body(final Context ctx) {
        try {
            return eu.nordtal.s2.common.json.Json.tree(ctx.body()).getAsJsonObject();
        } catch (final RuntimeException malformed) {
            throw new BadRequestResponse("The body is not the JSON this endpoint takes.");
        }
    }

    private static String key(final Context ctx) {
        final JsonObject body = body(ctx);
        if (!body.has("key")
                || !body.get("key").isJsonPrimitive()
                || body.get("key").getAsString().isBlank()) {
            throw new BadRequestResponse("key is the one to act on.");
        }
        return body.get("key").getAsString().trim();
    }

    private boolean exists(final String sql, final String parameter) {
        try (Connection connection = dataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        } catch (final SQLException failure) {
            throw new IllegalStateException("could not read the SMP's track", failure);
        }
    }
}
