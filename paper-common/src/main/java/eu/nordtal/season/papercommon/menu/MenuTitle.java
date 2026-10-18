package eu.nordtal.season.papercommon.menu;

import eu.nordtal.season.messagerendering.Lines;
import eu.nordtal.season.packrendering.Glyphs;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import org.jspecify.annotations.Nullable;

/**
 * Composes the title of a chest menu, so that the window is drawn in Nordtal's frame.
 * The panel must be white and name its font.
 */
public final class MenuTitle {

    /** Vanilla's {@code titleLabelX} for a chest screen. */
    public static final int ANCHOR_X = 8;

    /** The cursor advance of a 176px panel glyph: its width plus the trailing pixel. */
    public static final int PANEL_ADVANCE = 177;

    /** The number of panels the pack draws. */
    public static final int MAX_ROWS = 6;

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

    private MenuTitle() {}

    /**
     * Returns the title for {@code Bukkit.createInventory}.
     * It holds the panel for {@code rows} (1 to {@value #MAX_ROWS}), then {@code title}.
     */
    public static Component of(final int rows, final Component title) {
        if (rows < 1 || rows > MAX_ROWS) {
            throw new IllegalArgumentException("a chest menu has 1 to " + MAX_ROWS + " rows, not " + rows);
        }
        return Component.empty().append(panel(rows)).append(Lines.joined(title));
    }

    public static Component panel(final int rows) {
        return on(Glyphs.GUI_PANELS.get(rows - 1)).panel();
    }

    public static Component panelPlain(final int rows) {
        return onPlain(rows).panel();
    }

    public static Canvas onPlain(final int rows) {
        if (rows < 1 || rows > MAX_ROWS) {
            throw new IllegalArgumentException("a chest menu has 1 to " + MAX_ROWS + " rows, not " + rows);
        }
        return on(Glyphs.GUI_PANELS_PLAIN.get(rows - 1));
    }

    /** Starts a title on a full-window panel glyph: a 176px-wide {@code nordtal:gui} glyph on ascent 13. */
    public static Canvas on(final String panelGlyph) {
        return new Canvas(panelGlyph);
    }

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

    public static String move(final int pixels) {
        return pixels < 0 ? shift(-pixels) : forward(pixels);
    }

    /**
     * Something drawn on the panel at window x.
     *
     * @param font the font id, or null to inherit {@code nordtal:gui}
     * @param colour the colour, or null to inherit the panel's white
     */
    private record Overlay(
            int x,
            int advance,
            String content,
            @Nullable String font,
            @Nullable TextColor colour) {}

    /** A panel with overlays, composed into one title. Overlays are drawn in insertion order. */
    public static final class Canvas {

        private final String panelGlyph;
        private final List<Overlay> overlays = new ArrayList<>();

        private Canvas(final String panelGlyph) {
            this.panelGlyph = panelGlyph;
        }

        /**
         * Draws {@code glyph} with its left edge at window {@code x}.
         *
         * @param width the drawn width of the glyph, whose advance is one more
         */
        public Canvas overlay(final String glyph, final int x, final int width) {
            return place(x, width + 1, glyph, null, null);
        }

        /**
         * Draws a row glyph (pill, frame, button plate or pictogram) on a chest row.
         * A null {@code colour} leaves it white.
         */
        public Canvas rowArt(final String glyph, final int row, final int x, final @Nullable TextColor colour) {
            return place(x, MenuFont.advance(glyph.codePointAt(0)), glyph, rowFont(row), colour);
        }

        /**
         * Draws text on a chest row in the pack's five-pixel capitals.
         * It folds {@code text} to capitals.
         * {@code colour} is never null.
         */
        public Canvas rowText(final String text, final int row, final int x, final TextColor colour) {
            final String folded = MenuFont.fold(text);
            if (folded.isEmpty()) {
                rowFont(row);
                return this;
            }
            return place(x, MenuFont.width(folded), folded, rowFont(row), colour);
        }

        /** Draws the text with its right edge at {@code xRight}. */
        public Canvas rowTextRight(final String text, final int row, final int xRight, final TextColor colour) {
            final String folded = MenuFont.fold(text);
            return rowText(folded, row, xRight - MenuFont.width(folded), colour);
        }

        private static String rowFont(final int row) {
            if (row < 0 || row >= MAX_ROWS) {
                throw new IllegalArgumentException("there is no chest row " + row);
            }
            return Glyphs.FONT_GUI_ROWS.get(row);
        }

        private Canvas place(
                final int x,
                final int advance,
                final String content,
                final @Nullable String font,
                final @Nullable TextColor colour) {
            if (x < 0 || advance < 1 || x + advance > PANEL_ADVANCE) {
                throw new IllegalArgumentException(
                        "a placement advancing " + advance + " at x = " + x + " does not fit a 176px window");
            }
            overlays.add(new Overlay(x, advance, content, font, colour));
            return this;
        }

        /** Returns the panel and the overlays, ending on the title anchor, without readable text. */
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
            // The title returns to the panel's font, which has the advances of nordtal:gui.
            return surface.append(Component.text(move(ANCHOR_X - cursor)));
        }

        public Component build(final Component title) {
            return Component.empty().append(panel()).append(Lines.joined(title));
        }
    }
}
