package eu.nordtal.s2.common.hud;

/**
 * Turns a player's position and facing plus a target position into one of the sixteen
 * {@code Glyphs.BOSSBAR_ARROWS} indices - shared by the "nearest living player" arrow and the
 * "nearest loot point" direction, per {@code docs/hunger-games.md#the-hud}.
 * <p>
 * No Bukkit type appears here: {@code yaw} and the four coordinates are plain doubles, so the
 * whole calculation is a plain unit test. The plugin's HUD code passes
 * {@code player.getLocation().getYaw()} and the relevant X/Z pairs in.
 * </p>
 *
 * <p><b>Moved into {@code :common} on 2026-09-01</b>: {@code /navigate}'s second HUD line points at
 * a POI with the same sixteen arrows the hunger games point at the border with. One allocation of
 * {@code U+EF10}-{@code U+EF1F}, one mapping from an angle to it.</p>
 */
public final class Bearing {

    private Bearing() {
    }

    /**
     * The index into {@code Glyphs.BOSSBAR_ARROWS} for a target relative to a player's position and
     * yaw - 0 is straight ahead, and the array is documented as clockwise from there.
     * <p>
     * Minecraft yaw is 0 at south (+Z) and increases clockwise; the arrow at index 0 means "straight
     * ahead", i.e. the target is in the direction the player is currently facing.
     * </p>
     *
     * @param playerX player X
     * @param playerZ player Z
     * @param playerYawDegrees the player's yaw, in Minecraft's own convention (0 = south/+Z, clockwise)
     * @param targetX target X
     * @param targetZ target Z
     * @return an index in {@code [0, 16)}; when the target is exactly at the player's position, 0
     */
    public static int arrowIndex(final double playerX, final double playerZ, final double playerYawDegrees,
                                  final double targetX, final double targetZ) {
        final double dx = targetX - playerX;
        final double dz = targetZ - playerZ;
        if (dx == 0 && dz == 0) {
            return 0;
        }

        // atan2(-dx, dz) is the compass bearing to the target in Minecraft's yaw convention: yaw 0
        // faces +Z (south), and yaw increases clockwise toward +X (west is -X at yaw 90 the other
        // way) - this is the same convention Minecraft's own F3 yaw readout uses.
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
