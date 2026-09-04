package eu.nordtal.s2.common.hud;

import eu.nordtal.s2.common.Glyphs;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the frame around a Text Display board out of {@code nordtal:board}.
 *
 * <h2>Why the width is a number somebody chose</h2>
 * The obvious rule - make the board as wide as its longest line - cannot be implemented here, and
 * it is worth being exact about why rather than leaving it to be rediscovered. The board's text
 * renders in {@code minecraft:default}, whose per-character advances live in the <b>client</b>
 * jar; this repository has no copy of them, the pack's own {@code ascii.png} belongs to
 * {@code nordtal:bossbar} and not to the default font, and a table typed in from memory is exactly
 * the kind of constant this project has had to correct before. A player name, a milestone title
 * out of a YAML file and a progress bar drawn from {@code U+2588} would each need a different
 * source for that answer.
 *
 * <p>So the width is configuration - {@code config.yml}'s {@code boards[].width}, in pixels,
 * decided by the owner on 2026-09-04. A line that outgrows it draws over the right-hand edge,
 * which is visible immediately and is fixed by editing one number without a release. The
 * alternative - wrapping - would put a continuation line outside the box, which looks like a bug
 * rather than like a board that wants to be wider.
 *
 * <h2>The arithmetic</h2>
 * Every bitmap glyph advances the cursor by <b>one pixel more</b> than it is wide (Minecraft takes
 * the rightmost column with any alpha, plus one, and then adds the separator) - which is the whole
 * reason {@code nordtal:board} carries a negative-advance space provider at all. So every glyph
 * here is emitted with a {@code -1} behind it and the pieces butt up seamlessly, the way
 * {@code resource-pack/README.md} says they are drawn to.
 *
 * <p>The left corner is 9px wide and the right one 6, because the corners are drawn as a single
 * line bent at the centre of a 9px cell: the top-left one's stub reaches right across its whole
 * cell, the top-right one's comes in from the left and stops at the centre. That asymmetry is a
 * fact about the art, so {@code BoardFrameTest} re-derives all three widths from the PNGs rather
 * than trusting the constants below.
 *
 * <p>A content line draws its <b>right</b> edge before its content, walking out to a known x and
 * back again. That is the trick the whole class turns on: the cursor's position after the content
 * is exactly the thing that cannot be computed, so nothing is ever placed after it.
 */
public final class BoardFrame {

    /** Where the content column starts, which is also where the horizontal edges start. */
    public static final int CONTENT_X = 9;

    /** Trimmed width of {@code corner_tl} and {@code corner_bl} - a full cell, stub reaching right. */
    public static final int CORNER_LEFT_WIDTH = 9;

    /** Trimmed width of {@code corner_tr} and {@code corner_br} - stub coming in from the left. */
    public static final int CORNER_RIGHT_WIDTH = 6;

    /** Trimmed width of {@code edge_v_l} and {@code edge_v_r}. */
    public static final int EDGE_V_WIDTH = 6;

    /**
     * Widths the content column may be set to.
     *
     * <p>The ceiling is the negative shift's: a content line walks back {@code width + 6} pixels,
     * and eight advances of 1 to 128 add up to 255. The floor is judgement - a board narrower than
     * this holds no line worth framing.
     */
    public static final int MIN_WIDTH = 32;

    /** @see #MIN_WIDTH */
    public static final int MAX_WIDTH = 240;

    private static final int[] POWERS = {128, 64, 32, 16, 8, 4, 2, 1};

    private static final String[] MINUS = {
            Glyphs.BOARD_SPACE_MINUS_128, Glyphs.BOARD_SPACE_MINUS_64,
            Glyphs.BOARD_SPACE_MINUS_32, Glyphs.BOARD_SPACE_MINUS_16,
            Glyphs.BOARD_SPACE_MINUS_8, Glyphs.BOARD_SPACE_MINUS_4,
            Glyphs.BOARD_SPACE_MINUS_2, Glyphs.BOARD_SPACE_MINUS_1,
    };

    private static final int[] PLUS_PIXELS = {32, 16, 8, 4, 2, 1};

    private static final String[] PLUS = {
            Glyphs.BOARD_SPACE_PLUS_32, Glyphs.BOARD_SPACE_PLUS_16, Glyphs.BOARD_SPACE_PLUS_8,
            Glyphs.BOARD_SPACE_PLUS_4, Glyphs.BOARD_SPACE_PLUS_2, Glyphs.BOARD_SPACE_PLUS_1,
    };

    private static final String[] EDGES_H = {
            Glyphs.BOARD_EDGE_H_128, Glyphs.BOARD_EDGE_H_64, Glyphs.BOARD_EDGE_H_32,
            Glyphs.BOARD_EDGE_H_16, Glyphs.BOARD_EDGE_H_8, Glyphs.BOARD_EDGE_H_4,
            Glyphs.BOARD_EDGE_H_2, Glyphs.BOARD_EDGE_H_1,
    };

    private static final String[] DIVIDERS = {
            Glyphs.BOARD_DIVIDER_128, Glyphs.BOARD_DIVIDER_64, Glyphs.BOARD_DIVIDER_32,
            Glyphs.BOARD_DIVIDER_16, Glyphs.BOARD_DIVIDER_8, Glyphs.BOARD_DIVIDER_4,
            Glyphs.BOARD_DIVIDER_2, Glyphs.BOARD_DIVIDER_1,
    };

    private BoardFrame() {
    }

    /**
     * The whole board: a top border, the title, a rule under it, the lines, and a bottom border.
     *
     * <p>The divider is drawn - the owner's written fallback of 2026-09-04, and the reason the
     * pack allocates eight divider glyphs separately from the eight edge ones.
     *
     * @param width  the content column in pixels, {@value #MIN_WIDTH} to {@value #MAX_WIDTH}
     * @param title  the heading, already translated and coloured
     * @param lines  the body, one component per line, already translated and coloured
     * @throws IllegalArgumentException on a width the shifts cannot express - a configuration
     *                                  error, and one the plugin's validator should have caught
     *                                  before a single board was drawn
     */
    public static Component render(final int width, final Component title,
                                   final List<Component> lines) {
        checkWidth(width);

        final List<Component> out = new ArrayList<>(lines.size() + 4);
        out.add(border(width, Glyphs.BOARD_CORNER_TOP_LEFT, Glyphs.BOARD_CORNER_TOP_RIGHT));
        out.add(row(width, title));
        out.add(row(width, frameText(tile(width, DIVIDERS))));
        for (final Component line : lines) {
            out.add(row(width, line));
        }
        out.add(border(width, Glyphs.BOARD_CORNER_BOTTOM_LEFT, Glyphs.BOARD_CORNER_BOTTOM_RIGHT));

        Component board = Component.empty();
        for (int index = 0; index < out.size(); index++) {
            if (index > 0) {
                board = board.append(Component.newline());
            }
            board = board.append(out.get(index));
        }
        return board;
    }

    /** One horizontal border: a corner, the edge tiled to width, and the other corner. */
    public static Component border(final int width, final String leftCorner,
                                   final String rightCorner) {
        checkWidth(width);
        return frameText(glyph(leftCorner) + tile(width, EDGES_H) + glyph(rightCorner));
    }

    /**
     * One line of the box: both vertical edges, then the content, starting at {@link #CONTENT_X}.
     *
     * <p>The order is the point. The right edge is placed first, at a position derived only from
     * the configured width, and the cursor is then walked back to the content column - so nothing
     * in this composition ever has to know how wide the content turns out to be.
     */
    public static Component row(final int width, final Component content) {
        checkWidth(width);
        // Cursor, in pixels: 0 -> EDGE_V_WIDTH -> CONTENT_X + width -> CONTENT_X + width +
        // EDGE_V_WIDTH -> CONTENT_X. The last step is what MAX_WIDTH is derived from.
        final String edges = glyph(Glyphs.BOARD_EDGE_V_LEFT)
                + right(CONTENT_X + width - EDGE_V_WIDTH)
                + glyph(Glyphs.BOARD_EDGE_V_RIGHT)
                + left(width + EDGE_V_WIDTH);
        return Component.empty().append(frameText(edges)).append(content);
    }

    /**
     * Tiles a power-of-two glyph set to exactly {@code width} pixels.
     *
     * <p>Same decomposition {@link BossBarWidth} does for the boss bar, and deliberately not shared
     * with it: that one ends in a cap glyph this frame has no equivalent of, and every segment here
     * carries its own {@code -1}.
     */
    private static String tile(final int width, final String[] segments) {
        final StringBuilder out = new StringBuilder();
        int left = width;
        for (int index = 0; index < POWERS.length; index++) {
            while (left >= POWERS[index]) {
                out.append(glyph(segments[index]));
                left -= POWERS[index];
            }
        }
        return out.toString();
    }

    /** A bitmap glyph plus the one pixel of trailing advance Minecraft gives it, taken back. */
    private static String glyph(final String codePoint) {
        return codePoint + Glyphs.BOARD_SPACE_MINUS_1;
    }

    /** Moves the cursor right, repeating the largest advance the font has. */
    public static String right(final int pixels) {
        if (pixels < 0) {
            throw new IllegalArgumentException("a rightward shift is not " + pixels);
        }
        final StringBuilder out = new StringBuilder();
        int left = pixels;
        for (int index = 0; index < PLUS_PIXELS.length; index++) {
            while (left >= PLUS_PIXELS[index]) {
                out.append(PLUS[index]);
                left -= PLUS_PIXELS[index];
            }
        }
        return out.toString();
    }

    /** Moves the cursor left; the eight negative advances reach 255 and no further. */
    public static String left(final int pixels) {
        if (pixels < 0 || pixels > 255) {
            throw new IllegalArgumentException(
                    "nordtal:board reaches 255 pixels leftward, not " + pixels);
        }
        final StringBuilder out = new StringBuilder();
        int remaining = pixels;
        for (int index = 0; index < POWERS.length; index++) {
            if (remaining >= POWERS[index]) {
                out.append(MINUS[index]);
                remaining -= POWERS[index];
            }
        }
        return out.toString();
    }

    /**
     * A frame component: {@code nordtal:board}, white, shadowless, and italic-free.
     *
     * <p>All three halves matter. A component that names no font resolves its code points in
     * {@code minecraft:default}, where they are other glyphs entirely - the mistake
     * {@link Glyphs#FONT_BOSSBAR} documents. A component that names no colour inherits the parent's,
     * and a board whose heading is gold would then have a gold frame.
     *
     * <p><b>And a component that names no shadow gets vanilla's</b>, which is the whole glyph drawn
     * again one pixel down and right. On a line of text that is what text is supposed to look like;
     * on a frame composed of tiles butted against each other it is a dark ghost bleeding out of
     * every tile into its neighbour, and the seam it draws is exactly where the composition was
     * meant to be invisible. The shadow costs no advance, so nothing here moves - it only looks
     * broken, which is why it survives a reading of the arithmetic.
     */
    private static Component frameText(final String composed) {
        return Component.text(composed)
                .font(Key.key(Glyphs.FONT_BOARD))
                .color(NamedTextColor.WHITE)
                .shadowColor(ShadowColor.none());
    }

    private static void checkWidth(final int width) {
        if (width < MIN_WIDTH || width > MAX_WIDTH) {
            throw new IllegalArgumentException("a board is " + MIN_WIDTH + " to " + MAX_WIDTH
                    + " pixels wide, not " + width);
        }
    }
}
