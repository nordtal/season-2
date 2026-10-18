package eu.nordtal.season.discordbot.access.discord;

import eu.nordtal.season.database.payment.PaymentRequest;
import eu.nordtal.season.database.payment.PaymentRequestStatus;
import java.time.Duration;

/**
 * The state of a buyer's wait for a payment link.
 * It decides what the waiting message says. Every constant but {@link #WAITING} ends the wait.
 */
enum PaymentWait {
    GONE,

    LINK,

    /** The bank refused the tab. The buyer is told plainly and the admins get one alert. */
    REFUSED,

    WAITING,

    GIVE_UP;

    /** How long a message says the link is coming. It is under the fifteen minutes an interaction hook lives. */
    static final Duration LIMIT = Duration.ofMinutes(10);

    /** @param waited how long the message has been waiting, zero right after the confirm */
    static PaymentWait of(final PaymentRequest request, final Duration waited) {
        if (request.status() != PaymentRequestStatus.OPEN) {
            return GONE;
        }
        if (request.shareUrl() != null) {
            return LINK;
        }
        if (request.tabRefusal() != null) {
            return REFUSED;
        }
        return waited.compareTo(LIMIT) > 0 ? GIVE_UP : WAITING;
    }

    boolean ends() {
        return this != WAITING;
    }
}
