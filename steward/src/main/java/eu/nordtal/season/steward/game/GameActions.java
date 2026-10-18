package eu.nordtal.season.steward.game;

import com.google.gson.JsonObject;
import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.inbox.HungerGamesRequest;
import eu.nordtal.season.database.inbox.ServerRefusal;
import eu.nordtal.season.database.inbox.SmpRequest;
import eu.nordtal.season.database.registration.Game;
import eu.nordtal.season.messages.Refusal;
import eu.nordtal.season.messages.Refused;
import eu.nordtal.season.steward.texts.RequestRefused;
import eu.nordtal.season.steward.texts.StewardTexts;
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

/** The admin actions for the SMP and the Hunger Games, each written as a request through {@link CommandApi}. */
public final class GameActions {

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

    private final @Nullable DataSource dataSource;
    private final CommandApi commands;

    public GameActions(final @Nullable DataSource dataSource, final CommandApi commands) {
        this.dataSource = dataSource;
        this.commands = commands;
    }

    private DataSource dataSource() {
        return Objects.requireNonNull(dataSource, "no database - this route is not available without one");
    }

    /** {@code GET /api/smp/track} answers every milestone with its state and objectives. */
    public void track(final Context ctx) {
        ctx.json(readTrack());
    }

    /** Every milestone that has a row in {@code smp_milestone}, unlocked first. */
    public record SmpTrack(List<SmpMilestone> milestones) {}

    /** One milestone. {@code state} is {@code LOCKED}, {@code ACTIVE} or {@code UNLOCKED}. */
    public record SmpMilestone(
            String key, String state, @Nullable Instant unlocked, List<SmpObjective> objectives) {}

    /** One objective. {@code type} is {@code HAND_IN}, {@code STATISTIC} or {@code ADVANCEMENT}. */
    public record SmpObjective(
            String key,
            String type,
            long amount,
            long target,
            boolean completed,
            @Nullable Instant completedAt) {}

    /** The state and registered player count of the open round, both {@code null} when no round is open. */
    public record HungerGamesRound(
            @Nullable String state, @Nullable Long registered) {}

    /** The id and status of a request in a server's inbox. */
    public record CommandAsked(String id, String status) {}

    public SmpTrack readTrack() {
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

    /** {@code POST /api/smp/objective} with {@code {key}} completes an open objective of the active milestone. */
    public void completeObjective(final Context ctx) {
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
                        new JournalDetail.ObjectiveCompleted(key)));
    }

    /** {@code POST /api/smp/milestone} with {@code {key}} unlocks the active milestone. */
    public void unlockMilestone(final Context ctx) {
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
                        new JournalDetail.MilestoneUnlocked(key)));
    }

    private static Refused refused(final Refusal refusal) {
        return new Refused(refusal.reason(), refusal.message());
    }

    /** {@code GET /api/hunger-games/round} answers the open round. The count covers members in state OWNER or ACCEPTED. */
    public void round(final Context ctx) {
        ctx.json(readRound());
    }

    /** Returns the open round. Its state is the game's state while a game runs, else {@code REGISTRATION}. */
    public HungerGamesRound readRound() {
        try (Connection connection = dataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                     SELECT coalesce(game.state, CASE round.state WHEN 'OPEN' THEN 'REGISTRATION' ELSE round.state END)
                                AS state,
                            (SELECT count(*) FROM team_member member
                             WHERE member.registration_id = round.id
                               AND member.state IN ('OWNER', 'ACCEPTED')) AS registered
                     FROM registration round
                              LEFT JOIN hg_game game
                                        ON game.registration_id = round.id AND game.state IN ('COUNTDOWN', 'RUNNING')
                     WHERE round.game = ? AND round.state <> 'ENDED'
                     """)) {
            statement.setString(1, Game.HUNGER_GAMES.key());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next()
                        ? new HungerGamesRound(rows.getString("state"), rows.getLong("registered"))
                        : new HungerGamesRound(null, null);
            }
        } catch (final SQLException failure) {
            throw new IllegalStateException("could not read the hunger games round", failure);
        }
    }

    /** {@code POST /api/hunger-games/start} with {@code {confirm}} starts the game. */
    public void startRound(final Context ctx) {
        final JsonObject body = body(ctx);
        final boolean confirm = body.has("confirm")
                && body.get("confirm").isJsonPrimitive()
                && body.get("confirm").getAsBoolean();
        answer(ctx, commands.submit(ctx, new HungerGamesRequest.StartGame(confirm), JournalAction.START_GAME, null));
    }

    /** Answers with status 202 and the pending request {@code id}. */
    public static void answer(final Context ctx, final String id) {
        ctx.status(202).json(new CommandAsked(id, "PENDING"));
    }

    private static JsonObject body(final Context ctx) {
        try {
            return eu.nordtal.season.common.json.Json.tree(ctx.body()).getAsJsonObject();
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
