package eu.nordtal.s2.database.payment;

import eu.nordtal.s2.common.id.DiscordId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One row of {@code payment_request}, the persisted state of one purchase flow.
 *
 * @param reference       {@code NT-XXXXXX}, unique; the fallback matcher scrapes it from a description
 * @param days            the days ordered; the grant derives from the amount that arrives
 * @param amountCents     what the tab asks for: tier price plus the donation if chosen
 * @param donationCents   the donation part of {@code amountCents}, zero when none was chosen
 * @param bunqTabId       the bunq.me tab, null until the user confirmed
 * @param shareUrl        the bunq.me share URL, null until the user confirmed
 * @param bunqPaymentId   the payment that settled it, null until it is paid
 * @param settled         when it was booked, null unless {@code status} is {@code PAID}
 * @param tabFailed       bunq's error when the tab could not be made, null otherwise
 * @param tabCancelled    when steward cancelled it at bunq, null while it stands
 * @param matchedCents    what arrived, in cents, null until steward found it
 * @param matchedBy       along which path it was found, null until then
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
        @Nullable String tabFailed,
        @Nullable Instant tabCancelled,
        @Nullable Integer matchedCents,
        @Nullable PaymentMatch matchedBy) {

    /** Returns the bunq.me tab, if one was created. */
    public Optional<Long> tab() {
        return Optional.ofNullable(bunqTabId);
    }

    /** Returns whether the user asked for the donation surcharge. */
    public boolean donationRequested() {
        return donationCents > 0;
    }
}
