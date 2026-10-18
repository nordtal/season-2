package eu.nordtal.season.database.access;

import java.util.List;
import java.util.Objects;

/**
 * The prestige crest: a tier from 1 to 13, derived from the total online time of a player, AFK included.
 * It is derived from {@link PlayerIdentity#playtimeSeconds()}, never stored, and pure arithmetic.
 */
public final class Prestige {

    /** The lowest tier, which everybody has from the first second. */
    public static final int MINIMUM_TIER = 1;

    /** The number of crest designs in the resource pack, which caps the tier. */
    public static final int TIER_COUNT = 13;

    /** The config default in hours. */
    public static final List<Integer> DEFAULT_THRESHOLD_HOURS =
            List.of(0, 2, 5, 10, 20, 35, 55, 85, 125, 175, 250, 350, 500);

    private final long[] thresholdSeconds;

    /**
     * Builds the tier table.
     *
     * @param thresholdHours thirteen ascending hour thresholds, the first zero so a new player has a crest
     * @throws IllegalArgumentException if the list is not thirteen ascending values starting at zero
     */
    public Prestige(final List<Integer> thresholdHours) {
        Objects.requireNonNull(thresholdHours, "thresholdHours");
        if (thresholdHours.size() != TIER_COUNT) {
            throw new IllegalArgumentException(
                    "There are exactly " + TIER_COUNT + " crest designs in the resource pack, so there "
                            + "must be exactly that many thresholds; got " + thresholdHours.size());
        }
        if (thresholdHours.get(0) != 0) {
            throw new IllegalArgumentException(
                    "The first threshold must be 0 - tier 1 is what a player has before they have "
                            + "played at all - but was " + thresholdHours.get(0));
        }

        this.thresholdSeconds = new long[TIER_COUNT];
        for (int index = 0; index < TIER_COUNT; index++) {
            final int hours = thresholdHours.get(index);
            if (hours < 0) {
                throw new IllegalArgumentException("A threshold cannot be negative, was " + hours);
            }
            if (index > 0 && hours <= thresholdHours.get(index - 1)) {
                throw new IllegalArgumentException("Thresholds must rise strictly: tier " + (index + 1) + " is " + hours
                        + " hours, which is not above tier " + index + "'s "
                        + thresholdHours.get(index - 1));
            }
            this.thresholdSeconds[index] = hours * 3600L;
        }
    }

    public static Prestige defaults() {
        return new Prestige(DEFAULT_THRESHOLD_HOURS);
    }

    /**
     * Returns the crest tier for an online time.
     *
     * @param seconds the total network-wide online time. A negative value counts as none
     */
    public int tierOf(final long seconds) {
        if (seconds <= 0) {
            return MINIMUM_TIER;
        }
        // The highest threshold the player has passed.
        for (int index = TIER_COUNT - 1; index >= 0; index--) {
            if (seconds >= thresholdSeconds[index]) {
                return index + 1;
            }
        }
        return MINIMUM_TIER;
    }

    /**
     * Returns the online time at which a tier is reached.
     *
     * @param tier a tier between 1 and 13
     * @return the online time in seconds
     * @throws IllegalArgumentException if the tier is outside the table
     */
    public long secondsFor(final int tier) {
        if (tier < MINIMUM_TIER || tier > TIER_COUNT) {
            throw new IllegalArgumentException(
                    "Tier must be between " + MINIMUM_TIER + " and " + TIER_COUNT + ", was " + tier);
        }
        return thresholdSeconds[tier - 1];
    }

    /**
     * Returns the seconds still needed for the next tier, or 0 at tier 13.
     *
     * @param seconds the total online time
     */
    public long secondsToNextTier(final long seconds) {
        final int tier = tierOf(seconds);
        if (tier == TIER_COUNT) {
            return 0L;
        }
        return Math.max(0L, thresholdSeconds[tier] - Math.max(0L, seconds));
    }
}
