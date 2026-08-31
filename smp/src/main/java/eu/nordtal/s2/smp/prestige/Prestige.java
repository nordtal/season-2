package eu.nordtal.s2.smp.prestige;

import java.util.List;
import java.util.Objects;

/**
 * The prestige crest: a tier from 1 to 13, derived from a player's total online time.
 *
 * <p>docs/smp.md#prestige--a-crest-earned-by-time: "A coat-of-arms glyph in 13 design tiers,
 * assigned by total online time. AFK time counts, on purpose: this is a measure of presence, not of
 * effort, and it is the reason play time is not an aura source."
 *
 * <h2>Why this is a function and not a column</h2>
 * <b>The tier is derived, never stored.</b> It falls out of {@code player_playtime.seconds} and the
 * configured thresholds every time it is rendered, so retuning the thresholds is a config edit
 * rather than a migration plus a backfill of every player. That is also why this class has no state
 * and no database: it is called from a render path - the tab list, a nametag, a chat line - many
 * times a second, and it must never do anything but arithmetic.
 *
 * <h2>Where the seconds come from</h2>
 * {@code player_playtime}, written by <b>the proxy</b> and not by this plugin
 * (docs/architecture.md#schema-ownership). Only the proxy sees a whole session across servers; a
 * backend sees its own slice. That is also why the table carries no {@code smp_} prefix.
 */
public final class Prestige {

    /** The lowest tier, which everybody has from their first second. */
    public static final int MINIMUM_TIER = 1;

    /**
     * The number of crest designs the resource pack draws, and therefore a hard cap rather than a
     * configuration choice: {@code Glyphs} allocates thirteen code points and a fourteenth tier
     * would have nothing to render as.
     */
    public static final int TIER_COUNT = 13;

    /**
     * The proposal in docs/smp.md, in hours, "calibrated so tier 13 is reachable in two to three
     * months by somebody who plays regularly and leaves the client running some nights". Listed
     * here as the config default; the table is
     * [a proposal, not a decision](docs/smp.md#numbers-that-are-proposals-not-decisions).
     */
    public static final List<Integer> DEFAULT_THRESHOLD_HOURS =
            List.of(0, 2, 5, 10, 20, 35, 55, 85, 125, 175, 250, 350, 500);

    private final long[] thresholdSeconds;

    /**
     * @param thresholdHours thirteen ascending hour thresholds, the first of which must be zero -
     *                       tier 1 is what a player has before they have played at all, so a
     *                       non-zero first threshold would leave a brand new player with no crest
     *                       to draw
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
                throw new IllegalArgumentException(
                        "Thresholds must rise strictly: tier " + (index + 1) + " is " + hours
                                + " hours, which is not above tier " + index + "'s "
                                + thresholdHours.get(index - 1));
            }
            this.thresholdSeconds[index] = hours * 3600L;
        }
    }

    /** @return the tier table using the defaults from docs/smp.md */
    public static Prestige defaults() {
        return new Prestige(DEFAULT_THRESHOLD_HOURS);
    }

    /**
     * @param seconds total online time, network-wide, from {@code player_playtime.seconds}. A
     *                negative value - which the schema's own CHECK forbids, so this is defence
     *                against a caller and not against the database - is treated as none
     * @return the crest tier, between {@link #MINIMUM_TIER} and {@link #TIER_COUNT}
     */
    public int tierOf(final long seconds) {
        if (seconds <= 0) {
            return MINIMUM_TIER;
        }
        // Walking down from the top rather than up from the bottom: the answer is the highest
        // threshold the player has passed, and saying that directly is shorter than saying it as
        // "the first one they have not".
        for (int index = TIER_COUNT - 1; index >= 0; index--) {
            if (seconds >= thresholdSeconds[index]) {
                return index + 1;
            }
        }
        return MINIMUM_TIER;
    }

    /**
     * @param tier a tier between 1 and 13
     * @return the online time in seconds at which it is reached
     * @throws IllegalArgumentException if the tier is outside the table
     */
    public long secondsFor(final int tier) {
        if (tier < MINIMUM_TIER || tier > TIER_COUNT) {
            throw new IllegalArgumentException("Tier must be between " + MINIMUM_TIER + " and "
                    + TIER_COUNT + ", was " + tier);
        }
        return thresholdSeconds[tier - 1];
    }

    /**
     * How far a player is through their current tier, for a progress bar that has somewhere to go.
     *
     * @param seconds total online time
     * @return the seconds still needed for the next tier, or {@code 0} at tier 13, which is the top
     *         and stays the top - there is no prestige beyond the last crest the pack can draw
     */
    public long secondsToNextTier(final long seconds) {
        final int tier = tierOf(seconds);
        if (tier == TIER_COUNT) {
            return 0L;
        }
        return Math.max(0L, thresholdSeconds[tier] - Math.max(0L, seconds));
    }
}
