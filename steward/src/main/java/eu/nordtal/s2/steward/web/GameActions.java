package eu.nordtal.s2.steward.web;

import static eu.nordtal.s2.database.AdminTexts.TEXTS;

import com.google.gson.JsonObject;
import eu.nordtal.s2.database.audit.JournalAction;
import eu.nordtal.s2.database.inbox.HungerGamesRequest;
import eu.nordtal.s2.database.inbox.ServerRefusal;
import eu.nordtal.s2.database.inbox.SmpRequest;
import eu.nordtal.s2.messages.Refusal;
import eu.nordtal.s2.messages.Refused;
import eu.nordtal.s2.steward.texts.RequestRefused;
import eu.nordtal.s2.steward.texts.StewardTexts;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * The SMP's and the hunger games' admin actions, each one typed request written through {@link CommandApi}.
 *
 * The track is read from {@code smp_milestone} and {@code smp_objective}, as the running server holds it.
 */
final class GameActions {

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

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

    /** {@code GET /api/smp/track}: every milestone the SMP wrote a row for, unlocked first. */
    public record SmpTrack(List<SmpMilestone> milestones) {}

    /** One milestone; {@code state} is the column's word, {@code LOCKED}, {@code ACTIVE} or {@code UNLOCKED}. */
    public record SmpMilestone(
            String key, String state, @Nullable Instant unlocked, List<SmpObjective> objectives) {}

    /** One objective; {@code type} is {@code HAND_IN}, {@code STATISTIC} or {@code ADVANCEMENT}. */
    public record SmpObjective(
            String key,
            String type,
            long amount,
            long target,
            boolean completed,
            @Nullable Instant completedAt) {}

    /** {@code GET /api/hunger-games/round}: the open round's state and roster size, both absent when none is open. */
    public record HungerGamesRound(
            @Nullable String state, @Nullable Long registered) {}

    /** A request written into a server's inbox, which the server answers on its own time. */
    public record CommandAsked(String id, String status) {}

    /** The whole track as {@code GET /api/smp/track} answers. */
    SmpTrack readTrack() {
        final Map<String, SmpMilestone> milestones = new LinkedHashMap<>();
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
            while (rows.next()) {
                final String key = rows.getString("milestone");
                final String state = rows.getString("state");
                final @Nullable Instant unlocked = instant(rows.getTimestamp("unlocked"));
                final SmpMilestone milestone = milestones.computeIfAbsent(
                        key, fresh -> new SmpMilestone(fresh, state, unlocked, new ArrayList<>()));
                if (rows.getString("key") == null) continue;
                final @Nullable Instant completed = instant(rows.getTimestamp("completed"));
                milestone
                        .objectives()
                        .add(new SmpObjective(
                                rows.getString("key"),
                                rows.getString("type"),
                                rows.getLong("amount"),
                                rows.getLong("target"),
                                completed != null,
                                completed));
            }
        } catch (final SQLException failure) {
            throw new IllegalStateException("could not read the SMP's track", failure);
        }
        return new SmpTrack(List.copyOf(milestones.values()));
    }

    private static @Nullable Instant instant(final java.sql.@Nullable Timestamp at) {
        return at == null ? null : at.toInstant();
    }

    /**
     * {@code POST /api/smp/objective} with {@code {key}}, an open objective of the active milestone.
     *
     * A stale key is refused at once, with the reason and the words the SMP's own refusal carries.
     */
    void completeObjective(final Context ctx) {
        final String key = key(ctx);
        if (!exists("""
                SELECT 1 FROM smp_objective objective
                         JOIN smp_milestone milestone ON milestone.key = objective.milestone_key
                WHERE milestone.state = 'ACTIVE' AND objective.key = ? AND objective.completed IS NULL
                """, key)) {
            throw refused(
                    activeMilestone().isEmpty()
                            ? ServerRefusal.NO_ACTIVE_MILESTONE.with()
                            : ServerRefusal.NO_SUCH_OBJECTIVE.with(key));
        }
        answer(
                ctx,
                commands.submit(
                        ctx,
                        new SmpRequest.CompleteObjective(key),
                        JournalAction.COMPLETE_OBJECTIVE,
                        TEXTS.journal().completeObjective(key)));
    }

    /** {@code POST /api/smp/milestone} with {@code {key}}, the active milestone, refused as the SMP refuses it. */
    void unlockMilestone(final Context ctx) {
        final String key = key(ctx);
        final Optional<String> active = activeMilestone();
        if (active.isEmpty()) {
            throw refused(ServerRefusal.NO_ACTIVE_MILESTONE.with());
        }
        if (!active.get().equals(key)) {
            throw refused(ServerRefusal.MILESTONE_NOT_ACTIVE.with(key, active.get()));
        }
        answer(
                ctx,
                commands.submit(
                        ctx,
                        new SmpRequest.UnlockMilestone(key),
                        JournalAction.UNLOCK_MILESTONE,
                        TEXTS.journal().unlockMilestone(key)));
    }

    private static Refused refused(final Refusal refusal) {
        return new Refused(refusal.reason(), refusal.message());
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
    HungerGamesRound readRound() {
        try (Connection connection = dataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                     SELECT game.state, (SELECT count(*) FROM hg_member member
                                         WHERE member.game_id = game.id) AS registered
                     FROM hg_game game
                     WHERE game.state <> 'DECIDED'
                     """);
                ResultSet rows = statement.executeQuery()) {
            return rows.next()
                    ? new HungerGamesRound(rows.getString("state"), rows.getLong("registered"))
                    : new HungerGamesRound(null, null);
        } catch (final SQLException failure) {
            throw new IllegalStateException("could not read the hunger games round", failure);
        }
    }

    /**
     * {@code POST /api/hunger-games/start} with {@code {confirm}}, true only after the browser has shown the numbers.
     */
    void startRound(final Context ctx) {
        final JsonObject body = body(ctx);
        final boolean confirm = body.has("confirm")
                && body.get("confirm").isJsonPrimitive()
                && body.get("confirm").getAsBoolean();
        answer(
                ctx,
                commands.submit(
                        ctx,
                        new HungerGamesRequest.StartGame(confirm),
                        JournalAction.START_GAME,
                        TEXTS.journal().startGame()));
    }

    private static void answer(final Context ctx, final String id) {
        ctx.status(202).json(new CommandAsked(id, "PENDING"));
    }

    private static JsonObject body(final Context ctx) {
        try {
            return eu.nordtal.s2.common.json.Json.tree(ctx.body()).getAsJsonObject();
        } catch (final RuntimeException malformed) {
            throw new RequestRefused(400, ANSWER.notJson());
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

    private Optional<String> activeMilestone() {
        try (Connection connection = dataSource().getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT key FROM smp_milestone WHERE state = 'ACTIVE'");
                ResultSet rows = statement.executeQuery()) {
            return rows.next() ? Optional.of(rows.getString("key")) : Optional.empty();
        } catch (final SQLException failure) {
            throw new IllegalStateException("could not read the SMP's track", failure);
        }
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
