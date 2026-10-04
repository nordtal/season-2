package eu.nordtal.s2.hungergames.db;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.registration.Game;
import eu.nordtal.s2.database.registration.RegistrationState;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.sqlobject.config.KeyColumn;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.config.ValueColumn;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.sqlobject.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * The whole SQL surface of this plugin: its games and events, and the round of registration it starts them from.
 *
 * The round, its teams and members are discord-bot's. This plugin reads them and moves the round's state alone.
 */
public interface HungerGamesDao {

    /** Every active member of a round: on a team as its owner or accepted, never a pending invite. */
    String ROSTER = """
            SELECT member.id AS member_id, member.team_id, team.name AS team_name,
                   ready.member_id IS NOT NULL AS ready, link.mc_uuid
            FROM team_member member
                     JOIN team ON team.id = member.team_id
                     LEFT JOIN hg_ready ready ON ready.member_id = member.id
                     LEFT JOIN account_link link ON link.discord_id = member.discord_id
            WHERE member.state IN ('OWNER', 'ACCEPTED')
            """;

    /** The roster of the round a game was started from. */
    String GAME_ROSTER = ROSTER + """
             AND member.registration_id = (SELECT registration_id FROM hg_game WHERE id = :gameId)
            """;

    /** A round of registration that has not ended. */
    record Round(UUID id, RegistrationState state) {}

    /** Returns the round of the Hunger Games that has not ended, if there is one. */
    default Optional<Round> currentRound() {
        return currentRound(Game.HUNGER_GAMES.key());
    }

    @SqlQuery("SELECT id, state FROM registration WHERE game = :game AND state <> 'ENDED'")
    @RegisterConstructorMapper(Round.class)
    Optional<Round> currentRound(@Bind("game") String game);

    /** Returns the round the lobby waits in, which only an open one is. */
    default Optional<UUID> openRound() {
        return currentRound()
                .filter(round -> round.state() == RegistrationState.OPEN)
                .map(Round::id);
    }

    /** Returns the game in its countdown or running, of which the schema allows one. */
    @SqlQuery("""
            SELECT id, registration_id, state, started, ended, winner_member_id FROM hg_game
            WHERE state IN ('COUNTDOWN', 'RUNNING')
            """)
    @RegisterRowMapper(HgGameMapper.class)
    Optional<HgGame> gameUnderWay();

    /**
     * Starts a game from an open round and closes the round to the bot, in one transaction.
     *
     * @return the new game, or empty when the round was no longer open
     */
    @Transaction
    default Optional<UUID> startGame(final UUID registrationId) {
        if (closeRound(registrationId) == 0) {
            return Optional.empty();
        }
        return Optional.of(insertGame(registrationId));
    }

    @SqlUpdate("UPDATE registration SET state = 'CLOSED' WHERE id = :id AND state = 'OPEN'")
    int closeRound(@Bind("id") UUID registrationId);

    @SqlQuery("INSERT INTO hg_game (registration_id, started) VALUES (:registrationId, now()) RETURNING id")
    UUID insertGame(@Bind("registrationId") UUID registrationId);

    /** Marks the end of the countdown. */
    @SqlUpdate("UPDATE hg_game SET state = 'RUNNING' WHERE id = :id AND state = 'COUNTDOWN'")
    void release(@Bind("id") UUID id);

    /** Decides a game and ends its round, so that the next registration opens a new one. */
    @Transaction
    default void decideGame(final UUID id, final @Nullable UUID winnerMemberId) {
        markDecided(id, winnerMemberId);
        endRoundOf(id);
    }

    @SqlUpdate("""
            UPDATE hg_game
            SET state = 'DECIDED', ended = now(), winner_member_id = :winnerMemberId
            WHERE id = :id
            """)
    void markDecided(@Bind("id") UUID id, @Bind("winnerMemberId") @Nullable UUID winnerMemberId);

    @SqlUpdate("""
            UPDATE registration SET state = 'ENDED' WHERE id = (SELECT registration_id FROM hg_game WHERE id = :id)
            """)
    void endRoundOf(@Bind("id") UUID gameId);

    /**
     * Aborts the game a restart interrupted and opens its round again, with its teams, for an admin to start anew.
     *
     * @return the game aborted, or empty when none was under way
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

    @SqlUpdate("UPDATE hg_game SET state = 'ABORTED', ended = now() WHERE id = :id")
    void markAborted(@Bind("id") UUID id);

    @SqlUpdate("UPDATE registration SET state = 'OPEN' WHERE id = :id AND state = 'CLOSED'")
    void reopenRound(@Bind("id") UUID registrationId);

    /** Writes the colour a team plays in, the same one again for a game started anew. */
    @SqlUpdate("""
            INSERT INTO hg_team_colour (team_id, colour_rgb, colour_named) VALUES (:teamId, :colourRgb, :colourNamed)
            ON CONFLICT (team_id) DO UPDATE SET colour_rgb = excluded.colour_rgb, colour_named = excluded.colour_named
            """)
    void setTeamColour(
            @Bind("teamId") UUID teamId, @Bind("colourRgb") int colourRgb, @Bind("colourNamed") String colourNamed);

    /** Returns every active member of a round, joined to their Minecraft account. */
    @SqlQuery(ROSTER + " AND member.registration_id = :registrationId")
    @RegisterRowMapper(RosterEntryMapper.class)
    List<RosterEntry> roster(@Bind("registrationId") UUID registrationId);

    /** Returns every active member of the round a game was started from. */
    @SqlQuery(GAME_ROSTER)
    @RegisterRowMapper(RosterEntryMapper.class)
    List<RosterEntry> gameRoster(@Bind("gameId") UUID gameId);

    /** Returns one member of a game's round by their Minecraft account. */
    @SqlQuery(GAME_ROSTER + " AND link.mc_uuid = :mcUuid")
    @RegisterRowMapper(RosterEntryMapper.class)
    Optional<RosterEntry> rosterEntryByMcUuid(@Bind("gameId") UUID gameId, @Bind("mcUuid") UUID mcUuid);

    /**
     * Marks a member of a round ready, which stays so for a game started anew.
     *
     * @return whether {@code discordId} is on a team of that round
     */
    @SqlQuery("""
            WITH member AS (SELECT id FROM team_member
                            WHERE registration_id = :registrationId AND discord_id = :discordId
                              AND state IN ('OWNER', 'ACCEPTED')),
                 marked AS (INSERT INTO hg_ready (member_id) SELECT id FROM member ON CONFLICT DO NOTHING)
            SELECT EXISTS (SELECT 1 FROM member)
            """)
    boolean markReady(@Bind("registrationId") UUID registrationId, @Bind("discordId") DiscordId discordId);

    @SqlUpdate("""
            INSERT INTO hg_event (game_id, type, actor_id, victim_id, detail)
            VALUES (:gameId, :type, :actorId, :victimId, :detail)
            """)
    void recordEvent(
            @Bind("gameId") UUID gameId,
            @Bind("type") String type,
            @Bind("actorId") @Nullable UUID actorId,
            @Bind("victimId") @Nullable UUID victimId,
            @Bind("detail") @Nullable String detail);

    /** The kill tiebreaker: how many KILL events this member is the actor of, for this game. */
    @SqlQuery("""
            SELECT count(*) FROM hg_event
            WHERE game_id = :gameId AND type = 'KILL' AND actor_id = :actorId
            """)
    int killCount(@Bind("gameId") UUID gameId, @Bind("actorId") UUID actorId);

    /** The kill tally of every member of a game in one round trip; members with no kills are absent. */
    @SqlQuery("""
            SELECT actor_id, count(*) AS kills FROM hg_event
            WHERE game_id = :gameId AND type = 'KILL' AND actor_id IS NOT NULL
            GROUP BY actor_id
            """)
    @KeyColumn("actor_id")
    @ValueColumn("kills")
    Map<UUID, Integer> killCounts(@Bind("gameId") UUID gameId);
}
