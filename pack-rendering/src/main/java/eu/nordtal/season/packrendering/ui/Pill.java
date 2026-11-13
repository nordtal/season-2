package eu.nordtal.season.packrendering.ui;

import eu.nordtal.season.packrendering.Glyphs;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.jspecify.annotations.Nullable;

/** Composes a label pill: 15 high, its text in ink 4 rows down, content + 10 wide; the HUD's is flat. */
public final class Pill {

    /** The pill's height. */
    public static final int HEIGHT = 15;

    /** From a pill's end to its content: 3 px of edge and 2 clear, or the HUD's 1 px of rim and 4 clear. */
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

    /**
     * What a HUD pill holds in ink: a text, with a symbol before it, a symbol after it, or the ride bar before it.
     * {@code done} is the bar's share from 0 to 1, or {@code null} for no bar.
     */
    public record Content(
            @Nullable Symbol before,
            String text,
            @Nullable Symbol after,
            @Nullable Double done) {

        public Content {
            // A line break draws as an unknown character.
            text = text.replace('\n', ' ');
        }

        /** A text alone. */
        public static Content of(final String text) {
            return new Content(null, text, null, null);
        }

        /** {@code symbol} before {@code text}. */
        public static Content of(final Symbol symbol, final String text) {
            return new Content(symbol, text, null, null);
        }

        /** The ride bar at {@code done}, from 0 to 1, before {@code text}. */
        public static Content bar(final double done, final String text) {
            return new Content(null, text, null, done);
        }

        /** This content with {@code symbol} after its text. */
        public Content then(final Symbol symbol) {
            return new Content(before, text, symbol, done);
        }

        /** The columns between the pill's padding. */
        int width() {
            return (before == null ? 0 : Symbol.BEFORE_TEXT)
                    + (done == null ? 0 : RIDE_BAR + BAR_GAP)
                    + Letters.visibleWidth(text)
                    + (after == null ? 0 : AFTER_TEXT + Symbol.ADVANCE - 1);
        }
    }

    /** Pixels between two pills on one line. */
    public static final int GAP = 3;

    /** The ride bar's width, its outline included. */
    public static final int RIDE_BAR = 100;

    /** The fill's room inside the ride bar's outline. */
    static final int RIDE_FILL = RIDE_BAR - 2;

    /** Clear columns between the ride bar and its text. */
    private static final int BAR_GAP = 4;

    /** Clear columns between a text and the symbol after it. */
    private static final int AFTER_TEXT = 5;

    /** Returns {@code contents} as pills {@link #GAP} apart, on one line whose advance is their width. */
    public static Component line(final List<Content> contents) {
        final Pen pen = Pen.start();
        for (final Content content : contents) {
            if (pen.x() > 0) {
                pen.move(GAP);
            }
            final int x = pen.x();
            pen.append(of(content), width(content)).moveTo(x + width(content));
        }
        return pen.build();
    }

    /** The width of the pill that holds {@code content}. */
    public static int width(final Content content) {
        return content.width() + 2 * PAD;
    }

    /** Returns the HUD's pill that holds {@code content}, its top on the text row, flat with a 1 px rim. */
    public static Component of(final Content content) {
        final int inner = content.width();
        final Pen pen = frame(Glyphs.UI_HUD_PILL, inner);
        final @Nullable Double done = content.done();
        if (done != null) {
            final String font = Pen.artFont(0);
            pen.art(font, Glyphs.UI_RIDE_TRACK.get(0), RIDE_BAR).moveTo(PAD + 1);
            pen.runs(font, Glyphs.UI_RIDE_FILL, filled(done));
            pen.moveTo(PAD + RIDE_BAR + BAR_GAP);
        }
        final @Nullable Symbol before = content.before();
        if (before != null) {
            pen.symbol(TEXT, before, Colours.INK).move(Symbol.BEFORE_TEXT - Symbol.ADVANCE);
        }
        final int text = pen.x();
        if (!content.text().isEmpty()) {
            pen.text(TEXT, content.text(), Colours.INK);
        }
        final @Nullable Symbol after = content.after();
        if (after != null) {
            pen.moveTo(text + Letters.visibleWidth(content.text()) + AFTER_TEXT).symbol(TEXT, after, Colours.INK);
        }
        return pen.build(inner + 2 * PAD);
    }

    /** How many of the fill's columns {@code done} fills: none at 0, all at 1, and one for any start. */
    static int filled(final double done) {
        if (Double.isNaN(done) || done <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.round(Math.min(1, done) * RIDE_FILL));
    }

    /** Returns a pill holding {@code symbol}, if one is given, and {@code runs} after it. */
    public static Component of(final @Nullable Symbol symbol, final List<Run> runs) {
        final StringBuilder all = new StringBuilder();
        runs.forEach(run -> all.append(run.text()));
        final int content = content(symbol, all.toString());
        final Pen pen = frame(Glyphs.UI_PILL, content);
        if (symbol != null) {
            pen.symbol(TEXT, symbol, Colours.INK).move(Symbol.BEFORE_TEXT - Symbol.ADVANCE);
        }
        runs.stream().filter(run -> !run.text().isEmpty()).forEach(run -> pen.text(TEXT, run.text(), run.colour()));
        return pen.build(content + 2 * PAD);
    }

    /** Draws the pill of {@code glyphs} around {@code content} columns, and returns the pen at the content's start. */
    private static Pen frame(final List<String> glyphs, final int content) {
        final String font = Pen.artFont(0);
        final Pen pen = Pen.start().art(font, glyphs.get(0), PAD);
        body(pen, font, glyphs.subList(2, glyphs.size()), content);
        pen.moveTo(PAD + content).art(font, glyphs.get(1), PAD);
        return pen.moveTo(PAD);
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
