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
 * The whole SQL surface this plugin needs, as a JDBI SqlObject interface - the same style as
 * {@code common}'s {@code AccessDao}. This plugin does not modify {@code :common}'s access
 * package; it is a separate, small, read-mostly DAO over tables the bot's migrations own
 * (docs/architecture.md#schema-ownership) plus the {@code hg_*} tables this plugin itself owns the
 * game-state writes for (docs/hunger-games.md#data-model: "written by both the bot (registration)
 * and the plugin (game state)").
 * <p>
 * Package-private would match {@code AccessDao}'s style, but this interface is deliberately public
 * because, unlike {@code AccessDirectory}, there is no wrapping interface here - the plugin's own
 * managers hold a {@code HungerGamesDao} directly, the same way {@code PlaytimeStore} in
 * {@code network-control} holds its own JDBI SqlObject DAO with no extra interface layer.
 * </p>
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
     * The full roster of one game: every active ({@code OWNER}/{@code ACCEPTED}) membership,
     * joined through {@code account_link} to the Minecraft account it belongs to. This is the
     * plugin's one query for "who is registered, on what team, with what colour, ready or not, and
     * what is their Minecraft account" - the join {@code common}'s {@code AccessDirectory} does not
     * offer because it knows nothing about {@code hg_*}.
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
     * Whether the account behind this Minecraft UUID currently holds the Discord admin flag - see
     * {@code common}'s {@code AccessDirectory#setAdmin}, which is what mirrors it there in the
     * first place. This plugin only reads it: LuckPerms is not involved anywhere in this repo
     * (docs/smp.md#admins), and there is no second admin list.
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
     * The same tally for every member of a game, in <b>one</b> round trip.
     *
     * <p>{@link #killCount} answers the tiebreak, which asks about two members at most. The ceremony
     * asks about all of them - and it used to ask with one query per member, inside a loop that ran
     * once per <em>player</em>, on the main thread, at the single busiest moment of the event:
     * forty participants in front of forty players is 1 600 blocking queries on the server thread.
     * The tally does not depend on who is being told, which is what made the inner loop pure waste.
     *
     * <p>Members with no kills are simply absent from the map; the ceremony only prints the ones
     * above zero anyway.
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
