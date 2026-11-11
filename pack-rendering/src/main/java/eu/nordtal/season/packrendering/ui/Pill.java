package eu.nordtal.season.packrendering.ui;

import eu.nordtal.season.packrendering.Glyphs;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.jspecify.annotations.Nullable;

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
        return of(null, List.of(new Run(text, colour)));
    }

    /** Returns a pill holding {@code symbol} and {@code text} in ink, as a hologram names a kind of thing. */
    public static Component of(final Symbol symbol, final String text) {
        return of(symbol, List.of(new Run(text, Colours.INK)));
    }

    /** One stretch of a pill's text in its colour. */
    public record Run(String text, TextColor colour) {}

    /** Returns a pill holding {@code symbol}, if one is given, and {@code runs} after it. */
    public static Component of(final @Nullable Symbol symbol, final List<Run> runs) {
        final StringBuilder all = new StringBuilder();
        runs.forEach(run -> all.append(run.text()));
        final int content = content(symbol, all.toString());
        final String font = Pen.artFont(0);
        final List<String> glyphs = Glyphs.UI_PILL;
        final Pen pen = Pen.start().art(font, glyphs.get(0), PAD);
        body(pen, font, glyphs.subList(2, glyphs.size()), content);
        pen.moveTo(PAD + content).art(font, glyphs.get(1), PAD);
        pen.moveTo(PAD);
        if (symbol != null) {
            pen.symbol(TEXT, symbol, Colours.INK).move(Symbol.BEFORE_TEXT - Symbol.ADVANCE);
        }
        runs.stream().filter(run -> !run.text().isEmpty()).forEach(run -> pen.text(TEXT, run.text(), run.colour()));
        return pen.build(content + 2 * PAD);
    }

    /**
     * Draws the pill's middle, each piece over the last column of the one before and of the ends.
     * Its columns are all alike, and in the world a display places each glyph at fractional pixels, where two glyphs
     * that only touch leave a hairline of sky between them.
     */
    private static void body(final Pen pen, final String font, final List<String> middles, final int content) {
        final int end = PAD + content + 1;
        int x = PAD - 1;
        while (true) {
            final int length = Integer.highestOneBit(Math.min(end - x, 1 << (middles.size() - 1)));
            pen.moveTo(x).art(font, middles.get(Integer.numberOfTrailingZeros(length)), length);
            if (x + length >= end) {
                return;
            }
            x += length - 1;
        }
    }

    private static int content(final @Nullable Symbol symbol, final String text) {
        return (symbol == null ? 0 : Symbol.BEFORE_TEXT) + Letters.visibleWidth(text);
    }

    /** The width of the pill that holds {@code text}. */
    public static int width(final String text) {
        return width(null, text);
    }

    /** The width of the pill that holds {@code symbol}, if one is given, and {@code text}. */
    public static int width(final @Nullable Symbol symbol, final String text) {
        return content(symbol, text) + 2 * PAD;
    }
}
