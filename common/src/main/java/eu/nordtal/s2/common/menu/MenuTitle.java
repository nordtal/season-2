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
 * <h2>What this actually does</h2>
 * A menu on this server is an ordinary chest inventory. Its <b>title</b> carries a bitmap glyph
 * large enough to cover the whole window, on a large positive {@code ascent} so it rises out of the
 * title's baseline and fills the screen behind the slots. The client renders labels <em>after</em>
 * the background, so the panel is painted on top of {@code generic_54.png} rather than instead of
 * it - which is the whole reason the panel is opaque and the vanilla texture is left alone. See
 * {@code docs/presentation.md} section 2.
 *
 * <h2>The arithmetic, and why it is here rather than at six call sites</h2>
 * Three numbers, all of them vanilla's and none of them ours:
 *
 * <ul>
 *   <li>The container title is drawn at <b>x = 8</b> inside the window, so the cursor starts eight
 *       pixels right of the left edge. {@link #ANCHOR_X}.</li>
 *   <li>A 176px-wide bitmap glyph advances the cursor by <b>177</b>, not 176 - every bitmap glyph
 *       gets one trailing pixel. Assuming otherwise puts every menu one pixel out, in the same
 *       direction, forever. {@link #PANEL_ADVANCE}.</li>
 *   <li>So the readable title has to walk back <b>169</b> pixels to land where it started.</li>
 * </ul>
 *
 * The net displacement is {@code -8 + 177 - 169 = 0}: the title reads exactly where it would have
 * read with no panel at all, which is the invariant {@code MenuTitleTest} pins. Getting one of
 * these wrong costs one menu here and six if each menu did its own sum.
 *
 * <h2>Overlays (2026-09-05)</h2>
 * A menu whose surface varies per player - the balloon, whose cards are locked or not - does not
 * get a panel per combination. It gets one panel and a small glyph per <em>state</em>, drawn on top
 * of the panel at the card's own x by a {@link Canvas}. The vertical position is the glyph's own
 * {@code ascent} in {@code gui.json}, which is why a state that can land on two card rows is
 * declared twice. A fifth menu in this style is a panel, its overlays and a slot map; nothing here
 * changes.
 *
 * <h2>Rows, and the positive advance that made them possible (2026-09-09)</h2>
 * A <em>list</em> menu draws the same furniture on any of the six chest rows, and a glyph's only
 * vertical control is its font's {@code ascent} - so the row is carried by the font
 * ({@link Glyphs#FONT_GUI_ROWS}) rather than by the code point, and a row is composed with
 * {@link Canvas#rowArt} and {@link Canvas#rowText}. Until this change a canvas laid its overlays
 * down <b>right to left</b>, because {@code nordtal:gui} carried negative advances and no positive
 * ones; that is fine for four cards that never overlap and exactly wrong for a row, whose plate
 * starts at the smallest x and has to be painted <em>under</em> the label on top of it. So the font
 * gained {@code U+FF801..U+FF928}, and a canvas draws in the order things were added to it.
 *
 * <h2>Two things that are easy to get wrong</h2>
 * <b>The panel has to be white.</b> Vanilla draws an inventory title in hardcoded dark grey
 * ({@code 0x404040}), which applies to any component that names no colour of its own - so white art
 * comes out grey. <b>And the panel has to name its font.</b> The four fonts allocate
 * independently, so a panel code point left in {@code minecraft:default} does not fail to draw, it
 * draws whatever that font holds at the same code point. The readable title deliberately names no
 * font, so it renders in {@code minecraft:default} where the letters are.
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
     * @throws IllegalArgumentException on a row count the pack has no panel for - which is a
     *                                  programming error rather than a configuration one, and
     *                                  failing here beats a menu opening with a missing-glyph box
     *                                  where its frame should be
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
     * <p><b>The panel carries no shadow</b>, for the reason {@link eu.nordtal.s2.common.hud.BoardFrame} states at length:
     * vanilla draws every glyph a second time one pixel down and right, and on a 176-pixel opaque
     * panel that second copy is a dark edge along the bottom and the right of the window that
     * nothing in the pack drew. {@link #of} appends the readable title to {@code Component.empty()}
     * rather than to this component, so the title is a sibling and keeps its own shadow.
     */
    public static Component panel(final int rows) {
        return on(Glyphs.GUI_PANELS[rows - 1]).panel();
    }

    /**
     * The same, on the panel that has <b>no container slot recesses</b>.
     *
     * <p>For a menu that paints across whole rows - a list, where a pill runs the width of the
     * window - because a recess under such a pill shows above it, below it and on both sides of it.
     * The player's own three rows and the hotbar keep their recesses in both variants.
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
     * <p>The eight advances are powers of two, so this is the number's binary representation and
     * there is exactly one way to write it. Zero is the empty string rather than an error: a caller
     * that computes a shift of nothing should get nothing, not a special case.
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
     * The glyphs that move the cursor {@code pixels} to the <b>right</b>, largest advance first.
     *
     * <p>The mirror of {@link #shift(int)}, and it did not exist until 2026-09-09 because nothing
     * in a menu title had ever moved right. A row does: its pill has to be drawn before the label
     * on top of it, so a row is composed left to right and the cursor has to be able to come back
     * out to the next row's start.
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
     * <h2>Draw order is insertion order</h2>
     * Later wins, exactly as it does on any canvas: a pill added before its label is painted under
     * that label. That is only possible because {@code nordtal:gui} and the six row fonts carry
     * <em>positive</em> advances as well as negative ones since 2026-09-09. Until then the cursor
     * could only ever walk left, so overlays were laid down right-to-left - which works for the
     * balloon, whose four cards never overlap, and is exactly wrong for a row, whose plate starts
     * at the smallest x and has to be drawn first.
     *
     * <h2>Fonts, and why a placement carries one</h2>
     * A glyph's only vertical control is its font's {@code ascent}, so "on chest row 2" is a font
     * and not a coordinate ({@link Glyphs#FONT_GUI_ROWS}). A placement that names no font inherits
     * the panel's {@code nordtal:gui}, and one that names no colour inherits the panel's white -
     * which is what a tinted white pictogram wants and what a line of readable text does not.
     *
     * <p>{@code MenuTitleTest} walks the composed result with the pack's own advances and asserts
     * every placement lands on the x it was given and the surface ends back on the title anchor.</p>
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
         * <p>The row picks the font and the glyph picks the picture, which is the whole point of
         * there being six row fonts; the advance comes from {@link MenuFont}, which reads it out of
         * the same export the pack was generated with.</p>
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
         * what is measured is what is drawn. A caller that needs it to fit somewhere should use
         * {@link MenuFont#fit(String, int)} rather than passing the whole of a POI name and hoping.
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
            // The walk home names no font and no colour, so it inherits the panel's - which is why
            // every row font carries the same eight advances nordtal:gui does.
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
