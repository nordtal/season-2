package eu.nordtal.s2.common.hud;

/** Turns a player's position and facing plus a target into one of the sixteen boss bar arrow indices. */
public final class Bearing {

    private Bearing() {}

    /**
     * Returns the {@code Glyphs.BOSSBAR_ARROWS} index for a target; 0 is straight ahead, clockwise from there.
     *
     * @param playerYawDegrees the player's yaw, in Minecraft's convention (0 = south/+Z, clockwise)
     * @return an index in {@code [0, 16)}; 0 when the target is exactly at the player's position
     */
    public static int arrowIndex(
            final double playerX,
            final double playerZ,
            final double playerYawDegrees,
            final double targetX,
            final double targetZ) {
        final double dx = targetX - playerX;
        final double dz = targetZ - playerZ;
        if (dx == 0 && dz == 0) {
            return 0;
        }

        // atan2(-dx, dz) is the bearing in Minecraft's yaw convention: 0 faces +Z, clockwise.
        final double targetBearing = Math.toDegrees(Math.atan2(-dx, dz));
        final double relative = normalizeDegrees(targetBearing - playerYawDegrees);

        return Math.floorMod(Math.round((float) (relative / 22.5)), 16);
    }

    /** Normalises an angle to {@code [0, 360)}. */
    private static double normalizeDegrees(final double degrees) {
        double normalized = degrees % 360.0;
        if (normalized < 0) {
            normalized += 360.0;
        }
        return normalized;
    }
}
