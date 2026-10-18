package eu.nordtal.season.database.payment;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The price list and the rule that turns arrived money into a grant, which steward applies when it books.
 *
 * A covered order is granted as ordered. A short payment is downgraded to the highest tier it covers.
 */
public final class Tiers {

    private final List<Tier> byPriceDescending;
    private final int donationCents;

    private Tiers(final List<Tier> byPriceDescending, final int donationCents) {
        this.byPriceDescending = byPriceDescending;
        this.donationCents = donationCents;
    }

    public static Tiers of(final List<Tier> tiers, final int donationCents) {
        return new Tiers(
                tiers.stream()
                        .sorted(Comparator.comparingInt(Tier::priceCents).reversed())
                        .toList(),
                donationCents);
    }

    /** Returns every tier, cheapest first. */
    public List<Tier> all() {
        return byPriceDescending.reversed();
    }

    public int donationCents() {
        return donationCents;
    }

    /** Returns the tier with exactly that many days, if a price change did not remove it. */
    public Optional<Tier> byDays(final int days) {
        return byPriceDescending.stream().filter(tier -> tier.days() == days).findFirst();
    }

    /**
     * Returns what an amount buys against a known order.
     *
     * @return the grant, or empty if the amount does not cover the cheapest tier
     */
    public Optional<Settlement> resolve(final int receivedCents, final Order order) {
        // The total of the row, never the configured one.
        final int orderedTotal = order.totalCents();
        if (receivedCents < orderedTotal) {
            // Short: the only case that derives the tiers from the amount.
            return resolve(receivedCents).map(Settlement::asDowngrade);
        }

        final int surplus = receivedCents - orderedTotal;
        final boolean donation = order.donationOrdered() || surplus >= donationCents;

        // Everything above the price of the days: ordered surcharge plus surplus, which the thank-you names.
        final int donated = donation ? receivedCents - order.priceCents() : 0;
        return Optional.of(new Settlement(order.days(), order.priceCents(), donated, false));
    }

    /**
     * Returns the highest tier an amount covers. A remainder of at least the surcharge is a donation.
     *
     * @return the grant, or empty if the amount does not cover the cheapest tier
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
     * What was ordered, from the {@code payment_request} row.
     *
     * @param priceCents the price of the days, without donation
     * @param donationCents the surcharge amount of the order
     */
    public record Order(int days, int priceCents, int donationCents) {

        /** Reads the order off a request, so a later price change cannot rewrite it. */
        public static Order of(final PaymentRequest request) {
            return new Order(request.days(), request.amountCents() - request.donationCents(), request.donationCents());
        }

        public boolean donationOrdered() {
            return donationCents > 0;
        }

        /** Returns what the payer was asked to pay. */
        public int totalCents() {
            return priceCents + donationCents;
        }

        /** Returns the order as placed, which a booking by hand grants. */
        public Settlement asOrdered() {
            return new Settlement(days, priceCents, donationCents, false);
        }
    }

    /**
     * What an arrived amount buys.
     *
     * @param priceCents the price of the days. The donation is everything above it
     * @param donationCents the part of the payment that counts as donation
     * @param downgraded whether it is less than ordered, which the confirmation says
     */
    public record Settlement(int days, int priceCents, int donationCents, boolean downgraded) {

        public boolean donation() {
            return donationCents > 0;
        }

        Settlement asDowngrade() {
            return new Settlement(days, priceCents, donationCents, true);
        }
    }
}
