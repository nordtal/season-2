package eu.nordtal.s2.common.hud;

import eu.nordtal.s2.common.Glyphs;

/**
 * Composes a HUD pill background of any inner width from {@code Glyphs.BOSSBAR_BG_*}.
 *
 * The client advances a bitmap glyph by its width plus one, so every glyph is followed by
 * {@link Glyphs#BOSSBAR_SPACE_MINUS_1}; {@link BossBarWidthTest} walks the pack's advances to hold it.
 */
public final class BossBarWidth {

    /** The drawn width of {@code start.png} and {@code end.png}. */
    public static final int CAP = 4;

    /** Segment widths this class can compose with, largest first - mirrors {@code Glyphs.BOSSBAR_BG_*}. */
    private static final int[] SEGMENT_WIDTHS = {128, 64, 32, 16, 8, 4, 2, 1};

    private BossBarWidth() {}

    /**
     * Returns a whole pill that advances the cursor by exactly {@code CAP + inner + CAP}.
     *
     * @param inner the body width in pixels, zero or more
     */
    public static String pill(final int inner) {
        return seamless(Glyphs.BOSSBAR_BG_START) + body(inner) + seamless(Glyphs.BOSSBAR_BG_END);
    }

    /**
     * Returns {@code width} pixels of power-of-two segments, largest first, each stepped back by one.
     *
     * @throws IllegalArgumentException if {@code width} is negative
     */
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

    /** The glyph, then the one-pixel step back that cancels the client's separator. */
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
