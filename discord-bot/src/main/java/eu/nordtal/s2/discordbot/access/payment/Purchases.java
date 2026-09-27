package eu.nordtal.s2.discordbot.access.payment;

import eu.nordtal.s2.common.payment.PaymentRequest;
import eu.nordtal.s2.common.payment.PaymentRequestStatus;
import eu.nordtal.s2.common.payment.PaymentRequests;
import eu.nordtal.s2.discordbot.config.AccessSpec;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * The purchase state machine, without any Discord or any bank call in it.
 *
 * It writes {@code tab_requested} and {@code cancel_requested}; steward-worker makes the bank call.
 */
@Slf4j
public final class Purchases {

    private final PaymentRequests requests;
    private final Tiers tiers;
    private final AccessSpec config;

    public Purchases(final PaymentRequests requests, final Tiers tiers, final AccessSpec config) {
        this.requests = requests;
        this.tiers = tiers;
        this.config = config;
    }

    /**
     * Records what somebody has selected.
     *
     * An open request without a bunq tab is edited in place; once a tab exists it is superseded, tab and all.
     */
    public PaymentRequest select(final String discordId, final Tier tier, final boolean donation) {
        final int donationCents = donation ? tiers.donationCents() : 0;
        final int amountCents = tier.priceCents() + donationCents;

        final Optional<PaymentRequest> existing = requests.openOf(discordId);
        if (existing.isPresent()) {
            final PaymentRequest open = existing.get();
            if (open.tab().isEmpty() && requests.reselect(open.id(), tier.days(), amountCents, donationCents)) {
                return new PaymentRequest(
                        open.id(),
                        open.reference(),
                        open.discordId(),
                        tier.days(),
                        amountCents,
                        donationCents,
                        open.status(),
                        null,
                        null,
                        null,
                        open.created(),
                        open.expires(),
                        null,
                        open.tabRequested(),
                        open.tabFailed(),
                        open.cancelRequested(),
                        open.tabCancelled(),
                        open.matchedCents(),
                        open.matchedBy());
            }
            close(open, PaymentRequestStatus.SUPERSEDED);
        }

        return requests.open(
                discordId,
                tier.days(),
                amountCents,
                donationCents,
                config.payment().requestTtlHours());
    }

    /**
     * Asks steward-worker for the bunq.me tab and returns once the row is written; asking again is the retry.
     *
     * @param request the open request
     * @return {@code true} when a tab is now wanted; {@code false} when the request is closed or already has one
     */
    public boolean confirm(final PaymentRequest request) {
        if (request.tab().isPresent()) {
            return false;
        }
        return requests.requestTab(request.id());
    }

    /**
     * Closes a request and asks for its bunq tab to be cancelled, in one transaction.
     *
     * @param request the request to close
     * @param status  {@code SUPERSEDED}, {@code EXPIRED} or {@code CANCELLED}
     */
    public void close(final PaymentRequest request, final PaymentRequestStatus status) {
        if (requests.closeAndRequestCancel(request.id(), status)) {
            log.info("Request {} is now {}", request.reference(), status);
        }
    }
}
