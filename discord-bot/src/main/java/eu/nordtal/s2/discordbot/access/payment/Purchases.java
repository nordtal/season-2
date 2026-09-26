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
 * Everything that has to happen in a particular order lives here, so the Discord listener is glue
 * and the admin commands share the same rules rather than each having their own copy.
 *
 * {@link #confirm(PaymentRequest)} sets {@code tab_requested} and
 * {@link #close(PaymentRequest, PaymentRequestStatus)} sets {@code cancel_requested};
 * steward-worker holds the bunq key, makes the call and writes the answer back, and
 * {@code nordtal_payment} wakes both sides so neither waits for a poll. What the user sees in
 * between is "your payment link is being created", and what they see if it fails is
 * {@code tab_failed} - a state that exists precisely so that sentence has an exit.
 *
 * A request that is {@code SUPERSEDED} in our table while its bunq.me URL still works is a link
 * somebody can still pay - and that payment would then arrive against a reference nothing books
 * automatically, which is a support ticket rather than a purchase. The closing and the asking are
 * one transaction here; the bank call happens a second later, in another container.
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
     * An open request that has not reached a bunq tab yet is edited in place, so clicking through the options does not
     * burn a reference per click. Once a tab exists the amount is fixed at bunq and the row has to be superseded
     * instead - tab and all.
     *
     * @param discordId who is buying.
     * @param tier which tier they picked.
     * @param donation whether the donation surcharge is included.
     * @return the open request, ready to be confirmed.
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
     * Asks steward-worker for the bunq.me tab.
     *
     * Returns as soon as the row is written - which is the point. The link does not exist yet and this process
     * could not make it; the caller shows "your payment link is being created" and fills it in when
     * {@code nordtal_payment} says the row has one, or shows {@code tab_failed} when the bank said no.
     *
     * Asking again is the retry: {@code requestTab} clears the previous failure in the same statement, so a row
     * can go from refused back to pending without any state living in this process.
     *
     * @param request the open request.
     * @return {@code true} when a tab is now wanted; {@code false} when the request was closed underneath us, or
     *     already has a tab - in which case the caller already has the link.
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
