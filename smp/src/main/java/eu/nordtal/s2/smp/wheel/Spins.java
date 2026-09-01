package eu.nordtal.s2.smp.wheel;

import java.time.LocalDate;

/**
 * How many spins somebody has right now, and which kind the next one is.
 *
 * <p><b>A day is a calendar day in the server's own time zone</b>, decided 2026-09-01: the free spin
 * renews at midnight Europe/Berlin, which is what {@code smp_spin.last_free} being a {@code date}
 * already assumes. Predictable beat fair-to-the-second here - somebody who plays late and again the
 * next morning gets two spins close together, and that feels like a gift rather than a rule. A
 * rolling 24 hours would have been strictly fairer, would have moved the time later every day, and
 * would have needed a migration to {@code timestamptz}.
 *
 * <p>Pure, so the boundary is asserted rather than waited for.
 *
 * @param granted  extra spins earned by contributing to objectives, cumulative
 * @param used     how many of those have been spun
 * @param lastFree the day the free spin was last taken, or null if never
 */
public record Spins(int granted, int used, LocalDate lastFree) {

    public Spins {
        if (granted < 0 || used < 0) {
            throw new IllegalArgumentException("spins are counted upwards, never below zero");
        }
    }

    /** Whether today's free spin is still there. */
    public boolean hasFree(final LocalDate today) {
        return lastFree == null || lastFree.isBefore(today);
    }

    /** How many earned spins are left. Never negative, even if the two columns ever disagree. */
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
     * <p>The free one goes first, deliberately: an earned spin kept is an earned spin, but a free
     * one not taken today is gone at midnight.
     */
    public boolean nextIsFree(final LocalDate today) {
        return hasFree(today);
    }
}
