package eu.nordtal.season.database.payment;

import eu.nordtal.season.common.id.DiscordId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One row of {@code payment_request}, the state of one purchase flow.
 *
 * @param reference {@code NT-XXXXXX}, unique
 * @param days the days ordered
 * @param amountCents the amount the tab asks for: tier price plus donation
 * @param donationCents the donation part of {@code amountCents}
 * @param bunqTabId the bunq.me tab, null until the user confirmed
 * @param settled when it was booked, null unless {@code status} is {@code PAID}
 * @param tabRefusal why bunq made no tab, null unless it refused
 * @param tabFailed when it refused, null unless {@code tabRefusal} is set
 * @param tabCancelled when steward cancelled the tab at bunq, null while it stands
 */
public record PaymentRequest(
        UUID id,
        String reference,
        DiscordId discordId,
        int days,
        int amountCents,
        int donationCents,
        PaymentRequestStatus status,
        @Nullable Long bunqTabId,
        @Nullable String shareUrl,
        @Nullable Long bunqPaymentId,
        Instant created,
        Instant expires,
        @Nullable Instant settled,
        @Nullable TabRefusal tabRefusal,
        @Nullable Instant tabFailed,
        @Nullable Instant tabCancelled,
        @Nullable Integer matchedCents,
        @Nullable PaymentMatch matchedBy) {

    public Optional<Long> tab() {
        return Optional.ofNullable(bunqTabId);
    }

    public boolean donationRequested() {
        return donationCents > 0;
    }
}
