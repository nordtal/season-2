package eu.nordtal.s2.discordbot.payment;

import eu.nordtal.s2.discordbot.bunq.BunqGateway;
import eu.nordtal.s2.discordbot.config.AccessSpec;

import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * The purchase state machine, without any Discord in it.
 * <p>
 * Everything that has to happen in a particular order lives here, so the Discord listener is glue
 * and the poll loop and the admin commands share the same rules rather than each having their own
 * copy.
 * </p>
 *
 * <h2>Closing a request means closing its tab</h2>
 * {@link #close(PaymentRequest, PaymentRequestStatus)} calls bunq before it touches the row. A
 * request that is {@code SUPERSEDED} in our table while its bunq.me URL still works is a link
 * somebody can still pay - and that payment would then arrive against a reference the bot refuses
 * to book on its own, which is a support ticket rather than a purchase.
 */
@Slf4j
public final class Purchases {

    private final PaymentRequests requests;
    private final BunqGateway bunq;
    private final Tiers tiers;
    private final AccessSpec config;

    public Purchases(final PaymentRequests requests, final BunqGateway bunq, final Tiers tiers,
                     final AccessSpec config) {
        this.requests = requests;
        this.bunq = bunq;
        this.tiers = tiers;
        this.config = config;
    }

    /**
     * Records what somebody has selected.
     * <p>
     * An open request that has not reached a bunq tab yet is edited in place, so clicking through
     * the options does not burn a reference per click. Once a tab exists the amount is fixed at
     * bunq and the row has to be superseded instead - tab and all.
     * </p>
     *
     * @param discordId who is buying
     * @param tier      which tier they picked
     * @param donation  whether the donation surcharge is included
     * @return the open request, ready to be confirmed
     */
    public PaymentRequest select(final String discordId, final Tier tier, final boolean donation) {
        final int donationCents = donation ? tiers.donationCents() : 0;
        final int amountCents = tier.priceCents() + donationCents;

        final Optional<PaymentRequest> existing = requests.openOf(discordId);
        if (existing.isPresent()) {
            final PaymentRequest open = existing.get();
            if (open.tab().isEmpty()
                    && requests.reselect(open.id(), tier.days(), amountCents, donationCents)) {
                return new PaymentRequest(open.id(), open.reference(), open.discordId(), tier.days(),
                        amountCents, donationCents, open.status(), null, null, null,
                        open.created(), open.expires(), null);
            }
            close(open, PaymentRequestStatus.SUPERSEDED);
        }

        return requests.open(discordId, tier.days(), amountCents, donationCents,
                config.payment().requestTtlHours());
    }

    /**
     * Creates the bunq.me tab for an open request and stores it.
     *
     * @param request the open request
     * @return the same request with its tab and share URL filled in
     * @throws IllegalStateException if the request was closed while the tab was being created
     */
    public PaymentRequest confirm(final PaymentRequest request) {
        if (request.tab().isPresent()) {
            return request;
        }

        final BunqGateway.Tab tab = bunq.createTab(request.amountCents(), request.reference());
        if (!requests.attachTab(request.id(), tab.id(), tab.shareUrl())) {
            // The row closed underneath us - an expiry sweep, or the user started another
            // purchase in a second client. The tab would then be unreachable, so close it.
            bunq.cancelTab(tab.id());
            throw new IllegalStateException("Request " + request.reference() + " was closed while its "
                    + "bunq.me tab was being created");
        }

        return new PaymentRequest(request.id(), request.reference(), request.discordId(), request.days(),
                request.amountCents(), request.donationCents(), request.status(), tab.id(),
                tab.shareUrl(), null, request.created(), request.expires(), null);
    }

    /**
     * Cancels a request's bunq tab and closes the row.
     *
     * @param request the request to close
     * @param status  {@code SUPERSEDED}, {@code EXPIRED} or {@code CANCELLED}
     */
    public void close(final PaymentRequest request, final PaymentRequestStatus status) {
        request.tab().ifPresent(bunq::cancelTab);
        if (requests.close(request.id(), status)) {
            log.info("Request {} is now {}", request.reference(), status);
        }
    }
}
