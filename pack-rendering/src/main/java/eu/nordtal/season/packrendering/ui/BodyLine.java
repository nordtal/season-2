package eu.nordtal.season.packrendering.ui;

import eu.nordtal.season.packrendering.Glyphs;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import org.jspecify.annotations.Nullable;

/**
 * Composes one dialog body line from left to right: plates, rows, the star, the pager, cards and texts.
 * A plate line's art starts on its text row, its labels at plate + 5, and a second, empty row makes its box 26 high.
 * A text line has one row, its capitals 1 px under the text row. A line's net width is its art's width.
 */
public final class BodyLine {

    /** Every plate's height. */
    public static final int PLATE = 17;

    /** Between two plates side by side. */
    public static final int SIDE_GAP = 3;

    /** From a plate's ends to its label, and between a label and its value. */
    public static final int PAD = 4;

    /** The progress bar's width. */
    public static final int BAR = 48;

    /** The widest count a card holds, so every card's bar starts in one column. */
    public static final String WIDEST_COUNT = "20.000/20.000";

    private static final int LABEL = 5;
    private static final int TEXT_GAP = 8;
    private static final int PRIMARY_WIDTH = 98;

    /** A text line's capitals under its text row: box + 5, where a plain_message draws at box + 4. */
    private static final int TEXT_LINE = 1;

    private final Pen pen = Pen.start();
    private final int textShift;

    private BodyLine(final int textShift) {
        this.textShift = textShift;
    }

    /** A drawn part's click and the tooltip the mouse shows over it, each if it has one. */
    public record Press(@Nullable ClickEvent click, @Nullable Component tooltip) {}

    /** Starts an empty plate line. */
    public static BodyLine start() {
        return new BodyLine(LABEL);
    }

    /** Starts an empty text line: one row of text and symbols, no plate. */
    public static BodyLine textLine() {
        return new BodyLine(TEXT_LINE);
    }

    /** Returns {@code words} for a narrator only: they take no room and draw nothing. */
    public static Component silent(final String words) {
        return Component.text(words).font(Key.key(Glyphs.FONT_UI_SILENT));
    }

    /** Returns the label of a native button in the primary look: the 98 x 18 plate with {@code label} centred on it. */
    public static Component primaryButton(final String label) {
        final String text = Letters.cut(label, PRIMARY_WIDTH - 2 * PAD);
        return Pen.start()
                .art(Pen.artFont(0), Glyphs.UI_PRIMARY_BUTTON, PRIMARY_WIDTH)
                .moveTo((PRIMARY_WIDTH - Letters.visibleWidth(text) + 1) / 2)
                .text(0, text, Look.PRIMARY.ink())
                .build(PRIMARY_WIDTH);
    }

    /** Moves to {@code x} of the line, forward or back, without drawing. */
    public BodyLine at(final int x) {
        pen.moveTo(x);
        return this;
    }

    /** Draws {@code text} on the line's label row. */
    public BodyLine text(final String text, final TextColor colour) {
        pen.text(textShift, text, colour);
        return this;
    }

    /** Draws {@code symbol} on the line's label row, and moves {@link Symbol#BEFORE_TEXT} on. */
    public BodyLine symbol(final Symbol symbol, final TextColor colour) {
        pen.symbol(textShift, symbol, colour).move(Symbol.BEFORE_TEXT - Symbol.ADVANCE);
        return this;
    }

    /** Adds {@code words} for the narrator, which take no room. */
    public BodyLine words(final String words) {
        pen.append(silent(words), 0);
        return this;
    }

    /** Draws {@code part} as one button: a click on it runs {@code press}, and the mouse shows its tooltip. */
    public BodyLine button(final Press press, final Consumer<BodyLine> part) {
        final BodyLine inner = new BodyLine(textShift);
        part.accept(inner);
        final int width = inner.pen.x();
        Component drawn = inner.pen.build();
        final ClickEvent click = press.click();
        if (click != null) {
            drawn = drawn.clickEvent(click);
        }
        final Component tooltip = press.tooltip();
        if (tooltip != null) {
            drawn = drawn.hoverEvent(HoverEvent.showText(tooltip));
        }
        pen.append(drawn, width);
        return this;
    }

    /** Leaves {@code pixels} clear. */
    public BodyLine gap(final int pixels) {
        pen.move(pixels);
        return this;
    }

    /** Draws a button plate {@code width} wide with {@code label} centred, after {@code symbol} if one is given. */
    public BodyLine plate(final int width, final @Nullable Symbol symbol, final String label, final Look look) {
        final int x = pen.x();
        pen.plate(0, look, width);
        final int room = width - 2 * PAD - (symbol == null ? 0 : Symbol.BEFORE_TEXT);
        final String text = Letters.cut(label, room);
        int lx = x + (width - Letters.visibleWidth(text) - (symbol == null ? 0 : Symbol.BEFORE_TEXT) + 1) / 2;
        if (symbol != null) {
            pen.moveTo(lx).symbol(LABEL, symbol, look.ink());
            lx += Symbol.BEFORE_TEXT;
        }
        pen.moveTo(lx).text(LABEL, text, look.ink()).moveTo(x + width);
        return this;
    }

    /** Draws a list row {@code width} wide: a symbol, {@code name} cut to its room, {@code value} at the right. */
    public BodyLine row(
            final int width,
            final @Nullable Symbol symbol,
            final @Nullable TextColor symbolColour,
            final String name,
            final @Nullable String value,
            final Look look) {
        final int x = pen.x();
        pen.plate(0, look, width);
        final int right = x + width - 1 - PAD;
        int nameX = x + PAD;
        if (symbol != null) {
            pen.moveTo(nameX).symbol(LABEL, symbol, symbolColour == null ? look.ink() : symbolColour);
            nameX += Symbol.BEFORE_TEXT;
        }
        int room = right - nameX + 1;
        if (value != null) {
            final int valueX = right - Letters.visibleWidth(value) + 1;
            pen.moveTo(valueX).text(LABEL, value, look.soft());
            room = valueX - TEXT_GAP - nameX;
        }
        pen.moveTo(nameX).text(LABEL, Letters.cut(name, room), look.ink()).moveTo(x + width);
        return this;
    }

    /** The room a row {@code width} wide leaves its name, after {@code symbol} and before {@code value}. */
    public static int rowRoom(final int width, final boolean symbol, final @Nullable String value) {
        final int nameX = PAD + (symbol ? Symbol.BEFORE_TEXT : 0);
        final int right = width - 1 - PAD;
        return value == null ? right - nameX + 1 : right - Letters.visibleWidth(value) + 1 - TEXT_GAP - nameX;
    }

    /** Draws a 17 x 17 plate with {@code symbol} centred on it in {@code colour}. */
    public BodyLine symbolPlate(final Symbol symbol, final TextColor colour, final Look look) {
        final int x = pen.x();
        pen.plate(0, look, PLATE)
                .moveTo(x + LABEL)
                .symbol(LABEL, symbol, colour)
                .moveTo(x + PLATE);
        return this;
    }

    /** Draws the favourite's star toggle: a filled star in accent when {@code on}, a hollow one in soft when not. */
    public BodyLine star(final boolean on) {
        return on
                ? symbolPlate(Symbol.STAR, Colours.ACCENT, Look.NORMAL)
                : symbolPlate(Symbol.STAR_HOLLOW, Colours.SOFT, Look.NORMAL);
    }

    /**
     * Draws the pager: previous, {@code page}/{@code pages} in soft, next, 3 px apart.
     * Next is drawn first, so it is the line's first click and Enter turns the page. {@code words} lead it for the
     * narrator.
     */
    public BodyLine pager(final int page, final int pages, final Press next, final Press previous, final String words) {
        final String label = page + "/" + pages;
        final int x = pen.x();
        final int nextX = x + pagerWidth(page, pages) - PLATE;
        words(words);
        pen.moveTo(nextX);
        button(next, line -> line.symbolPlate(Symbol.RIGHT, Look.NORMAL.ink(), Look.NORMAL));
        pen.moveTo(x);
        button(previous, line -> line.symbolPlate(Symbol.LEFT, Look.NORMAL.ink(), Look.NORMAL));
        pen.move(SIDE_GAP).text(LABEL, label, Look.NORMAL.soft());
        pen.moveTo(nextX + PLATE);
        return this;
    }

    /** The width of the pager for {@code page} of {@code pages}. */
    public static int pagerWidth(final int page, final int pages) {
        return 2 * PLATE + 2 * SIDE_GAP + Letters.visibleWidth(page + "/" + pages);
    }

    /**
     * Draws a card {@code width} wide: {@code symbol}, {@code name}, a 48 px bar at {@code progress} and {@code count}.
     * A done card shows a tick before {@code count}, which then names the state, and no bar.
     */
    public BodyLine card(
            final int width,
            final Symbol symbol,
            final String name,
            final String count,
            final double progress,
            final boolean done,
            final Look look) {
        final int x = pen.x();
        pen.plate(0, look, width);
        pen.moveTo(x + PAD).symbol(LABEL, symbol, !done && look == Look.NORMAL ? Colours.ACCENT : look.ink());
        final int right = x + width - 1 - PAD;
        final int barX = right - Letters.visibleWidth(WIDEST_COUNT) - PAD - BAR + 1;
        final int nameX = x + PAD + Symbol.BEFORE_TEXT;
        pen.moveTo(nameX).text(LABEL, Letters.cut(name, barX - PAD - nameX), look.ink());
        final int countX = right - Letters.visibleWidth(count) + 1;
        if (done) {
            pen.moveTo(countX - Symbol.BEFORE_TEXT).symbol(LABEL, Symbol.TICK, look.soft());
        } else {
            bar(barX, progress);
        }
        pen.moveTo(countX).text(LABEL, count, look.soft()).moveTo(x + width);
        return this;
    }

    /** The narrowest card that holds {@code name}: symbol, name, the bar 4 px after it and the widest count. */
    public static int cardWidth(final String name) {
        return PAD
                + Symbol.BEFORE_TEXT
                + Letters.visibleWidth(name)
                + PAD
                + BAR
                + PAD
                + Letters.visibleWidth(WIDEST_COUNT)
                + PAD;
    }

    private void bar(final int x, final double progress) {
        final String font = Pen.artFont(0);
        pen.moveTo(x).art(font, Glyphs.UI_BAR_TRACK.get(0), BAR).moveTo(x);
        final double clamped = Math.min(Math.max(progress, 0), 1);
        int filled = (int) Math.round(BAR * clamped);
        if (clamped > 0 && filled == 0) {
            filled = 1;
        }
        pen.runs(font, Glyphs.UI_BAR_FILL, filled);
    }

    /** Returns the line, as wide as its art. */
    public Component build() {
        final Component line = pen.build();
        if (textShift == TEXT_LINE) {
            return line;
        }
        // The second row makes the plain_message's box 26 high, so the plate stands at box + 4.
        return Component.empty().children(List.of(line, Component.newline(), silent(" ")));
    }
}
