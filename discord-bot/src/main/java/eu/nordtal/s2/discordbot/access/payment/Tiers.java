package eu.nordtal.s2.discordbot.access.payment;

import eu.nordtal.s2.common.payment.PaymentRequest;
import eu.nordtal.s2.discordbot.config.AccessSpec;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The price list from {@code access.yml}, and the rule that turns arrived money into a grant.
 *
 * A covered order is granted as ordered; a short payment is downgraded to the highest tier it covers.
 */
public final class Tiers {

    private final List<Tier> byPriceDescending;
    private final int donationCents;

    private Tiers(final List<Tier> byPriceDescending, final int donationCents) {
        this.byPriceDescending = byPriceDescending;
        this.donationCents = donationCents;
    }

    /**
     * Reads the price list from the configuration; an empty one offers only the donation.
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
     * Builds the price list without a config file.
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

    /** Returns every tier, cheapest first, the order the purchase options render in. */
    public List<Tier> all() {
        return byPriceDescending.reversed();
    }

    /** @return the donation surcharge in cents */
    public int donationCents() {
        return donationCents;
    }

    /**
     * Returns the tier offering exactly that many days, if a price change has not removed it.
     *
     * @param days a number of days a user clicked on
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
        // The row's own total, never the configured one; a spontaneous surplus asks today's threshold.
        final int orderedTotal = order.totalCents();
        if (receivedCents < orderedTotal) {
            // Short: the only case the tiers are re-derived from the amount.
            return resolve(receivedCents).map(Settlement::asDowngrade);
        }

        final int surplus = receivedCents - orderedTotal;
        final boolean donation = order.donationOrdered() || surplus >= donationCents;

        // Everything beyond the price of the days: an ordered surcharge plus surplus, the number the thank-you names.
        final int donated = donation ? receivedCents - order.priceCents() : 0;
        return Optional.of(new Settlement(order.days(), order.priceCents(), donated, false));
    }

    /**
     * Returns the highest tier an amount covers, with a remainder of at least the surcharge as a donation.
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
     * What was ordered, taken from the {@code payment_request} row.
     *
     * @param days how many days were asked for.
     * @param priceCents what those days cost, without any donation.
     * @param donationCents the surcharge amount of the order, not a flag.
     */
    public record Order(int days, int priceCents, int donationCents) {

        /**
         * Reads the order off a request, so a later price or surcharge change cannot rewrite it.
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

        /** Returns what the payer was asked to pay, from the stored row alone so a price change cannot rewrite it. */
        public int totalCents() {
            return priceCents + donationCents;
        }
    }

    /**
     * What an arrived amount buys.
     *
     * @param days how many days of access to grant
     * @param priceCents what those days cost; the donation is everything above this
     * @param donationCents how much of the payment counts as a donation; zero when none does
     * @param downgraded whether this is less than was ordered, which the confirmation must say
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
