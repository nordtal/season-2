package eu.nordtal.s2.common.hud;

import eu.nordtal.s2.common.Glyphs;

/**
 * Composes a boss bar background of an arbitrary width out of {@code Glyphs.BOSSBAR_BG_*} segments
 * - the technique docs/hunger-games.md#the-hud describes: "background segments in powers of two, so
 * any width is composed from a handful of glyphs."
 * <p>
 * The segment glyphs are undrawn art (see {@code Glyphs}' own note), so this class produces the
 * correct sequence of code points regardless - exactly like the HUD's icon and arrow glyphs, the
 * code is correct today and will render once the art exists.
 * </p>
 *
 * <p><b>Moved into {@code :common} on 2026-09-01</b> because the SMP's HUD draws the same bar. The
 * arithmetic is one line of powers of two and would have been trivial to copy - which is exactly
 * why it was not: two copies of a glyph composition are two things that drift apart the first time
 * a segment is redrawn, and the resource pack is the one place this repository already keeps a
 * single source of truth for code points.</p>
 */
public final class BossBarWidth {

    /** Segment widths this class can compose with, largest first - mirrors {@code Glyphs.BOSSBAR_BG_*}. */
    private static final int[] SEGMENT_WIDTHS = {128, 64, 32, 16, 8, 4, 2, 1};

    private BossBarWidth() {
    }

    /**
     * Greedily composes {@code width} out of the available power-of-two background segments -
     * standard binary decomposition, since the available segment widths are exactly the powers of
     * two {@code Glyphs} declares.
     *
     * @param width the desired background width in whatever unit the glyphs are drawn at; must not
     *              be negative
     * @return the background string, {@link Glyphs#BOSSBAR_BG_END} last
     * @throws IllegalArgumentException if {@code width} is negative
     */
    public static String compose(final int width) {
        if (width < 0) {
            throw new IllegalArgumentException("width must not be negative, was " + width);
        }

        final StringBuilder out = new StringBuilder();
        int remaining = width;
        for (final int segment : SEGMENT_WIDTHS) {
            while (remaining >= segment) {
                out.append(glyphFor(segment));
                remaining -= segment;
            }
        }
        out.append(Glyphs.BOSSBAR_BG_END);
        return out.toString();
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
            default -> throw new IllegalStateException("Unreachable: " + segment);
        };
    }
}
