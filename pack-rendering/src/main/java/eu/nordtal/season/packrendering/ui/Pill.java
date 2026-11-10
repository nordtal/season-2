package eu.nordtal.season.packrendering.ui;

import eu.nordtal.season.packrendering.Glyphs;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

/** Composes a label pill: 15 high in the chest window's look, its text in ink 4 rows down, content + 10 wide. */
public final class Pill {

    /** The pill's height. */
    public static final int HEIGHT = 15;

    /** From a pill's end to its content: 3 px of edge and 2 clear. */
    public static final int PAD = 5;

    private static final int TEXT = 4;

    private Pill() {}

    /** Returns a pill holding {@code text} in ink, its top on the text row. */
    public static Component of(final String text) {
        return of(text, Colours.INK);
    }

    /** Returns a pill holding {@code text} in {@code colour}, its top on the text row. */
    public static Component of(final String text, final TextColor colour) {
        final int content = Letters.visibleWidth(text);
        final String font = Pen.artFont(0);
        final List<String> glyphs = Glyphs.UI_PILL;
        final Pen pen = Pen.start().art(font, glyphs.get(0), PAD);
        pen.runs(font, glyphs.subList(2, glyphs.size()), content);
        pen.art(font, glyphs.get(1), PAD);
        return pen.moveTo(PAD).text(TEXT, text, colour).build(width(text));
    }

    /** The width of the pill that holds {@code text}. */
    public static int width(final String text) {
        return Letters.visibleWidth(text) + 2 * PAD;
    }
}
