package eu.nordtal.season.smp.wheel;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * How many spins somebody has now; a day is a calendar day in the server's time zone.
 *
 * @param granted extra spins earned by contributing to objectives, cumulative
 * @param used how many of those have been spun
 * @param lastFree the day the free spin was last taken, or null if never
 */
public record Spins(int granted, int used, @Nullable LocalDate lastFree) {

    public Spins {
        if (granted < 0 || used < 0) {
            throw new IllegalArgumentException("spins are counted upwards, never below zero");
        }
    }

    /** Whether today's free spin is still there. */
    public boolean hasFree(final LocalDate today) {
        return lastFree == null || lastFree.isBefore(today);
    }

    /** How many earned spins are left, never negative. */
    public int extras() {
        return Math.max(0, granted - used);
    }

    public int available(final LocalDate today) {
        return (hasFree(today) ? 1 : 0) + extras();
    }

    public boolean canSpin(final LocalDate today) {
        return available(today) > 0;
    }

    /**
     * Which kind the next spin is.
     *
     * The free one goes first, since an unused free spin is gone at midnight.
     */
    public boolean nextIsFree(final LocalDate today) {
        return hasFree(today);
    }
}
