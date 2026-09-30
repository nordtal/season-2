package eu.nordtal.s2.database.access;

import java.util.List;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

/**
 * The read-only SQL surface of the roster; {@link RosterDirectory} is the API.
 *
 * Writing access belongs to {@code AccessDirectory} alone.
 */
interface RosterDao {

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

    /** Returns every payment request, newest first, with {@code id} breaking ties for a stable page. */
    @SqlQuery("""
            SELECT id, reference, discord_id, days, amount_cents, donation_cents, status,
                   bunq_tab_id, share_url, created, expires, settled
            FROM payment_request
            ORDER BY created DESC, id DESC
            LIMIT :limit
            """)
    @RegisterRowMapper(PaymentMapper.class)
    List<Payment> payments(@Bind("limit") int limit);

    /** Returns the payment requests still {@code OPEN}, oldest first and without a limit. */
    @SqlQuery("""
            SELECT id, reference, discord_id, days, amount_cents, donation_cents, status,
                   bunq_tab_id, share_url, created, expires, settled
            FROM payment_request
            WHERE status = 'OPEN'
            ORDER BY created, id
            """)
    @RegisterRowMapper(PaymentMapper.class)
    List<Payment> openPayments();

    /** Returns every grant of one person, newest window first, with {@code created} breaking ties. */
    @SqlQuery("""
            SELECT id, discord_id, valid_from, valid_until, source, payment_request_id, revoked, created
            FROM access_grant
            WHERE discord_id = :discordId
            ORDER BY valid_from DESC, created DESC
            """)
    @RegisterRowMapper(GrantMapper.class)
    List<Grant> grantsOf(@Bind("discordId") String discordId);
}
