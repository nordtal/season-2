package eu.nordtal.s2.hungergames.border;

/**
 * The border's pure arithmetic: the death step and the target-composition rules. No Bukkit type
 * appears here on purpose, so it is exercised by plain unit tests rather than a running server.
 */
public final class BorderMath {

    private BorderMath() {
    }

    /**
     * The fixed amount of diameter every death removes:
     * {@code step = (start - end) / (participants - 1)}. It divides by zero at one participant,
     * which is why a game can never start with fewer than two.
     *
     * @param startDiameter        the border's diameter at the start of the game
     * @param endDiameter          the floor the border never passes
     * @param effectiveParticipants the participant count AFTER countdown-time demotions, computed
     *                              once at start and fixed for the rest of the game
     * @return the step, a positive number of blocks of diameter
     * @throws IllegalArgumentException if {@code effectiveParticipants} is fewer than 2, or the
     *                                   diameters are not a valid start/end pair
     */
    public static double deathStep(final double startDiameter, final double endDiameter,
                                    final int effectiveParticipants) {
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
     * Where a death-triggered shrink should target next. A running shrink is extended rather than
     * restarted; the idle case falls out of the same formula by passing the border's actual size.
     *
     * @param currentTarget the border's current target diameter (its actual current size, if idle;
     *                      its in-flight target, if already shrinking)
     * @param step          the death step from {@link #deathStep(double, double, int)}
     * @param floor         the border's minimum diameter, never passed
     * @return the new target, clamped to {@code floor}
     */
    public static double nextShrinkTarget(final double currentTarget, final double step, final double floor) {
        return Math.max(floor, currentTarget - step);
    }

    /**
     * How long, in milliseconds, a death-triggered shrink from {@code fromDiameter} to
     * {@code toDiameter} takes at the configured wall speed.
     *
     * <p>The configured wall speed is already a diameter-change rate, so it is applied directly to
     * the diameter delta with no halving or doubling.</p>
     *
     * @param fromDiameter               the diameter the shrink starts from
     * @param toDiameter                 the diameter the shrink targets
     * @param wallSpeedDiameterPerSecond diameter-blocks per second, always positive
     * @return duration in milliseconds, at least 0
     */
    public static long shrinkDurationMillis(final double fromDiameter, final double toDiameter,
                                             final double wallSpeedDiameterPerSecond) {
        if (wallSpeedDiameterPerSecond <= 0) {
            throw new IllegalArgumentException("wallSpeedDiameterPerSecond must be positive");
        }
        final double delta = Math.abs(fromDiameter - toDiameter);
        return Math.round((delta / wallSpeedDiameterPerSecond) * 1000.0);
    }

    /**
     * How long, in milliseconds, a passive shrink from {@code fromDiameter} to {@code toDiameter}
     * takes at the configured passive rate.
     *
     * @param fromDiameter                  the diameter the passive shrink starts from
     * @param toDiameter                    the floor it targets
     * @param passiveShrinkDiameterPerHour   diameter-blocks per hour, always positive
     * @return duration in milliseconds, at least 0
     */
    public static long passiveShrinkDurationMillis(final double fromDiameter, final double toDiameter,
                                                     final double passiveShrinkDiameterPerHour) {
        if (passiveShrinkDiameterPerHour <= 0) {
            throw new IllegalArgumentException("passiveShrinkDiameterPerHour must be positive");
        }
        final double delta = Math.abs(fromDiameter - toDiameter);
        final double hours = delta / passiveShrinkDiameterPerHour;
        return Math.round(hours * 3_600_000.0);
    }
}
