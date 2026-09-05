package eu.nordtal.s2.common.hud;

import eu.nordtal.s2.common.Glyphs;

/**
 * Composes a HUD pill's background of an arbitrary inner width out of {@code Glyphs.BOSSBAR_BG_*}:
 * the left cap, a body of power-of-two segments, the right cap.
 *
 * <h2>The one fact this class exists for</h2>
 * Every segment is drawn exactly as wide as its name, and the client advances a bitmap glyph by
 * its width <b>plus one</b>. Two segments butted together therefore leave a one-pixel gap unless
 * the composer steps back a pixel after each - so every glyph here is followed by
 * {@link Glyphs#BOSSBAR_SPACE_MINUS_1}, the way {@code BoardFrame} has always done for the board.
 * The 182 px bar this class composed until 2026-09-05 did not, and had a seam at every boundary
 * that nothing ever looked at; {@link BossBarWidthTest} now walks the composition with the pack's
 * own advances and asserts the cursor lands exactly {@code CAP + inner + CAP} to the right.
 *
 * <p>Moved into {@code :common} on 2026-09-01 because the SMP's HUD draws the same bar as the hunger
 * games'; two copies of a glyph composition are two things that drift apart the first time a
 * segment is redrawn.</p>
 */
public final class BossBarWidth {

    /** The drawn width of {@code start.png} and {@code end.png}. */
    public static final int CAP = 4;

    /** Segment widths this class can compose with, largest first - mirrors {@code Glyphs.BOSSBAR_BG_*}. */
    private static final int[] SEGMENT_WIDTHS = {128, 64, 32, 16, 8, 4, 2, 1};

    private BossBarWidth() {
    }

    /**
     * A whole pill: left cap, {@code inner} pixels of body, right cap - advancing the cursor by
     * exactly {@code CAP + inner + CAP}.
     *
     * @param inner the body width in pixels, zero or more
     */
    public static String pill(final int inner) {
        return seamless(Glyphs.BOSSBAR_BG_START) + body(inner) + seamless(Glyphs.BOSSBAR_BG_END);
    }

    /**
     * Just the body: {@code width} pixels of segments, largest first, each stepped back by one so
     * they butt up - standard binary decomposition, since the segment widths are exactly the powers
     * of two {@code Glyphs} declares.
     *
     * @param width the body width in pixels; must not be negative
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
