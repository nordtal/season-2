package eu.nordtal.season.smp.wall;

import org.jspecify.annotations.Nullable;

/** The push back inside for a player who walked through the wall. */
final class Push {

    static final double HORIZONTAL = 1.4;
    static final double UP = 0.35;

    /** A player is pushed at most this often, in milliseconds. */
    static final long PAUSE = 500;

    private Push() {}

    /** The velocity {x, y, z} that pushes a player at {@code x}, {@code z} back inside, or null while inside. */
    static double @Nullable [] velocity(final Wall wall, final double x, final double z) {
        if (!wall.hasOutline() || wall.inside(x, z)) {
            return null;
        }
        final double[] inward = wall.inward(x, z);
        return new double[] {inward[0] * HORIZONTAL, UP, inward[1] * HORIZONTAL};
    }

    /** Whether a player last pushed at {@code last} may be pushed again at {@code now}. */
    static boolean due(final long last, final long now) {
        return now - last >= PAUSE;
    }
}
