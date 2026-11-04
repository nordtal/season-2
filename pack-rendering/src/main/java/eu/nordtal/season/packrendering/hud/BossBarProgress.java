package eu.nordtal.season.packrendering.hud;

import eu.nordtal.season.packrendering.Glyphs;

/**
 * Composes a HUD pill's progress bar from {@code Glyphs.BOSSBAR_RIDE_*}.
 * The part done is drawn over the empty track from the left, and the whole advances like a glyph of its width,
 * so it stands where a pill's icon stands.
 */
public final class BossBarProgress {

    /** The drawn width of the track, its outline included. */
    public static final int TRACK = 100;

    /** The pixels inside the track's outline, which the part done fills. */
    public static final int INNER = TRACK - 2;

    /** The widths of the fill segments, largest first. */
    private static final int[] SEGMENT_WIDTHS = {64, 32, 16, 8, 4, 2, 1};

    private BossBarProgress() {}

    /** Returns the bar at {@code progress}, from 0 to 1; a value outside is taken as the nearest end. */
    public static String bar(final double progress) {
        final int filled = filled(progress);
        // The track advances one pixel past its last column, as every glyph does; the fill starts inside its outline.
        return Glyphs.BOSSBAR_RIDE_TRACK + BossBarLine.left(TRACK) + fill(filled) + BossBarLine.right(TRACK - filled);
    }

    /** Returns how many of the {@link #INNER} pixels {@code progress} fills. */
    public static int filled(final double progress) {
        if (Double.isNaN(progress) || progress <= 0) {
            return 0;
        }
        return (int) Math.round(Math.min(1, progress) * INNER);
    }

    private static String fill(final int width) {
        final StringBuilder out = new StringBuilder();
        int remaining = width;
        for (final int segment : SEGMENT_WIDTHS) {
            while (remaining >= segment) {
                out.append(glyphFor(segment)).append(Glyphs.BOSSBAR_SPACE_MINUS_1);
                remaining -= segment;
            }
        }
        return out.toString();
    }

    private static String glyphFor(final int segment) {
        return switch (segment) {
            case 64 -> Glyphs.BOSSBAR_RIDE_FILL_64;
            case 32 -> Glyphs.BOSSBAR_RIDE_FILL_32;
            case 16 -> Glyphs.BOSSBAR_RIDE_FILL_16;
            case 8 -> Glyphs.BOSSBAR_RIDE_FILL_8;
            case 4 -> Glyphs.BOSSBAR_RIDE_FILL_4;
            case 2 -> Glyphs.BOSSBAR_RIDE_FILL_2;
            case 1 -> Glyphs.BOSSBAR_RIDE_FILL_1;
            default -> throw new IllegalStateException("no fill segment of width " + segment);
        };
    }
}
