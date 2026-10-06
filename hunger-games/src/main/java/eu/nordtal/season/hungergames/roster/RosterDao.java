package eu.nordtal.season.hungergames.roster;

import eu.nordtal.season.common.id.DiscordId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * The SQL of a round's roster: its members as this plugin plays them, their readiness and their team colours.
 *
 * The round, its teams and members are discord-bot's; this plugin reads them and writes only readiness and colours.
 */
public interface RosterDao {

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
}
