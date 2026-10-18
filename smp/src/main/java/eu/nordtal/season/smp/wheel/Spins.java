package eu.nordtal.season.smp.wheel;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/** A day is a calendar day in the server's time zone. {@code lastFree} is null before the first free spin. */
public record Spins(int granted, int used, @Nullable LocalDate lastFree) {

    public Spins {
        if (granted < 0 || used < 0) {
            throw new IllegalArgumentException("spins are counted upwards, never below zero");
        }
    }

    public boolean hasFree(final LocalDate today) {
        return lastFree == null || lastFree.isBefore(today);
    }

    public int extras() {
        return Math.max(0, granted - used);
    }

    public int available(final LocalDate today) {
        return (hasFree(today) ? 1 : 0) + extras();
    }

    public boolean canSpin(final LocalDate today) {
        return available(today) > 0;
    }

    /** The free spin goes before the earned spins. */
    public boolean nextIsFree(final LocalDate today) {
        return hasFree(today);
    }
}
