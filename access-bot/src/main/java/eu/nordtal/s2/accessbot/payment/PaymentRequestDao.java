package eu.nordtal.s2.accessbot.payment;

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
                      bunq_tab_id, share_url, bunq_payment_id, created, expires, settled
            """)
    PaymentRequest insert(@Bind("reference") String reference,
                          @Bind("discordId") String discordId,
                          @Bind("days") int days,
                          @Bind("amountCents") int amountCents,
                          @Bind("donationCents") int donationCents,
                          @Bind("ttlHours") int ttlHours);

    @SqlQuery("""
            SELECT id, reference, discord_id, days, amount_cents, donation_cents, status,
                   bunq_tab_id, share_url, bunq_payment_id, created, expires, settled
            FROM payment_request
            WHERE discord_id = :discordId AND status = 'OPEN'
            """)
    Optional<PaymentRequest> findOpenByUser(@Bind("discordId") String discordId);

    @SqlQuery("""
            SELECT id, reference, discord_id, days, amount_cents, donation_cents, status,
                   bunq_tab_id, share_url, bunq_payment_id, created, expires, settled
            FROM payment_request
            WHERE reference = :reference
            """)
    Optional<PaymentRequest> findByReference(@Bind("reference") String reference);

    @SqlQuery("""
            SELECT id, reference, discord_id, days, amount_cents, donation_cents, status,
                   bunq_tab_id, share_url, bunq_payment_id, created, expires, settled
            FROM payment_request
            WHERE discord_id = :discordId
            ORDER BY created DESC
            LIMIT :limit
            """)
    List<PaymentRequest> findByUser(@Bind("discordId") String discordId, @Bind("limit") int limit);

    /** Open requests that have a tab, oldest first - what the poll loop asks bunq about. */
    @SqlQuery("""
            SELECT id, reference, discord_id, days, amount_cents, donation_cents, status,
                   bunq_tab_id, share_url, bunq_payment_id, created, expires, settled
            FROM payment_request
            WHERE status = 'OPEN' AND bunq_tab_id IS NOT NULL
            ORDER BY created ASC
            """)
    List<PaymentRequest> openWithTab();

    /** Every open request, whether or not it got as far as a tab. Autocompletion for /settle. */
    @SqlQuery("""
            SELECT id, reference, discord_id, days, amount_cents, donation_cents, status,
                   bunq_tab_id, share_url, bunq_payment_id, created, expires, settled
            FROM payment_request
            WHERE status = 'OPEN'
            ORDER BY created ASC
            """)
    List<PaymentRequest> allOpen();

    /** Open requests past their TTL. Their tabs still have to be cancelled at bunq. */
    @SqlQuery("""
            SELECT id, reference, discord_id, days, amount_cents, donation_cents, status,
                   bunq_tab_id, share_url, bunq_payment_id, created, expires, settled
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
