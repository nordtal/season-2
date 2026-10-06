package eu.nordtal.season.discordbot.access.discord;

import eu.nordtal.season.database.payment.PaymentRequest;
import eu.nordtal.season.database.payment.PaymentRequestStatus;
import java.time.Duration;

/**
 * How a buyer's wait for a payment link stands, which decides what the waiting message says.
 *
 * Every constant but {@link #WAITING} ends the wait.
 */
enum PaymentWait {

    /** The request is no longer open: paid, cancelled or expired. */
    GONE,

    /** The tab exists and its link is shown. */
    LINK,

    /** The bank refused the tab; the buyer is told plainly and the admins get one alert. */
    REFUSED,

    /** Neither a link nor a refusal yet, and the message may still be edited. */
    WAITING,

    /** Nothing came within {@link #LIMIT}, so the message names the reference and stops. */
    GIVE_UP;

    /** How long a message says the link is coming, under the fifteen minutes an interaction hook lives. */
    static final Duration LIMIT = Duration.ofMinutes(10);

    /**
     * Reads the request once.
     *
     * @param waited how long the message has been waiting, zero when it is just confirmed
     */
    static PaymentWait of(final PaymentRequest request, final Duration waited) {
        if (request.status() != PaymentRequestStatus.OPEN) {
            return GONE;
        }
        if (request.shareUrl() != null) {
            return LINK;
        }
        if (request.tabFailed() != null) {
            return REFUSED;
        }
        return waited.compareTo(LIMIT) > 0 ? GIVE_UP : WAITING;
    }

    /** @return whether the waiting message is final */
    boolean ends() {
        return this != WAITING;
    }
}
