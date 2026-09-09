package eu.nordtal.s2.hungergames.db;

import org.jdbi.v3.sqlobject.config.KeyColumn;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.config.ValueColumn;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The whole SQL surface this plugin needs, as a JDBI SqlObject interface: a read-mostly DAO over
 * tables the bot's migrations own, plus the {@code hg_*} tables the bot writes registrations into
 * and this plugin writes game state into.
 */
public interface HungerGamesDao {

    // ---------------------------------------------------------------- hg_game

    @SqlQuery("SELECT id, state, started, ended, winner_member_id FROM hg_game WHERE state <> 'DECIDED'")
    @RegisterRowMapper(HgGameMapper.class)
    Optional<HgGame> currentGame();

    @SqlQuery("SELECT id, state, started, ended, winner_member_id FROM hg_game WHERE id = :id")
    @RegisterRowMapper(HgGameMapper.class)
    Optional<HgGame> game(@Bind("id") UUID id);

    @SqlUpdate("UPDATE hg_game SET state = :state WHERE id = :id")
    void setGameState(@Bind("id") UUID id, @Bind("state") String state);

    @SqlUpdate("UPDATE hg_game SET state = :state, started = now() WHERE id = :id")
    void startGame(@Bind("id") UUID id, @Bind("state") String state);

    @SqlUpdate("""
            UPDATE hg_game
            SET state = 'DECIDED', ended = now(), winner_member_id = :winnerMemberId
            WHERE id = :id
            """)
    void decideGame(@Bind("id") UUID id, @Bind("winnerMemberId") UUID winnerMemberId);

    // ---------------------------------------------------------------- hg_team

    @SqlQuery("SELECT id, game_id, name, colour_rgb, colour_named FROM hg_team WHERE game_id = :gameId")
    @RegisterRowMapper(HgTeamMapper.class)
    List<HgTeam> teamsOf(@Bind("gameId") UUID gameId);

    @SqlUpdate("UPDATE hg_team SET colour_rgb = :colourRgb, colour_named = :colourNamed WHERE id = :id")
    void setTeamColour(@Bind("id") UUID id, @Bind("colourRgb") int colourRgb,
                       @Bind("colourNamed") String colourNamed);

    // ---------------------------------------------------------------- hg_member

    @SqlQuery("""
            SELECT id, team_id, game_id, discord_id, state, ready
            FROM hg_member
            WHERE game_id = :gameId AND state IN ('OWNER', 'ACCEPTED')
            """)
    @RegisterRowMapper(HgMemberMapper.class)
    List<HgMember> activeMembersOf(@Bind("gameId") UUID gameId);

    @SqlUpdate("UPDATE hg_member SET ready = :ready WHERE game_id = :gameId AND discord_id = :discordId")
    int setReady(@Bind("gameId") UUID gameId, @Bind("discordId") String discordId, @Bind("ready") boolean ready);

    /**
     * The full roster of one game: every active ({@code OWNER}/{@code ACCEPTED}) membership, joined
     * through {@code account_link} to the Minecraft account it belongs to.
     */
    @SqlQuery("""
            SELECT m.id AS member_id, m.team_id, t.name AS team_name, t.colour_rgb, t.colour_named,
                   m.discord_id, m.state, m.ready, link.mc_uuid
            FROM hg_member m
                     JOIN hg_team t ON t.id = m.team_id
                     LEFT JOIN account_link link ON link.discord_id = m.discord_id
            WHERE m.game_id = :gameId AND m.state IN ('OWNER', 'ACCEPTED')
            """)
    @RegisterRowMapper(RosterEntryMapper.class)
    List<RosterEntry> roster(@Bind("gameId") UUID gameId);

    /** One member's row by their Minecraft account, for the current game. */
    @SqlQuery("""
            SELECT m.id AS member_id, m.team_id, t.name AS team_name, t.colour_rgb, t.colour_named,
                   m.discord_id, m.state, m.ready, link.mc_uuid
            FROM hg_member m
                     JOIN hg_team t ON t.id = m.team_id
                     JOIN account_link link ON link.discord_id = m.discord_id
            WHERE m.game_id = :gameId AND m.state IN ('OWNER', 'ACCEPTED') AND link.mc_uuid = :mcUuid
            """)
    @RegisterRowMapper(RosterEntryMapper.class)
    Optional<RosterEntry> rosterEntryByMcUuid(@Bind("gameId") UUID gameId, @Bind("mcUuid") UUID mcUuid);

    // ---------------------------------------------------------------- discord_user / account_link

    @SqlQuery("SELECT mc_uuid FROM account_link WHERE discord_id = :discordId")
    Optional<UUID> mcUuidOf(@Bind("discordId") String discordId);

    @SqlQuery("SELECT discord_id FROM account_link WHERE mc_uuid = :mcUuid")
    Optional<String> discordIdOf(@Bind("mcUuid") UUID mcUuid);

    @SqlQuery("SELECT locale FROM discord_user WHERE discord_id = :discordId")
    Optional<String> localeOf(@Bind("discordId") String discordId);

    /**
     * Whether the account behind this Minecraft UUID currently holds the Discord admin flag.
     * {@code discord_user.admin} is the only admin list; this plugin only reads it.
     */
    @SqlQuery("""
            SELECT usr.admin
            FROM account_link link
                     JOIN discord_user usr ON usr.discord_id = link.discord_id
            WHERE link.mc_uuid = :mcUuid
            """)
    Optional<Boolean> isAdmin(@Bind("mcUuid") UUID mcUuid);

    // ---------------------------------------------------------------- hg_event

    @SqlUpdate("""
            INSERT INTO hg_event (game_id, type, actor_id, victim_id, detail)
            VALUES (:gameId, :type, :actorId, :victimId, :detail)
            """)
    void recordEvent(@Bind("gameId") UUID gameId, @Bind("type") String type,
                     @Bind("actorId") UUID actorId, @Bind("victimId") UUID victimId,
                     @Bind("detail") String detail);

    /** The kill tiebreaker: how many KILL events this member is the actor of, for this game. */
    @SqlQuery("""
            SELECT count(*) FROM hg_event
            WHERE game_id = :gameId AND type = 'KILL' AND actor_id = :actorId
            """)
    int killCount(@Bind("gameId") UUID gameId, @Bind("actorId") UUID actorId);

    /**
     * The same tally for every member of a game in one round trip, for the ceremony - which needs
     * all of them at the busiest moment of the event and must not query per member per player.
     * Members with no kills are absent from the map.
     */
    @SqlQuery("""
            SELECT actor_id, count(*) AS kills FROM hg_event
            WHERE game_id = :gameId AND type = 'KILL' AND actor_id IS NOT NULL
            GROUP BY actor_id
            """)
    @KeyColumn("actor_id")
    @ValueColumn("kills")
    Map<UUID, Integer> killCounts(@Bind("gameId") UUID gameId);
}
