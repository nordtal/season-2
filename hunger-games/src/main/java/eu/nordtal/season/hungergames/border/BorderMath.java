package eu.nordtal.season.hungergames.border;

/** The arithmetic of the border shrinks. */
public final class BorderMath {

    private BorderMath() {}

    /**
     * Returns the diameter that a death removes: the difference of the diameters divided by the participants minus one.
     *
     * @throws IllegalArgumentException if there are fewer than two participants or the start is not greater than the
     * end
     */
    public static double deathStep(
            final double startDiameter, final double endDiameter, final int effectiveParticipants) {
        if (effectiveParticipants < 2) {
            throw new IllegalArgumentException(
                    "effectiveParticipants must be at least 2, was " + effectiveParticipants);
        }
        if (startDiameter <= endDiameter) {
            throw new IllegalArgumentException("startDiameter must be greater than endDiameter");
        }
        return (startDiameter - endDiameter) / (effectiveParticipants - 1);
    }

    /** Returns the current target minus the step, at least {@code floor}. */
    public static double nextShrinkTarget(final double currentTarget, final double step, final double floor) {
        return Math.max(floor, currentTarget - step);
    }

    /** Returns the milliseconds that a shrink takes at the wall speed. */
    public static long shrinkDurationMillis(
            final double fromDiameter, final double toDiameter, final double wallSpeedDiameterPerSecond) {
        if (wallSpeedDiameterPerSecond <= 0) {
            throw new IllegalArgumentException("wallSpeedDiameterPerSecond must be positive");
        }
        final double delta = Math.abs(fromDiameter - toDiameter);
        return Math.round((delta / wallSpeedDiameterPerSecond) * 1000.0);
    }

    /** Returns the milliseconds that a passive shrink takes. */
    public static long passiveShrinkDurationMillis(
            final double fromDiameter, final double toDiameter, final double passiveShrinkDiameterPerHour) {
        if (passiveShrinkDiameterPerHour <= 0) {
            throw new IllegalArgumentException("passiveShrinkDiameterPerHour must be positive");
        }
        final double delta = Math.abs(fromDiameter - toDiameter);
        final double hours = delta / passiveShrinkDiameterPerHour;
        return Math.round(hours * 3_600_000.0);
    }
}
