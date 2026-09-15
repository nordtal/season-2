package eu.nordtal.s2.common.roster;

import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

import java.util.List;

/**
 * The whole SQL surface of the roster, as a JDBI SqlObject interface. Package-private on purpose:
 * {@link RosterDirectory} is the API, and no consumer should hold a {@code Jdbi} or a DAO.
 *
 * <p>Every statement here is a {@code SELECT}. This package is the read-only half of the access
 * schema - writing is {@code eu.nordtal.s2.common.access.AccessDirectory}'s, and a second writer
 * would be a second answer to who has access.
 */
interface RosterDao {

    /**
     * Everyone the bot knows, with their link and their access, in one statement.
     *
     * <h2>Why the grant side is a LATERAL and not a join</h2>
     * A plain join onto {@code access_grant} multiplies the row out once per grant, and the two
     * aggregates would then need a {@code GROUP BY} over every column of {@code discord_user}. The
     * lateral subquery answers exactly one row per person - always one, because an aggregate over
     * no rows still returns a row of nulls - so the shape of the result is one row per person by
     * construction rather than by grouping.
     *
     * <p>{@code bool_or(...)} is {@code NULL} for somebody with no grants at all, which is why it
     * is wrapped in {@code coalesce}: {@code ResultSet#getBoolean} would answer {@code false} for
     * the null anyway, but relying on that would leave the difference between "no" and "nothing to
     * say" to the driver rather than to the query.
     *
     * <p>{@code revoked IS NULL} sits inside {@code bool_or} and <b>not</b> in the {@code WHERE},
     * because the two aggregates disagree about revoked grants on purpose: {@code access_until} is
     * the end of the latest period on record and {@code access_active} is the login decision. See
     * {@link Person}.
     *
     * <p>The order is {@code updated DESC}, and {@code discord_id} breaks the tie so that a page is
     * stable across two calls - several rows can share an {@code updated} down to the microsecond
     * after a bulk role reconcile, and without the tiebreak PostgreSQL is free to return them in
     * any order it likes each time.
     */
    @SqlQuery("""
            SELECT usr.discord_id,
                   usr.member_state,
                   usr.donor,
                   usr.admin,
                   usr.locale,
                   usr.updated,
                   link.mc_uuid,
                   link.linked,
                   access.access_until,
                   coalesce(access.access_active, false) AS access_active
            FROM discord_user usr
                     LEFT JOIN account_link link ON link.discord_id = usr.discord_id
                     LEFT JOIN LATERAL (
                SELECT max(grant_row.valid_until)                    AS access_until,
                       bool_or(grant_row.revoked IS NULL
                           AND grant_row.valid_from <= now()
                           AND grant_row.valid_until > now())        AS access_active
                FROM access_grant grant_row
                WHERE grant_row.discord_id = usr.discord_id
                ) access ON true
            ORDER BY usr.updated DESC, usr.discord_id
            LIMIT :limit
            """)
    @RegisterRowMapper(PersonMapper.class)
    List<Person> people(@Bind("limit") int limit);

    /**
     * Every payment request, newest first. {@code id} breaks the tie for the same reason the roster
     * breaks it on {@code discord_id}: two requests created in the same microsecond must not swap
     * places between two reads of the same page.
     */
    @SqlQuery("""
            SELECT id, reference, discord_id, days, amount_cents, donation_cents, status,
                   bunq_tab_id, share_url, created, expires, settled
            FROM payment_request
            ORDER BY created DESC, id DESC
            LIMIT :limit
            """)
    @RegisterRowMapper(PaymentMapper.class)
    List<Payment> payments(@Bind("limit") int limit);

    /**
     * The payment requests that are still {@code OPEN}, oldest first.
     *
     * <p>Not a filter over {@link #payments(int)}: that one is a page of a table and takes a limit,
     * so a deployment with three hundred settled requests could push every open one off the end of
     * it - and this list is what a person picks a reference from. There is no limit here because
     * there is no honest one: an open request is one nobody has paid yet, and if there are two
     * hundred of those, two hundred is the answer.
     *
     * <p>Oldest first, which is the opposite of {@link #payments(int)} and deliberate: this is a
     * queue to work through, not a page to read.
     */
    @SqlQuery("""
            SELECT id, reference, discord_id, days, amount_cents, donation_cents, status,
                   bunq_tab_id, share_url, created, expires, settled
            FROM payment_request
            WHERE status = 'OPEN'
            ORDER BY created, id
            """)
    @RegisterRowMapper(PaymentMapper.class)
    List<Payment> openPayments();

    /**
     * Every grant of one person, newest first.
     *
     * <p>It orders by {@code valid_from DESC} rather than by {@code created}: grants are appended,
     * so the newest window is the one that starts last, and that is the order a reader is looking
     * for. {@code created} breaks the tie.
     *
     * <p>Deliberately not the same order as {@code AccessDirectory#grantsOf}, which is oldest first
     * because it prints a history in {@code /access-status}. Same rows, two readers, two orders.
     */
    @SqlQuery("""
            SELECT id, discord_id, valid_from, valid_until, source, payment_request_id, revoked, created
            FROM access_grant
            WHERE discord_id = :discordId
            ORDER BY valid_from DESC, created DESC
            """)
    @RegisterRowMapper(GrantMapper.class)
    List<Grant> grantsOf(@Bind("discordId") String discordId);
}
