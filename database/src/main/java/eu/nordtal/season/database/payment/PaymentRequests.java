package eu.nordtal.season.database.payment;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.inbox.BankRequest;
import eu.nordtal.season.database.inbox.Inbox;
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

/** The {@code payment_request} table except the booking, which {@link Bookings} does. */
public final class PaymentRequests {

    /** The reference printed on the bunq.me tab and read back from a payment description. It is a lookup key only. */
    public static final Pattern REFERENCE_PATTERN = Pattern.compile("NT-[0-9A-F]{6}");

    private static final String PREFIX = "NT-";
    private static final int REFERENCE_BYTES = 3;

    private static final String UNIQUE_VIOLATION = "23505";

    private static final String REFERENCE_CONSTRAINT = "payment_request_reference_key";

    private static final int REFERENCE_ATTEMPTS = 10;

    private final Jdbi jdbi;
    private final PaymentRequestDao dao;
    private final Inbox<BankRequest> bank;
    private final SecureRandom random = new SecureRandom();

    /** Borrows {@code dataSource}. The process that built it closes it. */
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

    public Optional<PaymentRequest> byId(final UUID id) {
        return dao.findById(id);
    }

    public List<PaymentRequest> recentOf(final DiscordId discordId, final int limit) {
        return dao.findByUser(discordId, limit);
    }

    public List<PaymentRequest> openWithTab() {
        return dao.openWithTab();
    }

    public List<PaymentRequest> recent(final int limit) {
        // At least one row, as LIMIT 0 would look like an empty table.
        return dao.recent(Math.max(1, limit));
    }

    public List<PaymentRequest> allOpen() {
        return dao.allOpen();
    }

    public List<PaymentRequest> dueForExpiry() {
        return dao.dueForExpiry();
    }

    public boolean alreadyBooked(final long bunqPaymentId) {
        return dao.booked(bunqPaymentId).isPresent();
    }

    /**
     * Opens a request with a new reference and creates the {@code discord_user} row if needed.
     * The caller closes the open request of this user first, or this throws.
     *
     * @param ttlHours how long it stays payable
     * @return the row written
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
                // Only a reference collision is retried.
                if (attempt >= REFERENCE_ATTEMPTS || !isReferenceCollision(exception)) {
                    throw exception;
                }
            }
        }
    }

    /**
     * Changes the days or donation of an open request without a tab.
     *
     * @return false if a tab exists, as the tab asks a fixed amount
     */
    public boolean reselect(final UUID id, final int days, final int amountCents, final int donationCents) {
        return dao.reselect(id, days, amountCents, donationCents) == 1;
    }

    public boolean attachTab(final UUID id, final long tabId, final String shareUrl) {
        return dao.attachTab(id, tabId, shareUrl) == 1;
    }

    /**
     * Asks the bank's inbox for a bunq.me tab and clears an earlier refusal, so it is also the retry.
     *
     * @return false if the request was closed or has a tab
     */
    public boolean requestTab(final UUID id, final Actor by) {
        if (dao.clearFailure(id) == 0) {
            return false;
        }
        bank.submit(new BankRequest.OpenTab(id), by);
        return true;
    }

    /**
     * Records that bunq refused the tab.
     *
     * @param refusal the reason the payer sees instead of a link
     */
    public boolean failTab(final UUID id, final TabRefusal refusal) {
        return dao.failTab(id, refusal) == 1;
    }

    /**
     * Closes a request and asks the bank's inbox to cancel its tab.
     * The ask comes first, as a closed request with a live tab can still be paid.
     *
     * @param status anything but {@code PAID}
     * @param by who closed it: the payer, or Steward for an expiry
     * @return false if something else closed it already. The cancel was asked for anyway
     */
    public boolean closeAndRequestCancel(final UUID id, final PaymentRequestStatus status, final Actor by) {
        if (status == PaymentRequestStatus.PAID) {
            throw new IllegalArgumentException("a request is paid only by a booking");
        }
        bank.submit(new BankRequest.CancelTab(id), by);
        return dao.close(id, status.name()) == 1;
    }

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
