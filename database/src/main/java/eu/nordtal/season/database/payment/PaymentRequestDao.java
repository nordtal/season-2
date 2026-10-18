package eu.nordtal.season.database.payment;

import eu.nordtal.season.common.id.DiscordId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jspecify.annotations.Nullable;

/** The SQL of {@code payment_request}. {@link PaymentRequests} is its API. Every uniqueness rule is an index. */
@RegisterRowMapper(PaymentRequestMapper.class)
interface PaymentRequestDao {

    @SqlQuery("""
            INSERT INTO payment_request (reference, discord_id, days, amount_cents, donation_cents, expires)
            VALUES (:reference, :discordId, :days, :amountCents, :donationCents,
                    now() + make_interval(hours => :ttlHours))
            RETURNING id, reference, discord_id, days, amount_cents, donation_cents, status,
                      bunq_tab_id, share_url, bunq_payment_id, created, expires, settled,
                      tab_refusal, tab_failed, tab_cancelled,
                      matched_cents, matched_by
            """)
    PaymentRequest insert(
            @Bind("reference") String reference,
            @Bind("discordId") DiscordId discordId,
            @Bind("days") int days,
            @Bind("amountCents") int amountCents,
            @Bind("donationCents") int donationCents,
            @Bind("ttlHours") int ttlHours);

    @SqlQuery("""
            SELECT id, reference, discord_id, days, amount_cents, donation_cents, status,
                   bunq_tab_id, share_url, bunq_payment_id, created, expires, settled,
                   tab_refusal, tab_failed, tab_cancelled,
                   matched_cents, matched_by
            FROM payment_request
            WHERE discord_id = :discordId AND status = 'OPEN'
            """)
    Optional<PaymentRequest> findOpenByUser(@Bind("discordId") DiscordId discordId);

    @SqlQuery("""
            SELECT id, reference, discord_id, days, amount_cents, donation_cents, status,
                   bunq_tab_id, share_url, bunq_payment_id, created, expires, settled,
                   tab_refusal, tab_failed, tab_cancelled,
                   matched_cents, matched_by
            FROM payment_request
            WHERE reference = :reference
            """)
    Optional<PaymentRequest> findByReference(@Bind("reference") String reference);

    @SqlQuery("""
            SELECT id, reference, discord_id, days, amount_cents, donation_cents, status,
                   bunq_tab_id, share_url, bunq_payment_id, created, expires, settled,
                   tab_refusal, tab_failed, tab_cancelled,
                   matched_cents, matched_by
            FROM payment_request
            WHERE id = :id
            """)
    Optional<PaymentRequest> findById(@Bind("id") UUID id);

    @SqlQuery("""
            SELECT id, reference, discord_id, days, amount_cents, donation_cents, status,
                   bunq_tab_id, share_url, bunq_payment_id, created, expires, settled,
                   tab_refusal, tab_failed, tab_cancelled,
                   matched_cents, matched_by
            FROM payment_request
            WHERE discord_id = :discordId
            ORDER BY created DESC
            LIMIT :limit
            """)
    List<PaymentRequest> findByUser(@Bind("discordId") DiscordId discordId, @Bind("limit") int limit);

    /** Returns open requests that have a tab, oldest first, for the poll loop. */
    @SqlQuery("""
            SELECT id, reference, discord_id, days, amount_cents, donation_cents, status,
                   bunq_tab_id, share_url, bunq_payment_id, created, expires, settled,
                   tab_refusal, tab_failed, tab_cancelled,
                   matched_cents, matched_by
            FROM payment_request
            WHERE status = 'OPEN' AND bunq_tab_id IS NOT NULL
            ORDER BY created ASC
            """)
    List<PaymentRequest> openWithTab();

    /** Returns the newest requests of every status. {@code id} breaks ties. */
    @SqlQuery("""
            SELECT id, reference, discord_id, days, amount_cents, donation_cents, status,
                   bunq_tab_id, share_url, bunq_payment_id, created, expires, settled,
                   tab_refusal, tab_failed, tab_cancelled,
                   matched_cents, matched_by
            FROM payment_request
            ORDER BY created DESC, id DESC
            LIMIT :limit
            """)
    List<PaymentRequest> recent(@Bind("limit") int limit);

    @SqlQuery("""
            SELECT id, reference, discord_id, days, amount_cents, donation_cents, status,
                   bunq_tab_id, share_url, bunq_payment_id, created, expires, settled,
                   tab_refusal, tab_failed, tab_cancelled,
                   matched_cents, matched_by
            FROM payment_request
            WHERE status = 'OPEN'
            ORDER BY created ASC
            """)
    List<PaymentRequest> allOpen();

    /** Returns open requests past their TTL, whose tabs bunq still has to cancel. */
    @SqlQuery("""
            SELECT id, reference, discord_id, days, amount_cents, donation_cents, status,
                   bunq_tab_id, share_url, bunq_payment_id, created, expires, settled,
                   tab_refusal, tab_failed, tab_cancelled,
                   matched_cents, matched_by
            FROM payment_request
            WHERE status = 'OPEN' AND expires <= now()
            ORDER BY expires ASC
            """)
    List<PaymentRequest> dueForExpiry();

    @SqlUpdate("""
            UPDATE payment_request
            SET days = :days, amount_cents = :amountCents, donation_cents = :donationCents
            WHERE id = :id AND status = 'OPEN' AND bunq_tab_id IS NULL
            """)
    int reselect(
            @Bind("id") UUID id,
            @Bind("days") int days,
            @Bind("amountCents") int amountCents,
            @Bind("donationCents") int donationCents);

    /**
     * Stores the tab and announces it.
     *
     * @return 1 if stored, 0 if the row had closed, which leaves the caller a live URL to cancel
     */
    @SqlQuery("""
            WITH updated AS (
                UPDATE payment_request
                SET bunq_tab_id = :tabId, share_url = :shareUrl
                WHERE id = :id AND status = 'OPEN'
                RETURNING id
            ),
                 notified AS (
                     SELECT pg_notify('nordtal_payment', '') FROM updated
                 )
            SELECT count(*) FROM notified
            """)
    int attachTab(@Bind("id") UUID id, @Bind("tabId") long tabId, @Bind("shareUrl") String shareUrl);

    /**
     * Closes an open request with a status other than {@code PAID}, so {@code settled} stays null.
     *
     * @return 1 if the request was open, 0 if it was closed already
     */
    @SqlUpdate("""
            UPDATE payment_request
            SET status = :status
            WHERE id = :id AND status = 'OPEN'
            """)
    int close(@Bind("id") UUID id, @Bind("status") String status);

    /** Locks one open request for the booking transaction. */
    @SqlQuery("""
            SELECT id, reference, discord_id, days, amount_cents, donation_cents, status,
                   bunq_tab_id, share_url, bunq_payment_id, created, expires, settled,
                   tab_refusal, tab_failed, tab_cancelled,
                   matched_cents, matched_by
            FROM payment_request
            WHERE id = :id AND status = 'OPEN'
            FOR UPDATE
            """)
    Optional<PaymentRequest> lockOpen(@Bind("id") UUID id);

    /**
     * Marks a locked request paid with the arrival and announces it. Returns 1 if it did.
     * The unique index on {@code bunq_payment_id} refuses a payment that another request booked.
     */
    @SqlQuery("""
            WITH booked AS (
                UPDATE payment_request
                SET status = 'PAID', settled = now(), bunq_payment_id = :bunqPaymentId,
                    matched_cents = :receivedCents, matched_by = :matchedBy
                WHERE id = :id AND status = 'OPEN'
                RETURNING id
            ),
                 notified AS (
                     SELECT pg_notify('nordtal_payment', '') FROM booked
                 )
            SELECT count(*) FROM notified
            """)
    int markPaid(
            @Bind("id") UUID id,
            @Bind("bunqPaymentId") @Nullable Long bunqPaymentId,
            @Bind("receivedCents") @Nullable Integer receivedCents,
            @Bind("matchedBy") String matchedBy);

    @SqlQuery("SELECT 1 FROM payment_request WHERE bunq_payment_id = :bunqPaymentId")
    Optional<Integer> booked(@Bind("bunqPaymentId") long bunqPaymentId);

    /**
     * Clears the last bunq refusal on an open request without a tab, before a tab is requested again.
     *
     * @return 1 if a tab can be requested, 0 if the row is closed or has a tab
     */
    @SqlUpdate("""
            UPDATE payment_request
            SET tab_refusal = NULL, tab_failed = NULL
            WHERE id = :id AND status = 'OPEN' AND bunq_tab_id IS NULL
            """)
    int clearFailure(@Bind("id") UUID id);

    /**
     * Records that bunq refused the tab, which the payer sees instead of a link.
     *
     * @param refusal the reason, from which the payer's text and the admins' alert are drawn
     * @return 1 if recorded, 0 if the row closed or a tab arrived
     */
    @SqlQuery("""
            WITH updated AS (
                UPDATE payment_request
                SET tab_refusal = :refusal, tab_failed = now()
                WHERE id = :id AND status = 'OPEN' AND bunq_tab_id IS NULL
                RETURNING id
            ),
                 notified AS (
                     SELECT pg_notify('nordtal_payment', '') FROM updated
                 )
            SELECT count(*) FROM notified
            """)
    int failTab(@Bind("id") UUID id, @Bind("refusal") TabRefusal refusal);

    /**
     * Records that the tab is gone at bunq.
     *
     * @return 1 if this call recorded it, 0 if there was no tab or it was recorded already
     */
    @SqlQuery("""
            WITH updated AS (
                UPDATE payment_request
                SET tab_cancelled = now()
                WHERE id = :id AND bunq_tab_id IS NOT NULL AND tab_cancelled IS NULL
                RETURNING id
            ),
                 notified AS (
                     SELECT pg_notify('nordtal_payment', '') FROM updated
                 )
            SELECT count(*) FROM notified
            """)
    int recordCancelled(@Bind("id") UUID id);
}
