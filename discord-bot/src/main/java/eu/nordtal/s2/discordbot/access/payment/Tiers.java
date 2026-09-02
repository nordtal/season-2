package eu.nordtal.s2.discordbot.access.payment;

import eu.nordtal.s2.discordbot.config.AccessSpec;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The price list, read from {@code access.yml}, plus the rule that turns money that actually
 * arrived into what the payer gets.
 *
 * <h2>The order wins when the money covers it</h2>
 * A {@code payment_request} records what was ordered: a number of days, and whether the donation
 * surcharge was included. When the amount that arrives covers that total, <b>exactly what was
 * ordered is granted</b> - the tiers are not re-derived from the amount. Somebody who orders 60
 * days with a donation and pays the 10 € it asks for gets 60 days and the donor role, not 90 days
 * and no role.
 *
 * <h2>Only a shortfall is re-derived</h2>
 * A bunq.me amount is a suggestion the payer can edit on the bunq.me page, so an amount below the
 * ordered total is the case the "pay what you get" rule exists for: the granted tier becomes the
 * highest one the amount does cover, and a remainder of at least the surcharge on top of <i>that</i>
 * price is a donation. Below the cheapest tier nothing is granted and a human is told.
 *
 * <h2>The asymmetry is the point</h2>
 * Covered orders are honoured; short payments are downgraded. Both directions used to be derived
 * from the amount alone, which meant paying more could buy something other than what was asked for
 * - the 10 € case above. Reading the order first removes that, and the amount-only rule survives
 * only where there is genuinely no order to honour: see {@link #resolve(int)}.
 *
 * <h2>Surplus</h2>
 * Money beyond the ordered total is a donation when it reaches the surcharge and one was not
 * already ordered. Otherwise it is ignored - no extra days, no partial credit. Days are bought in
 * tiers, and a tier is either paid for or it is not.
 */
public final class Tiers {

    private final List<Tier> byPriceDescending;
    private final int donationCents;

    private Tiers(final List<Tier> byPriceDescending, final int donationCents) {
        this.byPriceDescending = byPriceDescending;
        this.donationCents = donationCents;
    }

    /**
     * Reads the price list out of the configuration.
     * <p>
     * The list is not empty, its day counts are unique and its prices rise with its day counts -
     * all validated when the config loads, so nothing here has to cope with a broken price list.
     * </p>
     *
     * @param config the loaded and validated access configuration
     * @return the price list
     */
    public static Tiers of(final AccessSpec config) {
        return of(config.tiers().stream()
                        .map(tier -> new Tier(tier.days(), tier.priceCents()))
                        .toList(),
                config.donationCents());
    }

    /**
     * The same price list, without a config file behind it. This is what the tests use, and it is
     * the only reason anything here is expressed in terms of {@link Tier} rather than of the spec
     * interface.
     *
     * @param tiers         the tiers, in any order
     * @param donationCents the donation surcharge
     * @return the price list
     */
    public static Tiers of(final List<Tier> tiers, final int donationCents) {
        return new Tiers(
                tiers.stream()
                        .sorted(Comparator.comparingInt(Tier::priceCents).reversed())
                        .toList(),
                donationCents);
    }

    /** @return every tier, cheapest first - the order the purchase options are rendered in */
    public List<Tier> all() {
        return byPriceDescending.reversed();
    }

    /** @return the donation surcharge in cents */
    public int donationCents() {
        return donationCents;
    }

    /**
     * @param days a number of days a user clicked on
     * @return the tier offering exactly that many days, if it is still on the price list - a
     *         button clicked after a price change refers to a tier that may be gone
     */
    public Optional<Tier> byDays(final int days) {
        return byPriceDescending.stream().filter(tier -> tier.days() == days).findFirst();
    }

    /**
     * What an amount buys against a known order.
     *
     * @param receivedCents what the payment was worth, in cents
     * @param order         what the payer asked for
     * @return what to grant, or empty when the amount does not even cover the cheapest tier
     */
    public Optional<Settlement> resolve(final int receivedCents, final Order order) {
        // The row's own total, never the configured one - see Order.of. `donationCents` below is
        // the CURRENT surcharge and is used for a different question: whether a surplus nobody
        // ordered is big enough to count as a spontaneous donation. That one has no stored intent
        // to honour, so today's threshold is the right one to ask.
        final int orderedTotal = order.totalCents();
        if (receivedCents < orderedTotal) {
            // Short. This is the only case the tiers are re-derived from the amount.
            return resolve(receivedCents).map(Settlement::asDowngrade);
        }

        final int surplus = receivedCents - orderedTotal;
        final boolean donation = order.donationOrdered() || surplus >= donationCents;

        // Everything beyond the price of the days themselves, so an ordered surcharge and any
        // surplus on top of it are one number - which is what the public thank-you names.
        final int donated = donation ? receivedCents - order.priceCents() : 0;
        return Optional.of(new Settlement(order.days(), order.priceCents(), donated, false));
    }

    /**
     * What an amount buys when there is no order behind it.
     * <p>
     * The highest tier the amount covers, with a remainder of at least the surcharge counting as a
     * donation. This is the original rule, kept for a payment that reaches the account with no
     * request to honour.
     * </p>
     * <p>
     * <b>Nothing in the bot calls this in production today.</b> The fallback matcher looks a
     * payment's {@code NT-} reference up, and a reference no request has is raised to the admin
     * channel rather than booked - so every settlement the poll loop performs has an order. It is
     * here because it is the defined behaviour for the orderless case and because
     * {@link #resolve(int, Order)} delegates to it for the shortfall.
     * </p>
     *
     * @param receivedCents what the payment was worth, in cents
     * @return what to grant, or empty when the amount does not even cover the cheapest tier
     */
    public Optional<Settlement> resolve(final int receivedCents) {
        for (final Tier tier : byPriceDescending) {
            if (receivedCents >= tier.priceCents()) {
                final int surplus = receivedCents - tier.priceCents();
                return Optional.of(new Settlement(tier.days(), tier.priceCents(),
                        surplus >= donationCents ? surplus : 0, false));
            }
        }
        return Optional.empty();
    }

    /**
     * What was ordered, taken from the {@code payment_request} row rather than re-derived.
     *
     * @param days          how many days were asked for
     * @param priceCents    what those days cost, without any donation
     * @param donationCents how much of the order was the surcharge - <b>the amount, not a flag</b>
     */
    public record Order(int days, int priceCents, int donationCents) {

        /**
         * Reads the order off a request.
         *
         * <p>The row stores the total and the donation part, so the price of the days themselves is
         * the difference, and both survive a price change after the request was written.</p>
         *
         * <h2>Why the surcharge is an amount here and was a boolean until 2026-09-02</h2>
         * The boolean threw away the one number the row had recorded, so
         * {@code totalCents(configuredSurcharge)} rebuilt the order's total out of the stored price
         * of the days and <em>today's</em> surcharge. An order placed at a 5 € surcharge and paid
         * after it moved to 3 € was then compared against a total nobody had ever been asked for -
         * a mixed number - and that comparison is the one that decides between "grant what was
         * ordered" and "downgrade". The javadoc right here promised the opposite, which is how it
         * survived review.
         *
         * @param request the request being settled
         * @return the order it recorded
         */
        public static Order of(final PaymentRequest request) {
            return new Order(request.days(),
                    request.amountCents() - request.donationCents(),
                    request.donationCents());
        }

        /** @return whether the surcharge was part of the order */
        public boolean donationOrdered() {
            return donationCents > 0;
        }

        /**
         * @return what the payer was actually asked to pay, entirely out of the stored row. It
         *         takes no configuration argument on purpose: there is no value the current config
         *         could contribute that would not be a way for a price change to rewrite history.
         */
        public int totalCents() {
            return priceCents + donationCents;
        }
    }

    /**
     * What an arrived amount buys.
     *
     * @param days          how many days of access to grant
     * @param priceCents    what those days cost - the donation is everything above this
     * @param donationCents how much of the payment counts as a donation; zero when none does
     * @param downgraded    whether this is less than was ordered, which is what the payer's
     *                      confirmation message has to say out loud
     */
    public record Settlement(int days, int priceCents, int donationCents, boolean downgraded) {

        /** @return whether the permanent donor role is earned */
        public boolean donation() {
            return donationCents > 0;
        }

        Settlement asDowngrade() {
            return new Settlement(days, priceCents, donationCents, true);
        }
    }
}
