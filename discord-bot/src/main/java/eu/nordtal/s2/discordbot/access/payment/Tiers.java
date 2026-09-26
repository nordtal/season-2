package eu.nordtal.s2.discordbot.access.payment;

import eu.nordtal.s2.common.payment.PaymentRequest;
import eu.nordtal.s2.discordbot.config.AccessSpec;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The price list, read from {@code access.yml}, plus the rule that turns arrived money into what the payer gets.
 *
 * The order wins when the money covers it: A {@code payment_request} records what was ordered: a number of days, and
 * whether the donation surcharge was included. When the amount that arrives covers that total, exactly what was
 * ordered is granted - the tiers are not re-derived from the amount. Somebody who orders 60 days with a donation and
 * pays the 10 € it asks for gets 60 days and the donor role, not 90 days and no role.
 *
 * Only a shortfall is re-derived: A bunq.me amount is a suggestion the payer can edit on the bunq.me page, so an
 * amount below the ordered total is the case the "pay what you get" rule exists for: the granted tier becomes the
 * highest one the amount does cover, and a remainder of at least the surcharge on top of that price is a donation.
 * Below the cheapest tier nothing is granted and a human is told.
 *
 * The asymmetry is the point: Covered orders are honoured; short payments are downgraded. Both directions used to be
 * derived from the amount alone, which meant paying more could buy something other than what was asked for - the 10
 * € case above. Reading the order first removes that, and the amount-only rule survives only where there is
 * genuinely no order to honour: see {@link #resolve(int)}.
 *
 * Surplus: Money beyond the ordered total is a donation when it reaches the surcharge and one was not already
 * ordered. Otherwise it is ignored - no extra days, no partial credit. Days are bought in tiers, and a tier is
 * either paid for or it is not.
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
     *
     * Its day counts are unique and its prices rise with its day counts - both validated when the config loads, so
     * nothing here has to cope with a broken price list. It may be empty, which is not broken: a deployment that
     * has not decided its prices offers the donation and nothing else, and {@code Configured} says so at startup.
     *
     * @param config the loaded and validated access configuration.
     * @return the price list.
     */
    public static Tiers of(final AccessSpec config) {
        return of(
                config.tiers().stream()
                        .map(tier -> new Tier(tier.days(), tier.priceCents()))
                        .toList(),
                config.donationCents());
    }

    /**
     * The same price list, without a config file behind it.
     *
     * This is what the tests use, and it is the only reason anything here is expressed in terms of {@link Tier}
     * rather than of the spec interface.
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
        // The row's own total, never the configured one - see Order.of; a spontaneous surplus asks today's threshold.
        final int orderedTotal = order.totalCents();
        if (receivedCents < orderedTotal) {
            // Short. This is the only case the tiers are re-derived from the amount.
            return resolve(receivedCents).map(Settlement::asDowngrade);
        }

        final int surplus = receivedCents - orderedTotal;
        final boolean donation = order.donationOrdered() || surplus >= donationCents;

        // Everything beyond the price of the days: an ordered surcharge plus surplus, the number the thank-you names.
        final int donated = donation ? receivedCents - order.priceCents() : 0;
        return Optional.of(new Settlement(order.days(), order.priceCents(), donated, false));
    }

    /**
     * What an amount buys when there is no order behind it.
     *
     * The highest tier the amount covers, with a remainder of at least the surcharge counting as a donation. This
     * is the original rule, kept for a payment that reaches the account with no request to honour.
     *
     * Nothing in the bot calls this for a normal settlement. The fallback matcher looks a payment's {@code NT-}
     * reference up, and a reference no request has is raised to the admin channel rather than booked - so every
     * settlement the poll loop performs has an order. It stays as the defined behaviour for the orderless case,
     * because {@link #resolve(int, Order)} delegates to it for the shortfall.
     *
     * @param receivedCents what the payment was worth, in cents.
     * @return what to grant, or empty when the amount does not even cover the cheapest tier.
     */
    public Optional<Settlement> resolve(final int receivedCents) {
        for (final Tier tier : byPriceDescending) {
            if (receivedCents >= tier.priceCents()) {
                final int surplus = receivedCents - tier.priceCents();
                return Optional.of(
                        new Settlement(tier.days(), tier.priceCents(), surplus >= donationCents ? surplus : 0, false));
            }
        }
        return Optional.empty();
    }

    /**
     * What was ordered, taken from the {@code payment_request} row rather than re-derived.
     *
     * @param days how many days were asked for.
     * @param priceCents what those days cost, without any donation.
     * @param donationCents how much of the order was the surcharge - the amount, not a flag.
     */
    public record Order(int days, int priceCents, int donationCents) {

        /**
         * Reads the order off a request.
         *
         * The row stores the total and the donation part, so the price of the days themselves is the difference,
         * and both survive a price change after the request was written.
         *
         * The surcharge is stored as an amount, not a flag, so a rebuilt total never has to guess at today's
         * configured surcharge: an order placed at one surcharge and paid after it changed would otherwise be
         * compared against a total nobody had ever been asked for.
         *
         * @param request the request being settled.
         * @return the order it recorded.
         */
        public static Order of(final PaymentRequest request) {
            return new Order(request.days(), request.amountCents() - request.donationCents(), request.donationCents());
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
