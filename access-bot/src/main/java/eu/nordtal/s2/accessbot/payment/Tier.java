package eu.nordtal.s2.accessbot.payment;

/**
 * One thing that can be bought: a number of days of access for a price.
 * <p>
 * A record built from {@code access.yml}, not an enum. Season 1's {@code ContributionTier} was an
 * enum carrying prices <i>and</i> Discord role ids, so a price change was a release and a role
 * change was a release. Nothing in season 2 knows a price at compile time.
 * </p>
 *
 * @param days       how many days of access; a day is exactly 24 hours, enforced in SQL
 * @param priceCents what it costs, in integer cents - never a float, never euros
 */
public record Tier(int days, int priceCents) {

    public Tier {
        if (days <= 0) {
            throw new IllegalArgumentException("days must be positive, got: " + days);
        }
        if (priceCents <= 0) {
            throw new IllegalArgumentException("priceCents must be positive, got: " + priceCents);
        }
    }
}
