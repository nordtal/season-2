package eu.nordtal.s2.steward.ui;

import com.google.gson.JsonObject;
import eu.nordtal.s2.commands.hungergames.HungerGamesCommands;
import eu.nordtal.s2.commands.smp.SmpCommands;
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
 * The SMP's and the hunger games' admin actions, each one row written through {@link CommandApi#submit}.
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

    /** {@code GET /api/smp/track}: the active milestones and their objectives. */
    void track(final Context ctx) {
        final Map<String, List<Map<String, Object>>> objectives = new LinkedHashMap<>();
        try (Connection connection = dataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                     SELECT milestone.key AS milestone, objective.key, objective.type,
                            objective.amount, objective.target, objective.completed IS NOT NULL AS done
                     FROM smp_milestone milestone
                              LEFT JOIN smp_objective objective ON objective.milestone_key = milestone.key
                     WHERE milestone.state = 'ACTIVE'
                     ORDER BY milestone.key, objective.key
                     """);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                final List<Map<String, Object>> list =
                        objectives.computeIfAbsent(rows.getString("milestone"), key -> new ArrayList<>());
                if (rows.getString("key") == null) continue;
                final Map<String, Object> objective = new LinkedHashMap<>();
                objective.put("key", rows.getString("key"));
                objective.put("type", rows.getString("type"));
                objective.put("amount", rows.getLong("amount"));
                objective.put("target", rows.getLong("target"));
                objective.put("completed", rows.getBoolean("done"));
                list.add(objective);
            }
        } catch (final SQLException failure) {
            throw new IllegalStateException("could not read the SMP's track", failure);
        }
        final List<Map<String, Object>> active = new ArrayList<>();
        objectives.forEach((key, list) -> {
            final Map<String, Object> milestone = new LinkedHashMap<>();
            milestone.put("key", key);
            milestone.put("objectives", list);
            active.add(milestone);
        });
        ctx.json(Map.of("active", active));
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
        answer(ctx, commands.submit(ctx, SmpCommands.COMPLETE_OBJECTIVE, arguments("key", key)));
    }

    /** {@code POST /api/smp/milestone} with {@code {key}}, the active milestone. */
    void unlockMilestone(final Context ctx) {
        final String key = key(ctx);
        if (!exists("SELECT 1 FROM smp_milestone WHERE state = 'ACTIVE' AND key = ?", key)) {
            throw new BadRequestResponse(key + " is not the active milestone.");
        }
        answer(ctx, commands.submit(ctx, SmpCommands.UNLOCK_MILESTONE, arguments("key", key)));
    }

    /**
     * {@code GET /api/hunger-games/round}: the open round's state, or empty when none is open.
     *
     * The count is players on the roster, not resolved participants.
     */
    void round(final Context ctx) {
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
        ctx.json(answer);
    }

    /**
     * {@code POST /api/hunger-games/start} with {@code {confirm}}, true only after the browser has shown the numbers.
     */
    void startRound(final Context ctx) {
        final JsonObject body = body(ctx);
        final boolean confirm = body.has("confirm")
                && body.get("confirm").isJsonPrimitive()
                && body.get("confirm").getAsBoolean();
        answer(ctx, commands.submit(ctx, HungerGamesCommands.START, confirm ? arguments("confirm", "confirm") : null));
    }

    private static void answer(final Context ctx, final long id) {
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("id", String.valueOf(id));
        answer.put("status", "PENDING");
        ctx.status(202).json(answer);
    }

    private static JsonObject arguments(final String name, final String value) {
        final JsonObject arguments = new JsonObject();
        arguments.addProperty(name, value);
        return arguments;
    }

    private static JsonObject body(final Context ctx) {
        try {
            return com.google.gson.JsonParser.parseString(ctx.body()).getAsJsonObject();
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
