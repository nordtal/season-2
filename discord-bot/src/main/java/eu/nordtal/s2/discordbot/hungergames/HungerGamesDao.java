package eu.nordtal.s2.discordbot.hungergames;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.Optional;
import java.util.UUID;

/**
 * The whole SQL surface of {@code hg_game}/{@code hg_team}/{@code hg_member} that the Discord half
 * needs - the same style as {@code PaymentRequestDao}. See {@code V5__hunger_games.sql} and
 * {@code docs/hunger-games.md#registration}.
 * <p>
 * Package-private: {@link Teams} is the API. Nothing outside this package holds a DAO.
 * </p>
 * <p>
 * {@code hg_member.ready} never appears here - the Discord half writes {@code OWNER}/{@code
 * INVITED}/{@code ACCEPTED}/{@code DECLINED} and nothing else. Readiness is written only by the
 * {@code hunger-games} Paper plugin's lobby broadcast (docs/hunger-games.md#the-lobby).
 * </p>
 * <p>
 * Most of the invariants below are enforced by the schema, not by a read-then-write here:
 * {@code hg_team_game_id_name_lower_key} and {@code hg_member_one_active_membership_key} are what
 * actually stop two people racing the same team name or the same invite - {@link Teams} pre-checks
 * for a friendly message, but the constraint is the real guard, the same relationship
 * {@code PaymentRequestDao}/{@code PaymentRequests} already has with {@code payment_request}'s
 * unique indexes.
 * </p>
 */
interface HungerGamesDao {

    @SqlUpdate("INSERT INTO discord_user (discord_id) VALUES (:discordId) ON CONFLICT (discord_id) DO NOTHING")
    void ensureDiscordUser(@Bind("discordId") String discordId);

    /** The one non-DECIDED game, if any - see {@code hg_game_one_open_key}. */
    @SqlQuery("SELECT id FROM hg_game WHERE state <> 'DECIDED' LIMIT 1")
    Optional<UUID> openGameId();

    @SqlQuery("INSERT INTO hg_game DEFAULT VALUES RETURNING id")
    UUID createGame();

    @SqlQuery("SELECT EXISTS (SELECT 1 FROM hg_team WHERE game_id = :gameId AND lower(name) = lower(:name))")
    boolean teamNameTaken(@Bind("gameId") UUID gameId, @Bind("name") String name);

    /** OWNER, INVITED or ACCEPTED - a DECLINED row does not count, see the partial index. */
    @SqlQuery("""
            SELECT id FROM hg_member
            WHERE game_id = :gameId AND discord_id = :discordId AND state IN ('OWNER', 'INVITED', 'ACCEPTED')
            """)
    Optional<UUID> activeMembershipId(@Bind("gameId") UUID gameId, @Bind("discordId") String discordId);

    @SqlQuery("INSERT INTO hg_team (game_id, name) VALUES (:gameId, :name) RETURNING id")
    UUID insertTeam(@Bind("gameId") UUID gameId, @Bind("name") String name);

    @SqlUpdate("""
            INSERT INTO hg_member (team_id, game_id, discord_id, state)
            VALUES (:teamId, :gameId, :discordId, 'OWNER')
            """)
    void insertOwner(@Bind("teamId") UUID teamId, @Bind("gameId") UUID gameId,
                     @Bind("discordId") String discordId);

    @SqlQuery("SELECT team_id FROM hg_member WHERE id = :memberId")
    Optional<UUID> teamIdOfMember(@Bind("memberId") UUID memberId);

    @SqlQuery("SELECT game_id FROM hg_member WHERE id = :memberId")
    Optional<UUID> gameIdOfMember(@Bind("memberId") UUID memberId);

    @SqlQuery("SELECT state FROM hg_member WHERE id = :memberId")
    Optional<String> stateOfMember(@Bind("memberId") UUID memberId);

    @SqlQuery("SELECT discord_id FROM hg_member WHERE id = :memberId")
    Optional<String> discordIdOfMember(@Bind("memberId") UUID memberId);

    @SqlQuery("SELECT name FROM hg_team WHERE id = :teamId")
    Optional<String> teamName(@Bind("teamId") UUID teamId);

    @SqlQuery("SELECT discord_id FROM hg_member WHERE team_id = :teamId AND state = 'OWNER'")
    Optional<String> ownerDiscordId(@Bind("teamId") UUID teamId);

    /** OWNER + ACCEPTED, not INVITED - a pending invite does not occupy the second seat yet. */
    @SqlQuery("""
            SELECT COUNT(*) FROM hg_member WHERE team_id = :teamId AND state IN ('OWNER', 'ACCEPTED')
            """)
    int settledMemberCount(@Bind("teamId") UUID teamId);

    @SqlQuery("""
            SELECT EXISTS (SELECT 1 FROM hg_member WHERE team_id = :teamId AND state = 'INVITED')
            """)
    boolean hasPendingInvite(@Bind("teamId") UUID teamId);

    @SqlQuery("""
            INSERT INTO hg_member (team_id, game_id, discord_id, state)
            VALUES (:teamId, :gameId, :discordId, 'INVITED')
            RETURNING id
            """)
    UUID insertInvite(@Bind("teamId") UUID teamId, @Bind("gameId") UUID gameId,
                      @Bind("discordId") String discordId);

    // discord_id is part of the WHERE, not just a precondition checked in Java first: only the
    // invited account may answer its own invite, and checking it in the same statement that flips
    // the state closes the gap between "whose invite is this" and "is it still pending".
    @SqlUpdate("""
            UPDATE hg_member SET state = 'ACCEPTED'
            WHERE id = :memberId AND discord_id = :discordId AND state = 'INVITED'
            """)
    int accept(@Bind("memberId") UUID memberId, @Bind("discordId") String discordId);

    @SqlUpdate("""
            UPDATE hg_member SET state = 'DECLINED'
            WHERE id = :memberId AND discord_id = :discordId AND state = 'INVITED'
            """)
    int decline(@Bind("memberId") UUID memberId, @Bind("discordId") String discordId);

    /** Read directly rather than through {@code PlayerLocales} - the Discord half has no MC UUID. */
    @SqlQuery("SELECT locale FROM discord_user WHERE discord_id = :discordId")
    Optional<String> localeOf(@Bind("discordId") String discordId);
}
