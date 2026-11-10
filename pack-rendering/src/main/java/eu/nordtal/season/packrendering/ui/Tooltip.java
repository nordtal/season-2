package eu.nordtal.season.packrendering.ui;

import eu.nordtal.season.packrendering.Glyphs;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.jspecify.annotations.Nullable;

/**
 * Builds a show_text hover in Nordtal's tooltip look: a box drawn as glyphs over vanilla's, the title, its text.
 * The rows after the title are drawn 2 px lower, as vanilla spaces an item's tooltip after its name.
 */
public final class Tooltip {

    /** Where a show_text hover breaks its lines, so the box stays inside the 200 px vanilla allows at 320 x 240. */
    public static final int WRAP = 188;

    /** Where a native widget's tooltip breaks, inside vanilla's 170. */
    public static final int WRAP_NATIVE = 158;

    /** The style id of the tooltip sprites, for an item's {@code tooltip_style}. */
    public static final Key STYLE = Key.key("nordtal", "panel");

    /** The most rows a box is drawn for. */
    public static final int MAX_ROWS = 8;

    private static final int LOWER = 2;
    private static final int OUTSIDE = 6;

    private static final Map<Integer, List<String>> COVERS = Map.ofEntries(
            Map.entry(20, Glyphs.UI_COVER_20),
            Map.entry(30, Glyphs.UI_COVER_30),
            Map.entry(32, Glyphs.UI_COVER_32),
            Map.entry(40, Glyphs.UI_COVER_40),
            Map.entry(42, Glyphs.UI_COVER_42),
            Map.entry(50, Glyphs.UI_COVER_50),
            Map.entry(52, Glyphs.UI_COVER_52),
            Map.entry(60, Glyphs.UI_COVER_60),
            Map.entry(62, Glyphs.UI_COVER_62),
            Map.entry(70, Glyphs.UI_COVER_70),
            Map.entry(72, Glyphs.UI_COVER_72),
            Map.entry(80, Glyphs.UI_COVER_80),
            Map.entry(82, Glyphs.UI_COVER_82),
            Map.entry(90, Glyphs.UI_COVER_90),
            Map.entry(92, Glyphs.UI_COVER_92));

    private Tooltip() {}

    /** One row of a tooltip: its text and its colour. */
    public record Row(String text, TextColor colour) {}

    /** Returns the rows of a tooltip, each broken at {@code wrap}: title, text and hint. */
    public static List<Row> rows(
            final String title, final @Nullable String text, final @Nullable String hint, final int wrap) {
        final List<Row> rows = new ArrayList<>();
        Letters.wrap(title, wrap).forEach(row -> rows.add(new Row(row, Colours.TOOLTIP_TITLE)));
        if (text != null) {
            Letters.wrap(text, wrap).forEach(row -> rows.add(new Row(row, Colours.TOOLTIP_TEXT)));
        }
        if (hint != null) {
            Letters.wrap(hint, wrap).forEach(row -> rows.add(new Row(row, Colours.HINT)));
        }
        return List.copyOf(rows);
    }

    /** Returns the hover component for {@code title}, {@code text} and {@code hint}, broken at {@link #WRAP}. */
    public static Component hover(final String title, final @Nullable String text, final @Nullable String hint) {
        return hover(rows(title, text, hint, WRAP), Letters.wrap(title, WRAP).size());
    }

    /** Returns the hover component for {@code rows}, the first {@code titleRows} of them the title. */
    public static Component hover(final List<Row> rows, final int titleRows) {
        if (rows.isEmpty() || rows.size() > MAX_ROWS) {
            throw new IllegalArgumentException("a tooltip box holds 1 to " + MAX_ROWS + " rows, not " + rows.size());
        }
        final int width =
                rows.stream().mapToInt(row -> Letters.width(row.text())).max().orElse(0);
        final boolean gap = titleRows < rows.size();
        final Pen first = Pen.start();
        cover(first, width, boxHeight(rows.size(), gap));
        Component hover = Component.empty()
                .append(first.text(0, rows.get(0).text(), rows.get(0).colour()).build());
        for (int index = 1; index < rows.size(); index++) {
            final Row row = rows.get(index);
            final int shift = index < titleRows ? 0 : LOWER;
            hover = hover.append(Component.newline())
                    .append(Pen.start().text(shift, row.text(), row.colour()).build());
        }
        return hover;
    }

    /** The box's height for {@code rows} rows: 20 for one, 10 n + 12 with the gap after a title, 10 n + 10 without. */
    public static int boxHeight(final int rows, final boolean gap) {
        return rows == 1 ? 20 : 10 * rows + (gap ? 12 : 10);
    }

    /** Draws the box for text {@code width} wide from 6 px left of the pen, and returns to where it started. */
    static void cover(final Pen pen, final int width, final int height) {
        final List<String> glyphs = COVERS.get(height);
        if (glyphs == null) {
            throw new IllegalArgumentException("the pack has no tooltip box " + height + " px high");
        }
        final int start = pen.x();
        final String font = Glyphs.FONT_UI_TOOLTIP;
        pen.moveTo(start - OUTSIDE).art(font, glyphs.get(0), 1).art(font, glyphs.get(1), 1);
        pen.runs(font, glyphs.subList(2, glyphs.size()), width + 8);
        pen.art(font, glyphs.get(1), 1).art(font, glyphs.get(0), 1).moveTo(start);
    }
}
