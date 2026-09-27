package eu.nordtal.s2.discordbot.access.payment;

/**
 * One thing that can be bought: a number of days of access for a price.
 *
 * @param days       how many days of access; a day is exactly 24 hours
 * @param priceCents what it costs, in integer cents
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
