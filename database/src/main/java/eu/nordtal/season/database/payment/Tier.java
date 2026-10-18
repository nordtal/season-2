package eu.nordtal.season.database.payment;

/**
 * One thing to buy: days of access for a price.
 *
 * @param days a day is exactly 24 hours
 * @param priceCents the price in cents
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
