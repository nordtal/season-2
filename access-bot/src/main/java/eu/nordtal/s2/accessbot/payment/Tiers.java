package eu.nordtal.s2.accessbot.payment;

import eu.nordtal.s2.accessbot.config.AccessSpec;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The price list, read from {@code access.yml}, plus the one rule that turns an amount of money
 * that actually arrived into what the payer gets.
 *
 * <h2>Pay what you get</h2>
 * A bunq.me tab asks for an amount, and the payer can edit it on the bunq.me page before paying.
 * So the amount that arrives is evidence, not confirmation: it may be less than was ordered, more,
 * or something unrelated. The rule from {@code docs/access-system.md}:
 * <ul>
 *   <li>the granted tier is the <b>highest tier whose price the received amount covers</b>;</li>
 *   <li>a remainder of at least the donation surcharge on top of that price counts as the
 *       donation;</li>
 *   <li>below the cheapest tier nothing is granted and a human is told.</li>
 * </ul>
 * <p>
 * <b>This maximises days, not the payer's likely intent.</b> With 3/5/7 € tiers and a 5 €
 * donation, 10 € buys the 7 € tier with a 3 € remainder - 90 days and no donor role - rather than
 * the 5 € tier plus the donation. That is the agreed rule read literally; it is written down here
 * because it is the case a support ticket will be about.
 * </p>
 */
public final class Tiers {

    private final List<Tier> byPriceDescending;
    private final int donationCents;

    private Tiers(final List<Tier> byPriceDescending, final int donationCents) {
        this.byPriceDescending = byPriceDescending;
        this.donationCents = donationCents;
    }

    /**
     * Reads the price list out of the configuration. The ordering the resolution below relies on
     * is validated when the config loads, not assumed here.
     *
     * @param config the loaded and validated access configuration
     * @return the price list
     */
    public static Tiers of(final AccessSpec config) {
        final AccessSpec.TiersSpec tiers = config.tiers();
        return of(List.of(new Tier(tiers.shortDays(), tiers.shortPriceCents()),
                        new Tier(tiers.mediumDays(), tiers.mediumPriceCents()),
                        new Tier(tiers.longDays(), tiers.longPriceCents())),
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

    /** @return every tier, cheapest first - the order the purchase buttons are rendered in */
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
     * Applies the "pay what you get" rule to an amount that actually arrived.
     *
     * @param receivedCents what the payment was worth, in cents
     * @return what to grant, or empty when the amount does not even cover the cheapest tier
     */
    public Optional<Settlement> resolve(final int receivedCents) {
        for (final Tier tier : byPriceDescending) {
            if (receivedCents >= tier.priceCents()) {
                final boolean donation = receivedCents - tier.priceCents() >= donationCents;
                return Optional.of(new Settlement(tier, donation));
            }
        }
        return Optional.empty();
    }

    /**
     * What an arrived amount buys.
     *
     * @param tier     the tier it covers
     * @param donation whether it also covers the donation surcharge on top of that tier
     */
    public record Settlement(Tier tier, boolean donation) {
    }
}
