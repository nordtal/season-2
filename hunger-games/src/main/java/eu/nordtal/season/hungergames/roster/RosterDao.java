package eu.nordtal.season.hungergames.roster;

import eu.nordtal.season.common.id.DiscordId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/** The SQL of the roster of a round. It writes only readiness and team colours. */
public interface RosterDao {

    /** The members of a round that own a team or accepted an invite. */
    String ROSTER = """
            SELECT member.id AS member_id, member.team_id, team.name AS team_name,
                   ready.member_id IS NOT NULL AS ready, link.mc_uuid
            FROM team_member member
                     JOIN team ON team.id = member.team_id
                     LEFT JOIN hg_ready ready ON ready.member_id = member.id
                     LEFT JOIN account_link link ON link.discord_id = member.discord_id
            WHERE member.state IN ('OWNER', 'ACCEPTED')
            """;

    /** The members of the round of a game. */
    String GAME_ROSTER = ROSTER + """
             AND member.registration_id = (SELECT registration_id FROM hg_game WHERE id = :gameId)
            """;

    /** Stores the colour of a team. */
    @SqlUpdate("""
            INSERT INTO hg_team_colour (team_id, colour_rgb, colour_named) VALUES (:teamId, :colourRgb, :colourNamed)
            ON CONFLICT (team_id) DO UPDATE SET colour_rgb = excluded.colour_rgb, colour_named = excluded.colour_named
            """)
    void setTeamColour(
            @Bind("teamId") UUID teamId, @Bind("colourRgb") int colourRgb, @Bind("colourNamed") String colourNamed);

    /** Returns the members of a round with their Minecraft account. */
    @SqlQuery(ROSTER + " AND member.registration_id = :registrationId")
    @RegisterRowMapper(RosterEntryMapper.class)
    List<RosterEntry> roster(@Bind("registrationId") UUID registrationId);

    /** Returns the members of the round of a game. */
    @SqlQuery(GAME_ROSTER)
    @RegisterRowMapper(RosterEntryMapper.class)
    List<RosterEntry> gameRoster(@Bind("gameId") UUID gameId);

    /** Returns the member of the round of a game with this Minecraft UUID. */
    @SqlQuery(GAME_ROSTER + " AND link.mc_uuid = :mcUuid")
    @RegisterRowMapper(RosterEntryMapper.class)
    Optional<RosterEntry> rosterEntryByMcUuid(@Bind("gameId") UUID gameId, @Bind("mcUuid") UUID mcUuid);

    /**
     * Marks the team of the member as ready.
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
