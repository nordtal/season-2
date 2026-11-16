package eu.nordtal.season.papercommon.npc;

/** The angles between a player and an NPC, in Minecraft's degrees: yaw 0 faces south, pitch grows downwards. */
public final class Gaze {

    private Gaze() {}

    /** Returns the yaw that faces along {@code dx} and {@code dz}. */
    public static double yawTo(final double dx, final double dz) {
        return Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** Returns the pitch that faces along {@code dx}, {@code dy} and {@code dz}. */
    public static double pitchTo(final double dx, final double dy, final double dz) {
        return -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
    }

    /** Returns {@code degrees} turned into the range from -180 up to 180. */
    public static double wrap(final double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped >= 180.0) {
            wrapped -= 360.0;
        }
        if (wrapped < -180.0) {
            wrapped += 360.0;
        }
        return wrapped;
    }

    /**
     * Returns how far beyond {@code start} blocks a player stands and how far their yaw turned from the NPC.
     * The dialogue began {@code start} blocks away. The offsets go from the player's eye to the look point.
     */
    public static Where where(
            final double start, final double dx, final double dz, final double distance, final double yaw) {
        return new Where(distance - start, Math.abs(wrap(yawTo(dx, dz) - yaw)));
    }
}
