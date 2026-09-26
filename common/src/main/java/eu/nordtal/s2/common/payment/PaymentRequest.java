package eu.nordtal.s2.common.payment;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One row of {@code payment_request}, the persisted state of one purchase flow.
 *
 * Written once the days are known and before a bunq tab exists, so the tab fields are null until then.
 *
 * @param reference       {@code NT-XXXXXX}, unique; the fallback matcher scrapes it from a description
 * @param days            the days ordered; the grant derives from the amount that arrives
 * @param amountCents     what the tab asks for: tier price plus the donation if chosen
 * @param donationCents   the donation part of {@code amountCents}, zero when none was chosen
 * @param bunqTabId       the bunq.me tab, null until the user confirmed
 * @param shareUrl        the bunq.me share URL, null until the user confirmed
 * @param bunqPaymentId   the payment that settled it, null until it is paid
 * @param settled         when it was booked, null unless {@code status} is {@code PAID}
 * @param tabRequested    when a tab was asked for, null when it has not been
 * @param tabFailed       bunq's error when the tab could not be made, null otherwise
 * @param cancelRequested when the tab was asked to go away, null when it was not
 * @param tabCancelled    when the worker cancelled it at bunq, null while pending
 * @param matchedCents    what arrived, in cents, null until the worker found it
 * @param matchedBy       along which path it was found, null until then
 */
public record PaymentRequest(
        UUID id,
        String reference,
        String discordId,
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
        @Nullable Instant tabRequested,
        @Nullable String tabFailed,
        @Nullable Instant cancelRequested,
        @Nullable Instant tabCancelled,
        @Nullable Integer matchedCents,
        @Nullable PaymentMatch matchedBy) {

    /** @return the bunq.me tab, if one was created */
    public Optional<Long> tab() {
        return Optional.ofNullable(bunqTabId);
    }

    /** @return whether the user asked for the donation surcharge */
    public boolean donationRequested() {
        return donationCents > 0;
    }
}
