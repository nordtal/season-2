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

/**
 * The SQL surface of {@code payment_request}; {@link PaymentRequests} is the API.
 * Every uniqueness rule is an index, so every write here applies or loses a race loudly.
 */
@RegisterRowMapper(PaymentRequestMapper.class)
interface PaymentRequestDao {

    @SqlQuery("""
            INSERT INTO payment_request (reference, discord_id, days, amount_cents, donation_cents, expires)
            VALUES (:reference, :discordId, :days, :amountCents, :donationCents,
                    now() + make_interval(hours => :ttlHours))
            RETURNING id, reference, discord_id, days, amount_cents, donation_cents, status,
                      bunq_tab_id, share_url, bunq_payment_id, created, expires, settled,
                      tab_failed, tab_cancelled,
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
                   tab_failed, tab_cancelled,
                   matched_cents, matched_by
            FROM payment_request
            WHERE discord_id = :discordId AND status = 'OPEN'
            """)
    Optional<PaymentRequest> findOpenByUser(@Bind("discordId") DiscordId discordId);

    @SqlQuery("""
            SELECT id, reference, discord_id, days, amount_cents, donation_cents, status,
                   bunq_tab_id, share_url, bunq_payment_id, created, expires, settled,
                   tab_failed, tab_cancelled,
                   matched_cents, matched_by
            FROM payment_request
            WHERE reference = :reference
            """)
    Optional<PaymentRequest> findByReference(@Bind("reference") String reference);

    /** Returns one request by its surrogate key. */
    @SqlQuery("""
            SELECT id, reference, discord_id, days, amount_cents, donation_cents, status,
                   bunq_tab_id, share_url, bunq_payment_id, created, expires, settled,
                   tab_failed, tab_cancelled,
                   matched_cents, matched_by
            FROM payment_request
            WHERE id = :id
            """)
    Optional<PaymentRequest> findById(@Bind("id") UUID id);

    @SqlQuery("""
            SELECT id, reference, discord_id, days, amount_cents, donation_cents, status,
                   bunq_tab_id, share_url, bunq_payment_id, created, expires, settled,
                   tab_failed, tab_cancelled,
                   matched_cents, matched_by
            FROM payment_request
            WHERE discord_id = :discordId
            ORDER BY created DESC
            LIMIT :limit
            """)
    List<PaymentRequest> findByUser(@Bind("discordId") DiscordId discordId, @Bind("limit") int limit);

    /** Returns open requests that have a tab, oldest first, which is what the poll loop asks bunq about. */
    @SqlQuery("""
            SELECT id, reference, discord_id, days, amount_cents, donation_cents, status,
                   bunq_tab_id, share_url, bunq_payment_id, created, expires, settled,
                   tab_failed, tab_cancelled,
                   matched_cents, matched_by
            FROM payment_request
            WHERE status = 'OPEN' AND bunq_tab_id IS NOT NULL
            ORDER BY created ASC
            """)
    List<PaymentRequest> openWithTab();

    /** Returns the newest requests of every status, with {@code id} breaking ties for a stable page. */
    @SqlQuery("""
            SELECT id, reference, discord_id, days, amount_cents, donation_cents, status,
                   bunq_tab_id, share_url, bunq_payment_id, created, expires, settled,
                   tab_failed, tab_cancelled,
                   matched_cents, matched_by
            FROM payment_request
            ORDER BY created DESC, id DESC
            LIMIT :limit
            """)
    List<PaymentRequest> recent(@Bind("limit") int limit);

    /** Returns every open request, whether or not it got as far as a tab. */
    @SqlQuery("""
            SELECT id, reference, discord_id, days, amount_cents, donation_cents, status,
                   bunq_tab_id, share_url, bunq_payment_id, created, expires, settled,
                   tab_failed, tab_cancelled,
                   matched_cents, matched_by
            FROM payment_request
            WHERE status = 'OPEN'
            ORDER BY created ASC
            """)
    List<PaymentRequest> allOpen();

    /** Returns open requests past their TTL, whose tabs still have to be cancelled at bunq. */
    @SqlQuery("""
            SELECT id, reference, discord_id, days, amount_cents, donation_cents, status,
                   bunq_tab_id, share_url, bunq_payment_id, created, expires, settled,
                   tab_failed, tab_cancelled,
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
     * Stores the tab steward made, and announces it.
     *
     * @return 1 when stored, 0 when the row had closed, leaving the caller a live URL to cancel
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
     * @return 1 when the request was still open, 0 when something else had already closed it
     */
    @SqlUpdate("""
            UPDATE payment_request
            SET status = :status
            WHERE id = :id AND status = 'OPEN'
            """)
    int close(@Bind("id") UUID id, @Bind("status") String status);

    /** Locks one open request for the transaction that books it, so its order cannot change under the booking. */
    @SqlQuery("""
            SELECT id, reference, discord_id, days, amount_cents, donation_cents, status,
                   bunq_tab_id, share_url, bunq_payment_id, created, expires, settled,
                   tab_failed, tab_cancelled,
                   matched_cents, matched_by
            FROM payment_request
            WHERE id = :id AND status = 'OPEN'
            FOR UPDATE
            """)
    Optional<PaymentRequest> lockOpen(@Bind("id") UUID id);

    /**
     * Marks a locked request paid with what arrived and how it was found, and announces it; 1 when it did.
     * The unique index on {@code bunq_payment_id} refuses a payment another request already booked.
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
     * Clears bunq's last refusal on an open request that still has no tab, before a tab is asked for again.
     *
     * @return 1 when a tab can be asked for, 0 when the row was closed or already has one
     */
    @SqlUpdate("""
            UPDATE payment_request
            SET tab_failed = NULL
            WHERE id = :id AND status = 'OPEN' AND bunq_tab_id IS NULL
            """)
    int clearFailure(@Bind("id") UUID id);

    /**
     * Records that bunq refused to make the tab, which the payer is shown instead of a link.
     *
     * @param reason what bunq said, verbatim enough for an admin to act on
     * @return 1 when the failure was recorded, 0 when the row had closed or a tab had arrived
     */
    @SqlQuery("""
            WITH updated AS (
                UPDATE payment_request
                SET tab_failed = :reason
                WHERE id = :id AND status = 'OPEN' AND bunq_tab_id IS NULL
                RETURNING id
            ),
                 notified AS (
                     SELECT pg_notify('nordtal_payment', '') FROM updated
                 )
            SELECT count(*) FROM notified
            """)
    int failTab(@Bind("id") UUID id, @Bind("reason") String reason);

    /**
     * Records that the tab is gone at bunq.
     *
     * @return 1 when this call closed it out, 0 when there was no tab or somebody had already done so
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
