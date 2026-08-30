package eu.nordtal.s2.accessbot.payment;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The {@code payment_request} table, as the rest of the bot sees it.
 * <p>
 * Everything here is one statement against a schema that already enforces the rules. The only
 * logic that lives in Java is allocating a reference, because that needs a random value and a
 * retry.
 * </p>
 */
public final class PaymentRequests {

    /**
     * The reference printed on the bunq.me tab and scraped back out of a payment description.
     * <p>
     * Six hex digits is 16.7 million values, and the point is not to be unguessable - it is to be
     * short enough to survive being retyped by a human into a bank transfer description, and
     * distinctive enough that a regex over a payment description does not match anything else. It
     * is a lookup key, never an authorisation: a payment carrying somebody else's reference books
     * against that request, which is what a bank reference is for.
     * </p>
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

    // ---------------------------------------------------------------- reads

    public Optional<PaymentRequest> openOf(final String discordId) {
        return dao.findOpenByUser(discordId);
    }

    public Optional<PaymentRequest> byReference(final String reference) {
        return dao.findByReference(reference.trim().toUpperCase(Locale.ROOT));
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

    // ---------------------------------------------------------------- writes

    /**
     * Opens a request with a freshly allocated reference.
     * <p>
     * The caller must have closed any previous open request of this user first - the partial
     * unique index {@code payment_request_one_open_per_user_key} makes that a hard requirement
     * rather than an expectation, and this throws if it was not done.
     * </p>
     * <p>
     * A {@code discord_user} row is created first, in the same transaction, because
     * {@code payment_request} has a foreign key onto it and the user may never have been written
     * about before.
     * </p>
     *
     * @param discordId     who is buying
     * @param days          how many days were ordered
     * @param amountCents   what the tab will ask for
     * @param donationCents the donation part of that amount, zero for none
     * @param ttlHours      how long it stays payable
     * @return the row that was written
     */
    public PaymentRequest open(final String discordId, final int days, final int amountCents,
                               final int donationCents, final int ttlHours) {
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
                // Only a collision on the reference is worth retrying. A collision on
                // "one open request per person" means the caller skipped a step, and silently
                // retrying it would just spin until the attempts ran out.
                if (attempt >= REFERENCE_ATTEMPTS || !isReferenceCollision(exception)) {
                    throw exception;
                }
            }
        }
    }

    /**
     * Changes what an open, not-yet-confirmed request is for - a different number of days, or the
     * donation toggled.
     *
     * @return {@code true} when the row was still changeable; {@code false} once a tab exists,
     *         because the tab asks for a fixed amount and editing the row would make the two
     *         disagree
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

    // ---------------------------------------------------------------- helpers

    private String randomReference() {
        final byte[] bytes = new byte[REFERENCE_BYTES];
        random.nextBytes(bytes);
        return PREFIX + HexFormat.of().withUpperCase().formatHex(bytes);
    }

    private static boolean isReferenceCollision(final UnableToExecuteStatementException exception) {
        return isUniqueViolation(exception)
                && String.valueOf(exception.getCause().getMessage()).contains(REFERENCE_CONSTRAINT);
    }

    private static boolean isUniqueViolation(final UnableToExecuteStatementException exception) {
        return exception.getCause() instanceof SQLException sql
                && UNIQUE_VIOLATION.equals(sql.getSQLState());
    }
}
