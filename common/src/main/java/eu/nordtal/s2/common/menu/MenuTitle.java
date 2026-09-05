package eu.nordtal.s2.common.menu;

import eu.nordtal.s2.common.Glyphs;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;

import java.util.ArrayList;
import java.util.Comparator;
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
 * of the panel at the card's own x by a {@link Canvas}: after the panel the cursor sits at the
 * window's right edge, and every overlay is reached by walking left from wherever the cursor is,
 * so overlays are laid down right-to-left and the title walks back to its anchor at the end. The
 * vertical position is the glyph's own {@code ascent} in {@code gui.json}, which is why a state
 * that can land on two rows is declared twice. A fifth menu in this style is a panel, its overlays
 * and a slot map; nothing here changes.
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

    /** One glyph drawn on top of the panel at a window x; its {@code ascent} in the font fixes y. */
    private record Overlay(String glyph, int x, int width) {
    }

    /**
     * A panel with overlays, composed into one title.
     *
     * <p>Overlays are drawn right-to-left regardless of the order they were added in, because the
     * font carries no positive advance: after the panel the cursor is at the window's right edge,
     * and every overlay is reached by walking left. Two overlays at the same x - the two rows of
     * the balloon's cards - are drawn one after the other, the second walking back over the first's
     * advance. {@code MenuTitleTest} walks the result with the pack's own advances and asserts every
     * overlay lands on the x it was given and the title lands back on its anchor.</p>
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
            if (x < 0 || width < 1 || x + width > PANEL_ADVANCE - 1) {
                throw new IllegalArgumentException(
                        "an overlay " + width + " wide at x = " + x + " does not fit a 176px window");
            }
            overlays.add(new Overlay(glyph, x, width));
            return this;
        }

        /** The composed surface: panel and overlays, ending on the title anchor - no readable text. */
        public Component panel() {
            final StringBuilder composed = new StringBuilder();
            composed.append(shift(ANCHOR_X)).append(panelGlyph);
            int cursor = PANEL_ADVANCE;

            final List<Overlay> rightToLeft = new ArrayList<>(overlays);
            rightToLeft.sort(Comparator.comparingInt(Overlay::x).reversed());
            for (final Overlay overlay : rightToLeft) {
                composed.append(shift(cursor - overlay.x())).append(overlay.glyph());
                cursor = overlay.x() + overlay.width() + 1;
            }
            composed.append(shift(cursor - ANCHOR_X));

            return Component.text(composed.toString())
                    .font(Key.key(Glyphs.FONT_GUI))
                    .color(NamedTextColor.WHITE)
                    .shadowColor(ShadowColor.none());
        }

        /** The title to hand {@code Bukkit.createInventory}: the surface, then {@code title}. */
        public Component build(final Component title) {
            return Component.empty()
                    .append(panel())
                    .append(title);
        }
    }
}
