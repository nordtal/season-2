package eu.nordtal.s2.common.payment;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;

/**
 * The {@code payment_request} table, as the rest of the bot sees it.
 *
 * Everything here is one statement against a schema that already enforces the rules. The only
 * logic that lives in Java is allocating a reference, because that needs a random value and a
 * retry.
 */
public final class PaymentRequests {

    /**
     * The reference printed on the bunq.me tab and scraped back out of a payment description.
     *
     * Six hex digits is 16.7 million values, and the point is not to be unguessable - it is to be
     * short enough to survive being retyped by a human into a bank transfer description, and
     * distinctive enough that a regex over a payment description does not match anything else. It
     * is a lookup key, never an authorisation: a payment carrying somebody else's reference books
     * against that request, which is what a bank reference is for.
     */
    public static final Pattern REFERENCE_PATTERN = Pattern.compile("NT-[0-9A-F]{6}");

    private static final String PREFIX = "NT-";
    private static final int REFERENCE_BYTES = 3;

    /** PostgreSQL's SQLSTATE for a unique violation. */
    private static final String UNIQUE_VIOLATION = "23505";

    private static final String REFERENCE_CONSTRAINT = "payment_request_reference_key";

    /** Generous: with 16.7 million values, needing more than a handful means something else. */
    private static final int REFERENCE_ATTEMPTS = 10;

    private final Jdbi jdbi;
    private final PaymentRequestDao dao;
    private final SecureRandom random = new SecureRandom();

    public PaymentRequests(final Jdbi jdbi) {
        this.jdbi = jdbi;
        this.dao = jdbi.onDemand(PaymentRequestDao.class);
    }

    public Optional<PaymentRequest> openOf(final String discordId) {
        return dao.findOpenByUser(discordId);
    }

    public Optional<PaymentRequest> byReference(final String reference) {
        return dao.findByReference(reference.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * One request by id - what a caller uses that is waiting for a particular row to change.
     *
     * @see PaymentRequestDao#findById(UUID)
     */
    public Optional<PaymentRequest> byId(final UUID id) {
        return dao.findById(id);
    }

    public List<PaymentRequest> recentOf(final String discordId, final int limit) {
        return dao.findByUser(discordId, limit);
    }

    /** Open requests that have a bunq tab - the poll loop's primary match path. */
    public List<PaymentRequest> openWithTab() {
        return dao.openWithTab();
    }

    /** Every open request, for {@code /settle}'s autocompletion. */
    public List<PaymentRequest> allOpen() {
        return dao.allOpen();
    }

    /** Open requests past their TTL. Their bunq tabs still have to be cancelled. */
    public List<PaymentRequest> dueForExpiry() {
        return dao.dueForExpiry();
    }

    public boolean alreadyBooked(final long bunqPaymentId) {
        return dao.booked(bunqPaymentId).isPresent();
    }

    /**
     * Open requests waiting for a bunq.me tab, oldest ask first - steward-worker's queue.
     *
     * @see PaymentRequestDao#tabsToCreate()
     */
    public List<PaymentRequest> tabsToCreate() {
        return dao.tabsToCreate();
    }

    /**
     * Requests whose bunq tab is supposed to be gone and is not yet - steward-worker's other queue.
     *
     * @see PaymentRequestDao#tabsToCancel()
     */
    public List<PaymentRequest> tabsToCancel() {
        return dao.tabsToCancel();
    }

    /**
     * Requests steward-worker has attributed money to and nobody has booked - the bot's queue.
     *
     * @see PaymentRequestDao#matchedAwaitingBooking()
     */
    public List<PaymentRequest> matchedAwaitingBooking() {
        return dao.matchedAwaitingBooking();
    }

    /** Payments that need a human and have not been put in the admin channel yet. */
    public List<PaymentNotice> unpostedNotices() {
        return dao.unpostedNotices();
    }

    /**
     * Opens a request with a freshly allocated reference.
     *
     * The caller must have closed any previous open request of this user first - the partial
     * unique index {@code payment_request_one_open_per_user_key} makes that a hard requirement
     * rather than an expectation, and this throws if it was not done.
     *
     * A {@code discord_user} row is created first, in the same transaction, because
     * {@code payment_request} has a foreign key onto it and the user may never have been written
     * about before.
     *
     * @param discordId     who is buying
     * @param days          how many days were ordered
     * @param amountCents   what the tab will ask for
     * @param donationCents the donation part of that amount, zero for none
     * @param ttlHours      how long it stays payable
     * @return the row that was written
     */
    public PaymentRequest open(
            final String discordId,
            final int days,
            final int amountCents,
            final int donationCents,
            final int ttlHours) {
        for (int attempt = 1; ; attempt++) {
            final String reference = randomReference();
            try {
                return jdbi.inTransaction(handle -> {
                    handle.createUpdate("INSERT INTO discord_user (discord_id) VALUES (:id) "
                                    + "ON CONFLICT (discord_id) DO NOTHING")
                            .bind("id", discordId)
                            .execute();
                    return handle.attach(PaymentRequestDao.class)
                            .insert(reference, discordId, days, amountCents, donationCents, ttlHours);
                });
            } catch (final UnableToExecuteStatementException exception) {
                // Only a reference collision is retried; a second open request means the caller skipped a step.
                if (attempt >= REFERENCE_ATTEMPTS || !isReferenceCollision(exception)) {
                    throw exception;
                }
            }
        }
    }

    /**
     * Changes the days or donation of an open request that has no tab yet.
     *
     * @return {@code false} once a tab exists, since the tab asks for a fixed amount
     */
    public boolean reselect(final UUID id, final int days, final int amountCents, final int donationCents) {
        return dao.reselect(id, days, amountCents, donationCents) == 1;
    }

    public boolean attachTab(final UUID id, final long tabId, final String shareUrl) {
        return dao.attachTab(id, tabId, shareUrl) == 1;
    }

    /**
     * Moves an open request out of the way.
     *
     * @param status anything but {@code PAID}; use {@link #settle(UUID, long)} for that
     * @return {@code true} when this call closed it
     */
    public boolean close(final UUID id, final PaymentRequestStatus status) {
        if (status == PaymentRequestStatus.PAID) {
            throw new IllegalArgumentException("use settle() to mark a request paid");
        }
        return dao.close(id, status.name()) == 1;
    }

    /**
     * Books a bunq payment against a request.
     *
     * @return {@code true} when this call booked it; {@code false} when the request was no longer
     *         open, or when that payment was already booked against another request
     */
    public boolean settle(final UUID id, final long bunqPaymentId) {
        try {
            return dao.settle(id, bunqPaymentId) == 1;
        } catch (final UnableToExecuteStatementException exception) {
            if (isUniqueViolation(exception)) {
                // payment_request_bunq_payment_id_key: somebody else got there first.
                return false;
            }
            throw exception;
        }
    }

    /**
     * Books a request an admin has confirmed by hand, with no bunq payment behind it.
     *
     * @return {@code true} when the request was still open
     */
    public boolean settleManually(final UUID id) {
        return dao.settleManually(id) == 1;
    }

    /**
     * Records that a payment needs a human, exactly once ever.
     *
     * @return {@code true} the first time this payment is raised, {@code false} on every later
     *         poll that sees the same one - which is what keeps the admin channel readable
     */
    public boolean noticeOnce(final long bunqPaymentId, final String reason, final String detail) {
        return dao.noticeOnce(bunqPaymentId, reason, detail) == 1;
    }

    /**
     * Claims a notice for the admin channel.
     *
     * @return {@code true} when this call claimed it and must post it, {@code false} when somebody
     *         already has
     * @see PaymentRequestDao#claimNotice(long)
     */
    public boolean claimNotice(final long bunqPaymentId) {
        return dao.claimNotice(bunqPaymentId) == 1;
    }

    /**
     * Asks steward-worker for a bunq.me tab, rather than calling bunq from wherever this runs.
     *
     * Also the retry: the previous failure, if there was one, is cleared by the same statement.
     *
     * @return {@code true} when a tab is now wanted; {@code false} when the request was closed or
     *         already has one
     */
    public boolean requestTab(final UUID id) {
        return dao.requestTab(id) == 1;
    }

    /**
     * Records that bunq refused to make the tab, and takes the request out of the queue.
     *
     * @param reason what bunq said - this is what the user ends up being shown instead of a link
     *               that never arrives
     * @return {@code true} when the failure was recorded
     */
    public boolean failTab(final UUID id, final String reason) {
        return dao.failTab(id, reason) == 1;
    }

    /**
     * Asks steward-worker to cancel the request's bunq tab; closing the row is the caller's own write.
     *
     * @return {@code true} when this call asked; {@code false} when it had already been asked for
     */
    public boolean requestCancel(final UUID id) {
        return dao.requestCancel(id) == 1;
    }

    /**
     * Closes a request and asks for its bunq tab to go away, in one transaction.
     *
     * The two halves are one
     * transaction rather than two statements because of the window between them: a row that is
     * closed but has not asked for the cancel is a live bunq.me URL somebody can still pay, and a
     * row that has asked but is still {@code OPEN} can be given a tab by the worker's other queue in
     * the same instant - which is why {@code tabsToCreate} carries a {@code cancel_requested IS
     * NULL} clause as well. Neither state exists for longer than this transaction.
     *
     * The cancel is asked for unconditionally, tab or no tab: {@code tabsToCancel} requires a
     * {@code bunq_tab_id}, so a row that never reached bunq simply never appears there, and the
     * alternative - deciding here, from a row that may be a poll old - is a decision made against a
     * tab that arrived in between.
     *
     * @param id     the request
     * @param status anything but {@code PAID}
     * @return {@code true} when this call closed it; {@code false} when something else already had,
     *         in which case the cancel was still asked for
     */
    public boolean closeAndRequestCancel(final UUID id, final PaymentRequestStatus status) {
        if (status == PaymentRequestStatus.PAID) {
            throw new IllegalArgumentException("use settle() to mark a request paid");
        }
        return jdbi.inTransaction(handle -> {
            final PaymentRequestDao attached = handle.attach(PaymentRequestDao.class);
            attached.requestCancel(id);
            return attached.close(id, status.name()) == 1;
        });
    }

    /**
     * Records that the tab is gone at bunq.
     *
     * @return {@code true} when this call closed it out
     */
    public boolean recordCancelled(final UUID id) {
        return dao.recordCancelled(id) == 1;
    }

    /**
     * Attributes a payment to a request without booking it, leaving the booking to the bot.
     *
     * Unlike {@link #settle(UUID, long)} this does not swallow the unique violation on
     * {@code bunq_payment_id}: attribution has one writer, so a double claim is a bug.
     *
     * @return {@code false} when the request was no longer open or already carried a payment
     * @throws UnableToExecuteStatementException when that payment is already claimed by another request
     */
    public boolean recordMatch(
            final UUID id, final long bunqPaymentId, final int matchedCents, final PaymentMatch matchedBy) {
        return dao.recordMatch(id, bunqPaymentId, matchedCents, matchedBy.name()) == 1;
    }

    private String randomReference() {
        final byte[] bytes = new byte[REFERENCE_BYTES];
        random.nextBytes(bytes);
        return PREFIX + HexFormat.of().withUpperCase().formatHex(bytes);
    }

    private static boolean isReferenceCollision(final UnableToExecuteStatementException exception) {
        final Throwable cause = exception.getCause();
        return isUniqueViolation(exception)
                && cause != null
                && String.valueOf(cause.getMessage()).contains(REFERENCE_CONSTRAINT);
    }

    private static boolean isUniqueViolation(final UnableToExecuteStatementException exception) {
        return exception.getCause() instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState());
    }
}
