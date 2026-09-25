package eu.nordtal.s2.steward.ui;

import com.google.gson.JsonObject;
import eu.nordtal.s2.commands.hungergames.HungerGamesCommands;
import eu.nordtal.s2.commands.smp.SmpCommands;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import org.jetbrains.annotations.NotNull;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The SMP's and the hunger games' admin actions, as the service pages draw them.
 *
 * <p><b>The transport is the command row and nothing else.</b> A Paper server is reachable from
 * here only through {@code command_request}, so each action is one row written through
 * {@link CommandApi#submit} and answered through {@code GET /api/commands/{id}}, exactly as the
 * generic card did. What changed is only what the browser sends: a milestone or an objective
 * picked from a list, never a command name or an argument field.</p>
 *
 * <p><b>The list is the SMP's own progress rows.</b> {@code smp_milestone} and {@code smp_objective}
 * are written by the running server from its {@code milestones.yml}, so they are the track as the
 * server holds it - not the file in the volume, which can be edited ahead of a reload. Only what
 * can be acted on is offered and accepted: the open objectives of an active milestone, and an
 * active milestone. Unlocking one further down the track by hand skips the ones before it.</p>
 */
final class GameActions {

    private final DataSource dataSource;
    private final CommandApi commands;

    /** @param dataSource null in a test that runs without a database, which never calls these */
    GameActions(final DataSource dataSource, final @NotNull CommandApi commands) {
        this.dataSource = dataSource;
        this.commands = commands;
    }

    /** {@code GET /api/smp/track} - the active milestones and their objectives. */
    void track(final @NotNull Context ctx) {
        final Map<String, List<Map<String, Object>>> objectives = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
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

    /** {@code POST /api/smp/objective} - {@code {key}}, an open objective of the active milestone. */
    void completeObjective(final @NotNull Context ctx) {
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

    /** {@code POST /api/smp/milestone} - {@code {key}}, the active milestone. */
    void unlockMilestone(final @NotNull Context ctx) {
        final String key = key(ctx);
        if (!exists("SELECT 1 FROM smp_milestone WHERE state = 'ACTIVE' AND key = ?", key)) {
            throw new BadRequestResponse(key + " is not the active milestone.");
        }
        answer(ctx, commands.submit(ctx, SmpCommands.UNLOCK_MILESTONE, arguments("key", key)));
    }

    /**
     * {@code GET /api/hunger-games/round} - the open round's state and how many have registered,
     * or an empty object when no round is open.
     *
     * <p>What tells the page whether a start went through. The server's answer to {@code /hg start}
     * is a sentence either way, and a round that is still {@code REGISTRATION} after it is one that
     * did not start - which is when "start anyway" is worth offering. The count is players on the
     * roster, not the server's resolved participants, and the page says "registered".</p>
     */
    void round(final @NotNull Context ctx) {
        final Map<String, Object> answer = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
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
     * {@code POST /api/hunger-games/start} - {@code {confirm}}.
     *
     * <p>{@code confirm: true} is the second step {@code /hg start} asks for itself when fewer than
     * the recommended number are registered, and answers with the numbers. The browser sends it
     * only after it has shown that answer, never on the first press.</p>
     */
    void startRound(final @NotNull Context ctx) {
        final JsonObject body = body(ctx);
        final boolean confirm = body.has("confirm") && body.get("confirm").isJsonPrimitive()
                && body.get("confirm").getAsBoolean();
        answer(ctx, commands.submit(ctx, HungerGamesCommands.START,
                confirm ? arguments("confirm", "confirm") : null));
    }

    // ---------------------------------------------------------------------------------------

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
        if (!body.has("key") || !body.get("key").isJsonPrimitive()
                || body.get("key").getAsString().isBlank()) {
            throw new BadRequestResponse("key is the one to act on.");
        }
        return body.get("key").getAsString().trim();
    }

    private boolean exists(final String sql, final String parameter) {
        try (Connection connection = dataSource.getConnection();
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
