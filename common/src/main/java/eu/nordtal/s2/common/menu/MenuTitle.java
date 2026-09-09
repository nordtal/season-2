package eu.nordtal.s2.common.menu;

import eu.nordtal.s2.common.Glyphs;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;

import java.util.ArrayList;
import java.util.List;

/**
 * Composes a chest menu's title so the window is drawn in Nordtal's own frame.
 *
 * <p>A menu is an ordinary chest inventory whose title carries a bitmap glyph big enough to cover
 * the window, on a large positive {@code ascent}. The client renders labels after the background, so
 * the panel is painted on top of the vanilla texture rather than instead of it.
 *
 * <p>The arithmetic lives here rather than at every call site. Vanilla draws the container title at
 * {@link #ANCHOR_X}, and a 176px bitmap glyph advances the cursor by {@link #PANEL_ADVANCE} - 177,
 * because every bitmap glyph gets one trailing pixel - so the readable title walks back 169. The net
 * displacement is zero: the title reads exactly where it would with no panel at all.
 *
 * <p>A surface that varies per player gets one panel plus a small glyph per state, drawn on top by a
 * {@link Canvas}; vertical position is the glyph's own {@code ascent}, which is why a state that can
 * land on two rows is declared twice. For list menus the row is carried by the font
 * ({@link Glyphs#FONT_GUI_ROWS}) rather than by the code point, and a canvas draws in insertion
 * order, which needs the positive advances as well as the negative ones.
 *
 * <p>Two traps: <b>the panel must be white</b>, because vanilla paints an inventory title in
 * hardcoded dark grey unless the component names a colour; and <b>the panel must name its font</b>,
 * because a panel code point left in {@code minecraft:default} draws whatever that font holds there.
 * The readable title names no font on purpose, so it renders where the letters are.
 */
public final class MenuTitle {

    /** Vanilla's {@code titleLabelX} for a chest screen: the title is drawn eight pixels in. */
    public static final int ANCHOR_X = 8;

    /** A 176px panel glyph advances the cursor by its width plus the trailing pixel. */
    public static final int PANEL_ADVANCE = 177;

    /** The number of chest sizes there are, which is the number of panels the pack draws. */
    public static final int MAX_ROWS = 6;

    /** The largest shift {@link Glyphs}' eight negative advances can add up to. */
    private static final int MAX_SHIFT = 255;

    private static final int[] SHIFTS = {128, 64, 32, 16, 8, 4, 2, 1};

    private static final String[] SHIFT_GLYPHS = {
            Glyphs.GUI_SPACE_MINUS_128, Glyphs.GUI_SPACE_MINUS_64, Glyphs.GUI_SPACE_MINUS_32,
            Glyphs.GUI_SPACE_MINUS_16, Glyphs.GUI_SPACE_MINUS_8, Glyphs.GUI_SPACE_MINUS_4,
            Glyphs.GUI_SPACE_MINUS_2, Glyphs.GUI_SPACE_MINUS_1,
    };

    private static final String[] FORWARD_GLYPHS = {
            Glyphs.GUI_SPACE_PLUS_128, Glyphs.GUI_SPACE_PLUS_64, Glyphs.GUI_SPACE_PLUS_32,
            Glyphs.GUI_SPACE_PLUS_16, Glyphs.GUI_SPACE_PLUS_8, Glyphs.GUI_SPACE_PLUS_4,
            Glyphs.GUI_SPACE_PLUS_2, Glyphs.GUI_SPACE_PLUS_1,
    };

    private MenuTitle() {
    }

    /**
     * The title to hand {@code Bukkit.createInventory}: the panel for this many rows, then the
     * readable title in its usual place.
     *
     * @param rows  chest rows, 1 to {@value #MAX_ROWS}
     * @param title what the player should read, already translated and coloured
     * @throws IllegalArgumentException on a row count the pack has no panel for - failing here
     *                                  beats a menu opening with a missing-glyph box for a frame
     */
    public static Component of(final int rows, final Component title) {
        if (rows < 1 || rows > MAX_ROWS) {
            throw new IllegalArgumentException(
                    "a chest menu has 1 to " + MAX_ROWS + " rows, not " + rows);
        }
        return Component.empty()
                .append(panel(rows))
                .append(title);
    }

    /**
     * Just the panel half, for a caller that has to build the title component itself.
     *
     * <p>It ends where it started, so anything appended after it sits at the title anchor exactly
     * as if the panel were not there.
     *
     * <p><b>The panel carries no shadow</b>: vanilla draws every glyph a second time one pixel down
     * and right, which on an opaque 176-pixel panel is a dark edge nothing in the pack drew.
     * {@link #of} appends the readable title to {@code Component.empty()} rather than to this
     * component, so the title stays a sibling and keeps its own shadow.
     */
    public static Component panel(final int rows) {
        return on(Glyphs.GUI_PANELS[rows - 1]).panel();
    }

    /**
     * The same, on the panel that has <b>no container slot recesses</b> - for a menu that paints
     * across whole rows, where a recess would show around every pill. The player's own rows and the
     * hotbar keep theirs in both variants.
     */
    public static Component panelPlain(final int rows) {
        return onPlain(rows).panel();
    }

    /** A canvas on the recess-free panel for {@code rows} rows. */
    public static Canvas onPlain(final int rows) {
        if (rows < 1 || rows > MAX_ROWS) {
            throw new IllegalArgumentException(
                    "a chest menu has 1 to " + MAX_ROWS + " rows, not " + rows);
        }
        return on(Glyphs.GUI_PANELS_PLAIN[rows - 1]);
    }

    /**
     * Starts a title on a full-window panel glyph - one of {@link Glyphs#GUI_PANELS}, or a menu's
     * own such as {@link Glyphs#GUI_TRAVEL_PANEL} - to which overlays can be added.
     *
     * @param panelGlyph a 176px-wide {@code nordtal:gui} glyph on ascent 13
     */
    public static Canvas on(final String panelGlyph) {
        return new Canvas(panelGlyph);
    }

    /**
     * The glyphs that move the cursor {@code pixels} to the left, largest advance first.
     *
     * <p>The eight advances are powers of two, so this is the number's binary representation. Zero
     * is the empty string rather than an error.
     */
    public static String shift(final int pixels) {
        if (pixels < 0 || pixels > MAX_SHIFT) {
            throw new IllegalArgumentException(
                    "nordtal:gui carries advances for 1.." + MAX_SHIFT + " pixels, not " + pixels);
        }
        final StringBuilder out = new StringBuilder();
        int left = pixels;
        for (int index = 0; index < SHIFTS.length; index++) {
            if (left >= SHIFTS[index]) {
                out.append(SHIFT_GLYPHS[index]);
                left -= SHIFTS[index];
            }
        }
        return out.toString();
    }

    /**
     * The glyphs that move the cursor {@code pixels} to the <b>right</b>, largest advance first -
     * the mirror of {@link #shift(int)}, needed because a row is composed left to right.
     */
    public static String forward(final int pixels) {
        if (pixels < 0 || pixels > MAX_SHIFT) {
            throw new IllegalArgumentException(
                    "nordtal:gui carries advances for 1.." + MAX_SHIFT + " pixels, not " + pixels);
        }
        final StringBuilder out = new StringBuilder();
        int left = pixels;
        for (int index = 0; index < SHIFTS.length; index++) {
            if (left >= SHIFTS[index]) {
                out.append(FORWARD_GLYPHS[index]);
                left -= SHIFTS[index];
            }
        }
        return out.toString();
    }

    /** Moves the cursor by {@code pixels}, right when positive and left when negative. */
    public static String move(final int pixels) {
        return pixels < 0 ? shift(-pixels) : forward(pixels);
    }

    /**
     * One thing drawn on top of the panel: art or text, at a window x, in a font of its own.
     *
     * @param x       the left edge in window pixels
     * @param advance how far the cursor moves for the whole of {@code content}
     * @param content the code points, already composed
     * @param font    the font id to name, or null to inherit {@code nordtal:gui} from the panel
     * @param colour  the colour to paint it, or null to inherit the panel's white
     */
    private record Overlay(int x, int advance, String content, String font, TextColor colour) {
    }

    /**
     * A panel with things drawn on top of it, composed into one title.
     *
     * <p>Draw order is insertion order, so a pill added before its label is painted under it.
     *
     * <p>A placement carries a font because a glyph's only vertical control is its font's
     * {@code ascent}, so "on chest row 2" is a font and not a coordinate. A placement naming no font
     * inherits the panel's {@code nordtal:gui}, and one naming no colour inherits the panel's white.
     */
    public static final class Canvas {

        private final String panelGlyph;
        private final List<Overlay> overlays = new ArrayList<>();

        private Canvas(final String panelGlyph) {
            this.panelGlyph = panelGlyph;
        }

        /**
         * Draws {@code glyph} on top of the panel with its left edge at window {@code x}.
         *
         * @param glyph a {@code nordtal:gui} glyph declared at the ascent of the row it lands on
         * @param x     the overlay's left edge in window pixels, 0 to {@code 176 - width}
         * @param width the glyph's drawn width - its advance is one more
         */
        public Canvas overlay(final String glyph, final int x, final int width) {
            return place(x, width + 1, glyph, null, null);
        }

        /**
         * Draws one row glyph - a pill, a frame, a button plate, a pictogram - on a chest row.
         *
         * <p>The row picks the font and the glyph picks the picture; the advance comes from
         * {@link MenuFont}, which reads it out of the same export the pack was generated with.
         *
         * @param glyph  a {@code GUI_ROW_*} code point
         * @param row    the chest row, 0 to {@code MAX_ROWS - 1}
         * @param x      its left edge in window pixels
         * @param colour null to leave it white, which is how the art is drawn
         */
        public Canvas rowArt(final String glyph, final int row, final int x, final TextColor colour) {
            return place(x, MenuFont.advance(glyph.codePointAt(0)), glyph, rowFont(row), colour);
        }

        /**
         * Draws readable text on a chest row, in the pack's five-pixel capitals.
         *
         * <p>The text is folded onto the sheet's alphabet by {@link MenuFont#fold(String)} first, so
         * what is measured is what is drawn; a caller that needs it to fit should use
         * {@link MenuFont#fit(String, int)}.
         *
         * @param text   any string; folded to capitals here
         * @param row    the chest row, 0 to {@code MAX_ROWS - 1}
         * @param x      the text's left edge in window pixels
         * @param colour what to paint it - never null, because the panel's white is unreadable on it
         */
        public Canvas rowText(final String text, final int row, final int x, final TextColor colour) {
            final String folded = MenuFont.fold(text);
            // Nothing to draw is not an error: a bundle key an operator has blanked, or a distance
            // that does not apply, should leave the row alone rather than refuse the whole menu.
            if (folded.isEmpty()) {
                rowFont(row);
                return this;
            }
            return place(x, MenuFont.width(folded), folded, rowFont(row), colour);
        }

        /** The same, with the text's <em>right</em> edge at {@code xRight}. */
        public Canvas rowTextRight(final String text, final int row, final int xRight,
                                   final TextColor colour) {
            final String folded = MenuFont.fold(text);
            return rowText(folded, row, xRight - MenuFont.width(folded), colour);
        }

        private static String rowFont(final int row) {
            if (row < 0 || row >= MAX_ROWS) {
                throw new IllegalArgumentException("there is no chest row " + row);
            }
            return Glyphs.FONT_GUI_ROWS[row];
        }

        private Canvas place(final int x, final int advance, final String content,
                             final String font, final TextColor colour) {
            if (x < 0 || advance < 1 || x + advance > PANEL_ADVANCE) {
                throw new IllegalArgumentException("a placement advancing " + advance
                        + " at x = " + x + " does not fit a 176px window");
            }
            overlays.add(new Overlay(x, advance, content, font, colour));
            return this;
        }

        /** The composed surface: panel and overlays, ending on the title anchor - no readable text. */
        public Component panel() {
            Component surface = Component.text(shift(ANCHOR_X) + panelGlyph)
                    .font(Key.key(Glyphs.FONT_GUI))
                    .color(NamedTextColor.WHITE)
                    .shadowColor(ShadowColor.none());
            int cursor = PANEL_ADVANCE;

            for (final Overlay overlay : overlays) {
                Component run = Component.text(move(overlay.x() - cursor) + overlay.content());
                if (overlay.font() != null) {
                    run = run.font(Key.key(overlay.font()));
                }
                if (overlay.colour() != null) {
                    run = run.color(overlay.colour());
                }
                surface = surface.append(run);
                cursor = overlay.x() + overlay.advance();
            }
            // The walk home names no font, so it inherits the panel's - which is why every row font
            // carries the same eight advances nordtal:gui does.
            return surface.append(Component.text(move(ANCHOR_X - cursor)));
        }

        /** The title to hand {@code Bukkit.createInventory}: the surface, then {@code title}. */
        public Component build(final Component title) {
            return Component.empty()
                    .append(panel())
                    .append(title);
        }
    }
}
