package eu.nordtal.s2.database.access;

import java.util.List;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

/** The roster's people, one row each with link, access and profile; {@link AccessReader} is the API. */
interface PersonDao {

    /** The columns and joins of a {@link Person}, shared so the two queries below cannot drift. */
    String PERSON_SELECTION = """
            SELECT usr.discord_id,
                   usr.member_state,
                   usr.donor,
                   usr.admin,
                   usr.locale,
                   usr.updated,
                   link.mc_uuid,
                   link.linked,
                   access.access_until,
                   coalesce(access.access_active, false) AS access_active,
                   usr.discord_username,
                   usr.discord_username_updated,
                   usr.discord_display_name,
                   usr.discord_display_name_updated,
                   usr.discord_avatar_url,
                   usr.discord_avatar_url_updated,
                   link.mc_name,
                   link.mc_name_updated,
                   playtime.seconds AS playtime_seconds,
                   usr.admin_granted_by,
                   usr.admin_granted_at,
                   usr.pack_exempt_by,
                   usr.pack_exempt_at
            FROM discord_user usr
                     LEFT JOIN account_link link ON link.discord_id = usr.discord_id
                     -- LEFT: somebody never online has no row, and Person keeps that NULL rather than a zero.
                     LEFT JOIN player_playtime playtime ON playtime.discord_id = usr.discord_id
                     LEFT JOIN LATERAL (
                SELECT max(grant_row.valid_until)                    AS access_until,
                       bool_or(grant_row.revoked IS NULL
                           AND grant_row.valid_from <= now()
                           AND grant_row.valid_until > now())        AS access_active
                FROM access_grant grant_row
                WHERE grant_row.discord_id = usr.discord_id
                ) access ON true
            """;

    /**
     * Returns everyone the bot knows with their link and access, one row per person.
     *
     * {@code discord_id} breaks ties on {@code updated} so a page is stable across calls.
     */
    @SqlQuery(PERSON_SELECTION + """
            ORDER BY usr.updated DESC, usr.discord_id
            LIMIT :limit
            """)
    @RegisterRowMapper(PersonMapper.class)
    List<Person> people(@Bind("limit") int limit);

    /** Returns the one row {@link #people(int)} would print for a single account, for {@code /api/me}. */
    @SqlQuery(PERSON_SELECTION + """
            WHERE usr.discord_id = :discordId
            """)
    @RegisterRowMapper(PersonMapper.class)
    Optional<Person> personOf(@Bind("discordId") String discordId);
}
