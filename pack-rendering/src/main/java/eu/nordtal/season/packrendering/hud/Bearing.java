package eu.nordtal.season.packrendering.hud;

import eu.nordtal.season.packrendering.Glyphs;

/** Turns a position, a facing and a target into one of sixteen boss bar arrow indices. */
public final class Bearing {

    /** The advance of the widest arrow. */
    private static final int WIDEST = Glyphs.BOSSBAR_ARROWS.stream()
            .mapToInt(BossBarAdvances::width)
            .max()
            .orElseThrow();

    private Bearing() {}

    /** Returns the arrow of {@code index}, padded to the widest arrow, so a pill keeps its width while it turns. */
    public static String arrow(final int index) {
        final String glyph = Glyphs.BOSSBAR_ARROWS.get(index);
        return glyph + BossBarLine.right(WIDEST - BossBarAdvances.width(glyph));
    }

    /**
     * Returns the {@code Glyphs.BOSSBAR_ARROWS} index of a target, 0 straight ahead, clockwise.
     *
     * @param playerYawDegrees the player's yaw in Minecraft's convention
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

        // Minecraft's yaw: 0 faces +Z, clockwise.
        final double targetBearing = Math.toDegrees(Math.atan2(-dx, dz));
        final double relative = normalizeDegrees(targetBearing - playerYawDegrees);

        return Math.floorMod(Math.round((float) (relative / 22.5)), 16);
    }

    private static double normalizeDegrees(final double degrees) {
        double normalized = degrees % 360.0;
        if (normalized < 0) {
            normalized += 360.0;
        }
        return normalized;
    }
}
