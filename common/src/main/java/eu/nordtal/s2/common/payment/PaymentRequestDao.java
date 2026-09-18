package eu.nordtal.s2.common.payment;

import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The whole SQL surface of {@code payment_request}, as a JDBI SqlObject interface - the same style
 * as {@code AccessDao} in {@code :common}.
 * <p>
 * Package-private: {@link PaymentRequests} is the API. Nothing outside this package holds a DAO.
 * </p>
 * <p>
 * The three rules that matter are enforced by the schema, not by the statements here: one open
 * request per person, one grant per request, and one booking per bunq payment are all unique
 * indexes. Every write below is therefore allowed to be a plain statement that either applies or
 * loses a race loudly, rather than a read followed by a write that two poll passes could both
 * pass.
 * </p>
 */
@RegisterRowMapper(PaymentRequestMapper.class)
interface PaymentRequestDao {

    @SqlQuery("""
            INSERT INTO payment_request (reference, discord_id, days, amount_cents, donation_cents, expires)
            VALUES (:reference, :discordId, :days, :amountCents, :donationCents,
                    now() + make_interval(hours => :ttlHours))
            RETURNING id, reference, discord_id, days, amount_cents, donation_cents, status,
                      bunq_tab_id, share_url, bunq_payment_id, created, expires, settled,
                      tab_requested, tab_failed, cancel_requested, tab_cancelled,
                      matched_cents, matched_by
            """)
    PaymentRequest insert(@Bind("reference") String reference,
                          @Bind("discordId") String discordId,
                          @Bind("days") int days,
                          @Bind("amountCents") int amountCents,
                          @Bind("donationCents") int donationCents,
                          @Bind("ttlHours") int ttlHours);

    @SqlQuery("""
            SELECT id, reference, discord_id, days, amount_cents, donation_cents, status,
                   bunq_tab_id, share_url, bunq_payment_id, created, expires, settled,
                   tab_requested, tab_failed, cancel_requested, tab_cancelled,
                   matched_cents, matched_by
            FROM payment_request
            WHERE discord_id = :discordId AND status = 'OPEN'
            """)
    Optional<PaymentRequest> findOpenByUser(@Bind("discordId") String discordId);

    @SqlQuery("""
            SELECT id, reference, discord_id, days, amount_cents, donation_cents, status,
                   bunq_tab_id, share_url, bunq_payment_id, created, expires, settled,
                   tab_requested, tab_failed, cancel_requested, tab_cancelled,
                   matched_cents, matched_by
            FROM payment_request
            WHERE reference = :reference
            """)
    Optional<PaymentRequest> findByReference(@Bind("reference") String reference);

    @SqlQuery("""
            SELECT id, reference, discord_id, days, amount_cents, donation_cents, status,
                   bunq_tab_id, share_url, bunq_payment_id, created, expires, settled,
                   tab_requested, tab_failed, cancel_requested, tab_cancelled,
                   matched_cents, matched_by
            FROM payment_request
            WHERE discord_id = :discordId
            ORDER BY created DESC
            LIMIT :limit
            """)
    List<PaymentRequest> findByUser(@Bind("discordId") String discordId, @Bind("limit") int limit);

    /** Open requests that have a tab, oldest first - what the poll loop asks bunq about. */
    @SqlQuery("""
            SELECT id, reference, discord_id, days, amount_cents, donation_cents, status,
                   bunq_tab_id, share_url, bunq_payment_id, created, expires, settled,
                   tab_requested, tab_failed, cancel_requested, tab_cancelled,
                   matched_cents, matched_by
            FROM payment_request
            WHERE status = 'OPEN' AND bunq_tab_id IS NOT NULL
            ORDER BY created ASC
            """)
    List<PaymentRequest> openWithTab();

    /** Every open request, whether or not it got as far as a tab. Autocompletion for /settle. */
    @SqlQuery("""
            SELECT id, reference, discord_id, days, amount_cents, donation_cents, status,
                   bunq_tab_id, share_url, bunq_payment_id, created, expires, settled,
                   tab_requested, tab_failed, cancel_requested, tab_cancelled,
                   matched_cents, matched_by
            FROM payment_request
            WHERE status = 'OPEN'
            ORDER BY created ASC
            """)
    List<PaymentRequest> allOpen();

    /** Open requests past their TTL. Their tabs still have to be cancelled at bunq. */
    @SqlQuery("""
            SELECT id, reference, discord_id, days, amount_cents, donation_cents, status,
                   bunq_tab_id, share_url, bunq_payment_id, created, expires, settled,
                   tab_requested, tab_failed, cancel_requested, tab_cancelled,
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
    int reselect(@Bind("id") UUID id,
                 @Bind("days") int days,
                 @Bind("amountCents") int amountCents,
                 @Bind("donationCents") int donationCents);

    @SqlUpdate("""
            UPDATE payment_request
            SET bunq_tab_id = :tabId, share_url = :shareUrl
            WHERE id = :id AND status = 'OPEN'
            """)
    int attachTab(@Bind("id") UUID id, @Bind("tabId") long tabId, @Bind("shareUrl") String shareUrl);

    /**
     * Moves an open request out of the way. Only ever called with a status that is not
     * {@code PAID}, so the {@code settled IFF paid} check constraint holds without touching
     * {@code settled}.
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
     * Books a payment against a request.
     * <p>
     * The {@code status = 'OPEN'} predicate makes this the point at which two poll passes that
     * both saw the same payment are decided: exactly one of them updates a row. The partial unique
     * index on {@code bunq_payment_id} catches the other half of the problem - the same payment
     * matched to two different requests - by throwing.
     * </p>
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
     * Books a request without a bunq payment - {@code /settle}, when an admin has confirmed by
     * hand that money arrived. {@code bunq_payment_id} stays null, which the partial unique index
     * on it allows and which is exactly how a manual settlement is told apart from a matched one.
     *
     * @return 1 when the request was still open
     */
    @SqlUpdate("""
            UPDATE payment_request
            SET status = 'PAID', settled = now()
            WHERE id = :id AND status = 'OPEN'
            """)
    int settleManually(@Bind("id") UUID id);

    @SqlQuery("SELECT 1 FROM payment_request WHERE bunq_payment_id = :bunqPaymentId")
    Optional<Integer> booked(@Bind("bunqPaymentId") long bunqPaymentId);

    // ---------------------------------------------------------------- the seam (concept §10d)

    /**
     * Asks for a bunq.me tab instead of making one.
     *
     * <p>Clears {@code tab_failed} in the same statement: a request that is pending again is not
     * also a request that failed, and leaving the old message there would let the bot show a stale
     * reason while the worker is already trying. This is therefore also the retry.</p>
     *
     * <p>{@code bunq_tab_id IS NULL} makes it idempotent in the direction that matters - a second
     * click once the tab exists changes nothing rather than queueing a second tab for the same
     * request. Re-asking while it is still pending only moves the timestamp, which is what puts a
     * re-asked row at the back of the queue.</p>
     *
     * @return 1 when a tab is now wanted, 0 when the row was closed or already has one
     */
    @SqlQuery("""
            WITH updated AS (
                UPDATE payment_request
                SET tab_requested = now(), tab_failed = NULL
                WHERE id = :id AND status = 'OPEN' AND bunq_tab_id IS NULL
                RETURNING id
            ),
                 notified AS (
                     SELECT pg_notify('nordtal_payment', '') FROM updated
                 )
            SELECT count(*) FROM notified
            """)
    int requestTab(@Bind("id") UUID id);

    /**
     * The worker's queue: open requests that want a tab and have none.
     *
     * <p>{@code cancel_requested IS NULL} is not in the ticket's predicate and is here anyway. The
     * bot closes a row and asks for the cancel in one transaction, but a row that was waiting for a
     * tab when that happened would otherwise still be picked up in the window before the status is
     * visible - and the result is a tab created purely so that the next pass can cancel it.</p>
     *
     * <p>Oldest first, by the moment it was asked for rather than by {@code created}: a request
     * re-asked after a failure is a new wait, and the person doing it is looking at the message
     * now.</p>
     */
    @SqlQuery("""
            SELECT id, reference, discord_id, days, amount_cents, donation_cents, status,
                   bunq_tab_id, share_url, bunq_payment_id, created, expires, settled,
                   tab_requested, tab_failed, cancel_requested, tab_cancelled,
                   matched_cents, matched_by
            FROM payment_request
            WHERE status = 'OPEN'
              AND tab_requested IS NOT NULL
              AND bunq_tab_id IS NULL
              AND cancel_requested IS NULL
            ORDER BY tab_requested ASC
            """)
    List<PaymentRequest> tabsToCreate();

    /**
     * Records that bunq refused, and takes the row out of the queue in the same statement.
     *
     * <p>Clearing {@code tab_requested} is what gives "the link is coming" an exit: the pair
     * {@code tab_requested IS NULL AND tab_failed IS NOT NULL} is a state the bot can render, and
     * {@link #requestTab} puts the row back. A failure left in the queue would be retried on every
     * pass forever, against a bunq that has already said no.</p>
     *
     * @param reason what bunq said, verbatim enough for an admin to act on
     * @return 1 when the failure was recorded, 0 when the row had closed or a tab had arrived
     */
    @SqlQuery("""
            WITH updated AS (
                UPDATE payment_request
                SET tab_failed = :reason, tab_requested = NULL
                WHERE id = :id AND tab_requested IS NOT NULL AND bunq_tab_id IS NULL
                RETURNING id
            ),
                 notified AS (
                     SELECT pg_notify('nordtal_payment', '') FROM updated
                 )
            SELECT count(*) FROM notified
            """)
    int failTab(@Bind("id") UUID id, @Bind("reason") String reason);

    /**
     * Asks for the bunq tab to be cancelled. Says nothing about the row's status: closing the row
     * is the caller's own write, and the two are one transaction rather than one statement because
     * only one of them has to reach bunq.
     *
     * <p>{@code cancel_requested IS NULL} keeps the timestamp at the first ask, so this is safe to
     * call from an expiry sweep that runs every minute over the same row.</p>
     *
     * @return 1 when this call asked, 0 when it had already been asked for
     */
    @SqlQuery("""
            WITH updated AS (
                UPDATE payment_request
                SET cancel_requested = now()
                WHERE id = :id AND cancel_requested IS NULL
                RETURNING id
            ),
                 notified AS (
                     SELECT pg_notify('nordtal_payment', '') FROM updated
                 )
            SELECT count(*) FROM notified
            """)
    int requestCancel(@Bind("id") UUID id);

    /**
     * The worker's other queue: tabs that exist at bunq and are supposed to stop existing.
     *
     * <p>No {@code status} filter. What has to happen at bunq does not depend on how the row was
     * closed, and a cancel asked for on a still-open row is legitimate - the bot asks first and
     * writes the status in the same transaction.</p>
     */
    @SqlQuery("""
            SELECT id, reference, discord_id, days, amount_cents, donation_cents, status,
                   bunq_tab_id, share_url, bunq_payment_id, created, expires, settled,
                   tab_requested, tab_failed, cancel_requested, tab_cancelled,
                   matched_cents, matched_by
            FROM payment_request
            WHERE cancel_requested IS NOT NULL
              AND bunq_tab_id IS NOT NULL
              AND tab_cancelled IS NULL
            ORDER BY cancel_requested ASC
            """)
    List<PaymentRequest> tabsToCancel();

    /**
     * Records that the tab is gone at bunq. The exit from {@link #tabsToCancel} - without it the
     * queue matches the same row on every pass and the worker cancels an already cancelled tab
     * forever.
     *
     * @return 1 when this call closed it out, 0 when somebody had already done so
     */
    @SqlQuery("""
            WITH updated AS (
                UPDATE payment_request
                SET tab_cancelled = now()
                WHERE id = :id AND cancel_requested IS NOT NULL AND tab_cancelled IS NULL
                RETURNING id
            ),
                 notified AS (
                     SELECT pg_notify('nordtal_payment', '') FROM updated
                 )
            SELECT count(*) FROM notified
            """)
    int recordCancelled(@Bind("id") UUID id);

    /**
     * Attributes a bunq payment to a request, without booking it.
     *
     * <h2>Why this does not touch {@code status} or {@code settled}</h2>
     * {@code payment_request_settled_iff_paid} ties those two to each other, and booking is the
     * bot's half of the seam: it grants the days, sends the DM and posts the thank-you, none of
     * which the worker can do. So the worker writes what it found onto a row that is still
     * {@code OPEN} with {@code settled} still {@code NULL}, the check constraint is never crossed,
     * and {@link #settle} stays the one statement that books.
     *
     * <h2>It claims {@code bunq_payment_id}, and that is deliberate</h2>
     * The partial unique index on that column is still the only thing preventing one payment from
     * being booked twice, so the claim has to happen at the moment the payment is attributed rather
     * than later at the booking - otherwise two requests can both be told "this payment is yours"
     * and one of them only finds out when the grant fails. Two calls with the same
     * {@code bunqPaymentId} therefore make the second one throw, which is
     * {@link PaymentRequests#recordMatch} passing the unique violation on rather than absorbing it:
     * unlike {@code settle}, whose caller is a poll racing with itself, this one has a single
     * writer and a second claim is a bug in it.
     *
     * @return 1 when this call attributed it, 0 when the row was closed or already carries a
     *         payment
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
    int recordMatch(@Bind("id") UUID id,
                    @Bind("bunqPaymentId") long bunqPaymentId,
                    @Bind("matchedCents") int matchedCents,
                    @Bind("matchedBy") String matchedBy);

    // ---------------------------------------------------------------- payment_notice

    /**
     * Records that a payment was raised to the admin channel, and says whether this call is the
     * one that raised it.
     *
     * @return 1 the first time, 0 on every later poll that sees the same payment
     */
    @SqlUpdate("""
            INSERT INTO payment_notice (bunq_payment_id, reason, detail)
            VALUES (:bunqPaymentId, :reason, :detail)
            ON CONFLICT (bunq_payment_id) DO NOTHING
            """)
    int noticeOnce(@Bind("bunqPaymentId") long bunqPaymentId,
                   @Bind("reason") String reason,
                   @Bind("detail") String detail);
}
