package eu.nordtal.s2.database.payment;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.Actor;
import eu.nordtal.s2.database.Jdbis;
import eu.nordtal.s2.database.inbox.BankRequest;
import eu.nordtal.s2.database.inbox.Inbox;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;

/**
 * The {@code payment_request} table, short of its booking, which {@link Bookings} does.
 * Only reference allocation is Java logic.
 */
public final class PaymentRequests {

    /**
     * The reference printed on the bunq.me tab and scraped back out of a payment description.
     * A lookup key, never an authorisation: a payment carrying it books against that request.
     */
    public static final Pattern REFERENCE_PATTERN = Pattern.compile("NT-[0-9A-F]{6}");

    private static final String PREFIX = "NT-";
    private static final int REFERENCE_BYTES = 3;

    /** PostgreSQL's SQLSTATE for a unique violation. */
    private static final String UNIQUE_VIOLATION = "23505";

    private static final String REFERENCE_CONSTRAINT = "payment_request_reference_key";

    private static final int REFERENCE_ATTEMPTS = 10;

    private final Jdbi jdbi;
    private final PaymentRequestDao dao;
    private final Inbox<BankRequest> bank;
    private final SecureRandom random = new SecureRandom();

    /** Borrows the pool it is given and owns nothing; the process that built it closes it. */
    public PaymentRequests(final DataSource dataSource) {
        this.jdbi = Jdbis.over(dataSource);
        this.dao = jdbi.onDemand(PaymentRequestDao.class);
        this.bank = Inbox.over(dataSource, BankRequest.TABLE);
    }

    public Optional<PaymentRequest> openOf(final DiscordId discordId) {
        return dao.findOpenByUser(discordId);
    }

    public Optional<PaymentRequest> byReference(final String reference) {
        return dao.findByReference(reference.trim().toUpperCase(Locale.ROOT));
    }

    /** Returns one request by id, for a caller waiting for that row to change. */
    public Optional<PaymentRequest> byId(final UUID id) {
        return dao.findById(id);
    }

    public List<PaymentRequest> recentOf(final DiscordId discordId, final int limit) {
        return dao.findByUser(discordId, limit);
    }

    /** Returns open requests that have a bunq tab, the poll loop's primary match path. */
    public List<PaymentRequest> openWithTab() {
        return dao.openWithTab();
    }

    /** Returns the newest requests of every status, for Steward's payment list. */
    public List<PaymentRequest> recent(final int limit) {
        // At least one row, since LIMIT 0 would look like an empty table.
        return dao.recent(Math.max(1, limit));
    }

    /** Returns every open request, which the Access page offers a booking by hand on. */
    public List<PaymentRequest> allOpen() {
        return dao.allOpen();
    }

    /** Returns open requests past their TTL, whose bunq tabs still have to be cancelled. */
    public List<PaymentRequest> dueForExpiry() {
        return dao.dueForExpiry();
    }

    public boolean alreadyBooked(final long bunqPaymentId) {
        return dao.booked(bunqPaymentId).isPresent();
    }

    /**
     * Opens a request with a freshly allocated reference, creating the {@code discord_user} row if needed.
     * The caller must close any open request of this user first, or this throws.
     *
     * @param discordId     who is buying
     * @param days          how many days were ordered
     * @param amountCents   what the tab will ask for
     * @param donationCents the donation part of that amount, zero for none
     * @param ttlHours      how long it stays payable
     * @return the row that was written
     */
    public PaymentRequest open(
            final DiscordId discordId,
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
     * Asks the bank's inbox for a bunq.me tab, clearing any previous refusal, so this is also the retry.
     *
     * @param by who asked: the payer, pressing the button
     * @return {@code false} when the request was closed or already has one
     */
    public boolean requestTab(final UUID id, final Actor by) {
        if (dao.clearFailure(id) == 0) {
            return false;
        }
        bank.submit(new BankRequest.OpenTab(id), by);
        return true;
    }

    /**
     * Records that bunq refused to make the tab.
     *
     * @param reason what bunq said, shown to the user instead of a link
     * @return {@code true} when the failure was recorded
     */
    public boolean failTab(final UUID id, final String reason) {
        return dao.failTab(id, reason) == 1;
    }

    /**
     * Closes a request and asks the bank's inbox for its tab to go away, tab or no tab.
     * The ask comes first: a request left open with its tab cancelled expires; a closed one with a live tab is paid.
     *
     * @param status anything but {@code PAID}
     * @param by     who closed it: the payer, or Steward for an expiry
     * @return {@code false} when something else already closed it; the cancel was still asked for
     */
    public boolean closeAndRequestCancel(final UUID id, final PaymentRequestStatus status, final Actor by) {
        if (status == PaymentRequestStatus.PAID) {
            throw new IllegalArgumentException("a request is paid only by a booking");
        }
        bank.submit(new BankRequest.CancelTab(id), by);
        return dao.close(id, status.name()) == 1;
    }

    /**
     * Records that the tab is gone at bunq.
     *
     * @return {@code true} when this call closed it out
     */
    public boolean recordCancelled(final UUID id) {
        return dao.recordCancelled(id) == 1;
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
