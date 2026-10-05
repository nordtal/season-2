package eu.nordtal.season.hungergames.border;

/** The border's pure arithmetic: the death step and the target rules, free of Bukkit for unit tests. */
public final class BorderMath {

    private BorderMath() {}

    /**
     * Returns the diameter every death removes: the diameter range divided by one fewer than the participants.
     *
     * @param effectiveParticipants the count after countdown-time demotions, fixed for the game
     * @throws IllegalArgumentException if fewer than two participants, or the diameters are not a valid start/end pair
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

    /**
     * Returns where a death-triggered shrink should target next, clamped to {@code floor}.
     *
     * @param currentTarget the in-flight target if shrinking, otherwise the border's actual size
     */
    public static double nextShrinkTarget(final double currentTarget, final double step, final double floor) {
        return Math.max(floor, currentTarget - step);
    }

    /**
     * Returns how long a death-triggered shrink takes, in milliseconds, at least 0.
     *
     * The wall speed is already a diameter rate, so it applies to the diameter delta directly.
     */
    public static long shrinkDurationMillis(
            final double fromDiameter, final double toDiameter, final double wallSpeedDiameterPerSecond) {
        if (wallSpeedDiameterPerSecond <= 0) {
            throw new IllegalArgumentException("wallSpeedDiameterPerSecond must be positive");
        }
        final double delta = Math.abs(fromDiameter - toDiameter);
        return Math.round((delta / wallSpeedDiameterPerSecond) * 1000.0);
    }

    /** Returns how long a passive shrink takes, in milliseconds, at least 0. */
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
