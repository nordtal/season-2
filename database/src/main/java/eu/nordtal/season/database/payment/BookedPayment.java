package eu.nordtal.season.database.payment;

import eu.nordtal.season.common.id.DiscordId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a booking gave its payer, which the bot tells them: the days and donation it booked and the period they bought.
 *
 * @param donationCents the part of the payment that counts as donation
 * @param downgraded whether fewer days were granted than ordered, because less arrived than the order's total
 * @param receivedCents the amount, null for an admin's booking by hand
 * @param from the start of the access period it bought
 * @param entry the journal entry of the booking, which the admin card draws
 */
public record BookedPayment(
        UUID payment,
        DiscordId person,
        String reference,
        int days,
        int donationCents,
        boolean downgraded,
        @Nullable Integer receivedCents,
        Instant from,
        Instant until,
        UUID entry) {

    public BookedPayment {
        Objects.requireNonNull(payment, "payment");
        Objects.requireNonNull(person, "person");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(until, "until");
        Objects.requireNonNull(entry, "entry");
        if (days <= 0) {
            throw new IllegalArgumentException("a booking grants at least one day, got " + days);
        }
    }

    public boolean donation() {
        return donationCents > 0;
    }
}
