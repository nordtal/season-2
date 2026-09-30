package eu.nordtal.s2.database.payment;

import eu.nordtal.s2.common.id.DiscordId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

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
     * Stores the tab steward-worker made, and announces it.
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

    /**
     * Books a payment against a request; the {@code status = 'OPEN'} predicate lets exactly one poll pass win.
     *
     * @return 1 when this call booked it, 0 when it was already closed
     */
    @SqlUpdate("""
            UPDATE payment_request
            SET status = 'PAID', settled = now(), bunq_payment_id = :bunqPaymentId
            WHERE id = :id AND status = 'OPEN'
            """)
    int settle(@Bind("id") UUID id, @Bind("bunqPaymentId") long bunqPaymentId);

    /**
     * Books a request without a bunq payment, after an admin confirmed by hand that money arrived.
     *
     * @return 1 when the request was still open
     */
    @SqlUpdate("""
            UPDATE payment_request
            SET status = 'PAID', settled = now(), matched_by = 'MANUAL'
            WHERE id = :id AND status = 'OPEN'
            """)
    int settleManually(@Bind("id") UUID id);

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

    /**
     * Attributes a bunq payment to a request and claims {@code bunq_payment_id}, leaving the booking to the bot.
     *
     * @return 1 when this call attributed it, 0 when the row was closed or already carries a payment
     */
    @SqlQuery("""
            WITH updated AS (
                UPDATE payment_request
                SET bunq_payment_id = :bunqPaymentId,
                    matched_cents = :matchedCents,
                    matched_by = :matchedBy
                WHERE id = :id AND status = 'OPEN' AND bunq_payment_id IS NULL
                RETURNING id
            ),
                 notified AS (
                     SELECT pg_notify('nordtal_payment', '') FROM updated
                 )
            SELECT count(*) FROM notified
            """)
    int recordMatch(
            @Bind("id") UUID id,
            @Bind("bunqPaymentId") long bunqPaymentId,
            @Bind("matchedCents") int matchedCents,
            @Bind("matchedBy") String matchedBy);

    /** Returns rows steward-worker has matched to a payment and nobody has booked yet. */
    @SqlQuery("""
            SELECT id, reference, discord_id, days, amount_cents, donation_cents, status,
                   bunq_tab_id, share_url, bunq_payment_id, created, expires, settled,
                   tab_failed, tab_cancelled,
                   matched_cents, matched_by
            FROM payment_request
            WHERE status = 'OPEN' AND matched_cents IS NOT NULL
            ORDER BY created ASC
            """)
    List<PaymentRequest> matchedAwaitingBooking();

    /**
     * Records that a payment needs a human, once ever, and wakes whoever posts it.
     *
     * @return 1 the first time, 0 on every later poll that sees the same payment
     */
    @SqlQuery("""
            WITH inserted AS (
                INSERT INTO payment_notice (bunq_payment_id, reason, detail)
                VALUES (:bunqPaymentId, :reason, :detail)
                ON CONFLICT (bunq_payment_id) DO NOTHING
                RETURNING bunq_payment_id
            ),
                 notified AS (
                     SELECT pg_notify('nordtal_payment', '') FROM inserted
                 )
            SELECT count(*) FROM notified
            """)
    int noticeOnce(
            @Bind("bunqPaymentId") long bunqPaymentId, @Bind("reason") String reason, @Bind("detail") String detail);

    /** Returns notices nobody has put in the admin channel yet, oldest first. */
    @SqlQuery("""
            SELECT bunq_payment_id, reason, detail, reported
            FROM payment_notice
            WHERE posted IS NULL
            ORDER BY reported ASC
            """)
    @RegisterRowMapper(PaymentNoticeMapper.class)
    List<PaymentNotice> unpostedNotices();

    /**
     * Claims a notice before it is posted, so a crash loses a post rather than doubling it.
     *
     * @return 1 when this call claimed it, 0 when somebody else had
     */
    @SqlUpdate("""
            UPDATE payment_notice
            SET posted = now()
            WHERE bunq_payment_id = :bunqPaymentId AND posted IS NULL
            """)
    int claimNotice(@Bind("bunqPaymentId") long bunqPaymentId);
}
