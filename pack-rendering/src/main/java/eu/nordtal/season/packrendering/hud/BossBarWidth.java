package eu.nordtal.season.packrendering.hud;

import eu.nordtal.season.packrendering.Glyphs;

/** Composes the background of a HUD pill of any inner width from {@code Glyphs.BOSSBAR_BG_*}. */
public final class BossBarWidth {

    /** The drawn width of {@code start.png} and {@code end.png}. */
    public static final int CAP = 4;

    /** The widths of the segments, largest first. */
    private static final int[] SEGMENT_WIDTHS = {128, 64, 32, 16, 8, 4, 2, 1};

    private BossBarWidth() {}

    /** Returns a pill whose advance is {@code CAP + inner + CAP}. */
    public static String pill(final int inner) {
        return seamless(Glyphs.BOSSBAR_BG_START) + body(inner) + seamless(Glyphs.BOSSBAR_BG_END);
    }

    /** Returns {@code width} pixels of power-of-two segments, largest first. */
    public static String body(final int width) {
        if (width < 0) {
            throw new IllegalArgumentException("width must not be negative, was " + width);
        }
        final StringBuilder out = new StringBuilder();
        int remaining = width;
        for (final int segment : SEGMENT_WIDTHS) {
            while (remaining >= segment) {
                out.append(seamless(glyphFor(segment)));
                remaining -= segment;
            }
        }
        return out.toString();
    }

    private static String seamless(final String glyph) {
        return glyph + Glyphs.BOSSBAR_SPACE_MINUS_1;
    }

    private static String glyphFor(final int segment) {
        return switch (segment) {
            case 128 -> Glyphs.BOSSBAR_BG_128;
            case 64 -> Glyphs.BOSSBAR_BG_64;
            case 32 -> Glyphs.BOSSBAR_BG_32;
            case 16 -> Glyphs.BOSSBAR_BG_16;
            case 8 -> Glyphs.BOSSBAR_BG_8;
            case 4 -> Glyphs.BOSSBAR_BG_4;
            case 2 -> Glyphs.BOSSBAR_BG_2;
            case 1 -> Glyphs.BOSSBAR_BG_1;
            default -> throw new IllegalStateException("no background segment of width " + segment);
        };
    }
}
