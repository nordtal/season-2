package eu.nordtal.season.hungergames.game;

import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.database.notify.Notifies;
import eu.nordtal.season.database.registration.Game;
import eu.nordtal.season.database.registration.RegistrationState;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.sqlobject.config.KeyColumn;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.config.ValueColumn;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * The SQL of the games, their events and the rounds of registration. Every write notifies {@link Channel#HUNGER_GAMES}.
 */
@Notifies(Channel.HUNGER_GAMES)
public interface GameDao {

    /** A round of registration that has not ended. */
    record Round(UUID id, RegistrationState state) {}

    /** Returns the round of the Hunger Games that has not ended, if there is one. */
    default Optional<Round> currentRound() {
        return currentRound(Game.HUNGER_GAMES.key());
    }

    @SqlQuery("SELECT id, state FROM registration WHERE game = :game AND state <> 'ENDED'")
    @RegisterConstructorMapper(Round.class)
    Optional<Round> currentRound(@Bind("game") String game);

    /** Returns the current round if it is open. */
    default Optional<UUID> openRound() {
        return currentRound()
                .filter(round -> round.state() == RegistrationState.OPEN)
                .map(Round::id);
    }

    /** Returns the game in {@code COUNTDOWN} or {@code RUNNING}. */
    @SqlQuery("""
            SELECT id, registration_id, state, started, ended, winner_member_id FROM hg_game
            WHERE state IN ('COUNTDOWN', 'RUNNING')
            """)
    @RegisterRowMapper(HgGameMapper.class)
    Optional<HgGame> gameUnderWay();

    /**
     * Creates a game from an open round and closes the round, in one transaction.
     *
     * @return the new game, or empty when the round is not open
     */
    @Transaction
    default Optional<UUID> startGame(final UUID registrationId) {
        if (closeRound(registrationId) == 0) {
            return Optional.empty();
        }
        return Optional.of(insertGame(registrationId));
    }

    @SqlQuery("""
            WITH closed AS (UPDATE registration SET state = 'CLOSED' WHERE id = :id AND state = 'OPEN' RETURNING id)
            SELECT count(*) FROM (SELECT pg_notify(:channel, '') FROM closed) AS notified
            """)
    int closeRound(@Bind("id") UUID registrationId);

    @SqlQuery("""
            WITH inserted AS (
                INSERT INTO hg_game (registration_id, started) VALUES (:registrationId, now()) RETURNING id
            )
            SELECT id FROM (SELECT inserted.id, pg_notify(:channel, '') FROM inserted) AS notified
            """)
    UUID insertGame(@Bind("registrationId") UUID registrationId);

    /** Sets the game to {@code RUNNING}. */
    @SqlQuery("""
            WITH released AS (UPDATE hg_game SET state = 'RUNNING' WHERE id = :id AND state = 'COUNTDOWN' RETURNING id)
            SELECT count(*) FROM (SELECT pg_notify(:channel, '') FROM released) AS notified
            """)
    int release(@Bind("id") UUID id);

    /** Sets the game to {@code DECIDED} and ends its round. */
    @Transaction
    default void decideGame(final UUID id, final @Nullable UUID winnerMemberId) {
        markDecided(id, winnerMemberId);
        endRoundOf(id);
    }

    @SqlQuery("""
            WITH decided AS (
                UPDATE hg_game
                SET state = 'DECIDED', ended = now(), winner_member_id = :winnerMemberId
                WHERE id = :id
                RETURNING id
            )
            SELECT count(*) FROM (SELECT pg_notify(:channel, '') FROM decided) AS notified
            """)
    int markDecided(@Bind("id") UUID id, @Bind("winnerMemberId") @Nullable UUID winnerMemberId);

    @SqlQuery("""
            WITH ended AS (
                UPDATE registration SET state = 'ENDED'
                WHERE id = (SELECT registration_id FROM hg_game WHERE id = :id)
                RETURNING id
            )
            SELECT count(*) FROM (SELECT pg_notify(:channel, '') FROM ended) AS notified
            """)
    int endRoundOf(@Bind("id") UUID gameId);

    /**
     * Aborts the game in {@code COUNTDOWN} or {@code RUNNING} and opens its round again.
     *
     * @return the aborted game, or empty when no game was under way
     */
    @Transaction
    default Optional<HgGame> abortInterrupted() {
        final Optional<HgGame> interrupted = gameUnderWay();
        interrupted.ifPresent(game -> {
            markAborted(game.id());
            reopenRound(game.registrationId());
        });
        return interrupted;
    }

    @SqlQuery("""
            WITH aborted AS (UPDATE hg_game SET state = 'ABORTED', ended = now() WHERE id = :id RETURNING id)
            SELECT count(*) FROM (SELECT pg_notify(:channel, '') FROM aborted) AS notified
            """)
    int markAborted(@Bind("id") UUID id);

    @SqlQuery("""
            WITH reopened AS (UPDATE registration SET state = 'OPEN' WHERE id = :id AND state = 'CLOSED' RETURNING id)
            SELECT count(*) FROM (SELECT pg_notify(:channel, '') FROM reopened) AS notified
            """)
    int reopenRound(@Bind("id") UUID registrationId);

    @SqlQuery("""
            WITH recorded AS (
                INSERT INTO hg_event (game_id, type, actor_id, victim_id)
                VALUES (:gameId, :type, :actorId, :victimId)
                RETURNING id
            )
            SELECT count(*) FROM (SELECT pg_notify(:channel, '') FROM recorded) AS notified
            """)
    int recordEvent(
            @Bind("gameId") UUID gameId,
            @Bind("type") String type,
            @Bind("actorId") @Nullable UUID actorId,
            @Bind("victimId") @Nullable UUID victimId);

    /** Stores a {@code TIE} event with the winner of the tiebreak as the actor, or without an actor. */
    default void recordTie(final UUID gameId, final @Nullable UUID winnerMemberId) {
        recordEvent(gameId, "TIE", winnerMemberId, null);
    }

    /** Returns the number of {@code KILL} events of the member in the game. */
    @SqlQuery("""
            SELECT count(*) FROM hg_event
            WHERE game_id = :gameId AND type = 'KILL' AND actor_id = :actorId
            """)
    int killCount(@Bind("gameId") UUID gameId, @Bind("actorId") UUID actorId);

    /** Returns the number of {@code KILL} events of each member of the game, without members that have none. */
    @SqlQuery("""
            SELECT actor_id, count(*) AS kills FROM hg_event
            WHERE game_id = :gameId AND type = 'KILL' AND actor_id IS NOT NULL
            GROUP BY actor_id
            """)
    @KeyColumn("actor_id")
    @ValueColumn("kills")
    Map<UUID, Integer> killCounts(@Bind("gameId") UUID gameId);
}
