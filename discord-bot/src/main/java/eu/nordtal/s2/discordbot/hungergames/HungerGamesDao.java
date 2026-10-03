package eu.nordtal.s2.discordbot.hungergames;

import eu.nordtal.s2.common.id.DiscordId;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * The SQL over {@code hg_game}, {@code hg_team} and {@code hg_member} the Discord half needs.
 *
 * The schema's unique indexes enforce the invariants; {@link Teams} only pre-checks for a friendly message.
 */
interface HungerGamesDao {

    @SqlUpdate("INSERT INTO discord_user (discord_id) VALUES (:discordId) ON CONFLICT (discord_id) DO NOTHING")
    void ensureDiscordUser(@Bind("discordId") DiscordId discordId);

    /** The one game that is not DECIDED, if any. */
    @SqlQuery("SELECT id FROM hg_game WHERE state <> 'DECIDED' LIMIT 1")
    Optional<UUID> openGameId();

    @SqlQuery("INSERT INTO hg_game DEFAULT VALUES RETURNING id")
    UUID createGame();

    @SqlQuery("SELECT EXISTS (SELECT 1 FROM hg_team WHERE game_id = :gameId AND lower(name) = lower(:name))")
    boolean teamNameTaken(@Bind("gameId") UUID gameId, @Bind("name") String name);

    /** OWNER, INVITED or ACCEPTED; a DECLINED row does not count. */
    @SqlQuery("""
            SELECT id FROM hg_member
            WHERE game_id = :gameId AND discord_id = :discordId AND state IN ('OWNER', 'INVITED', 'ACCEPTED')
            """)
    Optional<UUID> activeMembershipId(@Bind("gameId") UUID gameId, @Bind("discordId") DiscordId discordId);

    @SqlQuery("INSERT INTO hg_team (game_id, name) VALUES (:gameId, :name) RETURNING id")
    UUID insertTeam(@Bind("gameId") UUID gameId, @Bind("name") String name);

    @SqlUpdate("""
            INSERT INTO hg_member (team_id, game_id, discord_id, state)
            VALUES (:teamId, :gameId, :discordId, 'OWNER')
            """)
    void insertOwner(@Bind("teamId") UUID teamId, @Bind("gameId") UUID gameId, @Bind("discordId") DiscordId discordId);

    @SqlQuery("SELECT team_id FROM hg_member WHERE id = :memberId")
    Optional<UUID> teamIdOfMember(@Bind("memberId") UUID memberId);

    @SqlQuery("SELECT game_id FROM hg_member WHERE id = :memberId")
    Optional<UUID> gameIdOfMember(@Bind("memberId") UUID memberId);

    @SqlQuery("SELECT state FROM hg_member WHERE id = :memberId")
    Optional<String> stateOfMember(@Bind("memberId") UUID memberId);

    @SqlQuery("SELECT discord_id FROM hg_member WHERE id = :memberId")
    Optional<DiscordId> discordIdOfMember(@Bind("memberId") UUID memberId);

    @SqlQuery("SELECT name FROM hg_team WHERE id = :teamId")
    Optional<String> teamName(@Bind("teamId") UUID teamId);

    @SqlQuery("SELECT discord_id FROM hg_member WHERE team_id = :teamId AND state = 'OWNER'")
    Optional<String> ownerDiscordId(@Bind("teamId") UUID teamId);

    /** OWNER and ACCEPTED; a pending invite does not occupy the second seat yet. */
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
    UUID insertInvite(@Bind("teamId") UUID teamId, @Bind("gameId") UUID gameId, @Bind("discordId") DiscordId discordId);

    // discord_id is in the WHERE: only the invited account may answer its own invite.
    @SqlUpdate("""
            UPDATE hg_member SET state = 'ACCEPTED'
            WHERE id = :memberId AND discord_id = :discordId AND state = 'INVITED'
            """)
    int accept(@Bind("memberId") UUID memberId, @Bind("discordId") DiscordId discordId);

    @SqlUpdate("""
            UPDATE hg_member SET state = 'DECLINED'
            WHERE id = :memberId AND discord_id = :discordId AND state = 'INVITED'
            """)
    int decline(@Bind("memberId") UUID memberId, @Bind("discordId") DiscordId discordId);
}
