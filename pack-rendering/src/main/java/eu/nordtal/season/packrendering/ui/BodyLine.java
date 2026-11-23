package eu.nordtal.season.packrendering.ui;

import eu.nordtal.season.packrendering.Glyphs;
import java.util.function.Consumer;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import org.jspecify.annotations.Nullable;

/**
 * Composes one dialog body line from left to right.
 * A plate line's art starts on its text row or lower, as {@link BodyColumn} sets it. {@link FocusRing} frames the
 * finished line in its box.
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

    /** The width of a native button that carries a 98 x 18 plate inside its 1 px rim. */
    public static final int NATIVE_BUTTON = 100;

    /** The width of the station's native text field. */
    public static final int FIELD = 180;

    /** A native checkbox's box, and the gap from it to its label. */
    private static final int BOX = 17;

    private static final int BOX_GAP = 4;

    private final Pen pen = Pen.start();
    private final int down;
    private final int textShift;
    private boolean disabled;

    private BodyLine(final int down, final int textShift) {
        this.down = down;
        this.textShift = textShift;
    }

    /** A drawn part's click and the tooltip the mouse shows over it, each if it has one. */
    public record Press(
            @Nullable ClickEvent click, @Nullable Component tooltip) {

        /** No click and no tooltip, for static art or a disabled control without hover text. */
        public static final Press NONE = new Press(null, null);

        /** A disabled control's press: carries no click callback, only its optional {@code tooltip}. */
        public static Press disabled(final @Nullable Component tooltip) {
            return new Press(null, tooltip);
        }

        /** Refuses a click callback on a disabled look, so disabled controls never carry one. */
        public static void rejectDisabledClick(final Look look, final Press press) {
            if (look == Look.DISABLED && press.click() != null) {
                throw new IllegalArgumentException("a disabled control carries no click callback");
            }
        }
    }

    /** Starts an empty plate line. */
    public static BodyLine start() {
        return start(0);
    }

    /** Starts an empty plate line whose plates' tops stand {@code down} pixels below the text row. */
    public static BodyLine start(final int down) {
        return new BodyLine(down, down + LABEL);
    }

    /** Starts a row of text and symbols whose capitals stand {@code down} pixels below the text row. */
    public static BodyLine textRow(final int down) {
        return new BodyLine(down, down);
    }

    /** Returns where the next part starts, which is the line's width so far. */
    public int width() {
        return pen.x();
    }

    /** Returns {@code words} for a narrator only: they take no room and draw nothing. */
    public static Component silent(final String words) {
        return Component.text(words).font(Key.key(Glyphs.FONT_UI_SILENT));
    }

    /** Returns the label of a native button in the primary look: the 98 x 18 plate with {@code label} centred on it. */
    public static Component primaryButton(final String label) {
        return nativeButton(label, Glyphs.UI_PRIMARY_BUTTON, Look.PRIMARY);
    }

    /** Returns the label of a native button in the normal look: the 98 x 18 plate with {@code label} centred on it. */
    public static Component normalButton(final String label) {
        return nativeButton(label, Glyphs.UI_NORMAL_BUTTON, Look.NORMAL);
    }

    /**
     * Returns a native button's label: its rim in the rest colours over the client's hover and focus mark, and a plate.
     * It advances 0, so the client centres it on the button's middle and neither scrolls nor clips it.
     */
    private static Component nativeButton(final String label, final String plate, final Look look) {
        final String text = Letters.cut(label, PRIMARY_WIDTH - 2 * PAD);
        final int left = -(NATIVE_BUTTON / 2);
        final int inside = left + 1;
        return Pen.start()
                .moveTo(left)
                .art(Pen.artFont(0), Glyphs.UI_BUTTON_RIM, NATIVE_BUTTON)
                .moveTo(inside)
                .art(Pen.artFont(0), plate, PRIMARY_WIDTH)
                .moveTo(inside + (PRIMARY_WIDTH - Letters.visibleWidth(text) + 1) / 2)
                .text(0, text, look.ink())
                .build(0);
    }

    /**
     * Returns the label of a native button in the primary look that also rings the station's text field above it.
     * The field stands centred over the button, its top 63 rows above the label's text row; the ring takes its frame
     * colour, so the client's white focus border on the field never shows.
     */
    public static Component primaryButtonOverField(final String label) {
        return primaryButton(label)
                .append(Pen.start()
                        .moveTo(-(FIELD / 2))
                        .art(Glyphs.FONT_UI_RING, Glyphs.UI_FIELD_RING, FIELD)
                        .build(0));
    }

    /**
     * Returns a checkbox's label: {@code text} in ink after a ring over the box itself.
     * The ring takes the box's frame colour, so the client's white focus border and highlight never show; it advances
     * 0, so the client lays the checkbox out by the text alone.
     */
    public static Component checkboxLabel(final String text) {
        return Pen.start()
                .moveTo(-(BOX + BOX_GAP))
                .art(Glyphs.FONT_UI_RING, Glyphs.UI_BOX_RING, BOX)
                .moveTo(0)
                .text(0, text, Colours.INK)
                .build(Letters.width(text));
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

    /** Draws {@code text} in bold on the line's label row. */
    public BodyLine bold(final String text, final TextColor colour) {
        pen.bold(Pen.textFont(textShift), text, colour);
        return this;
    }

    /** Draws a dark item field ({@link ItemPicture#SMALL_FIELD} or {@link ItemPicture#LARGE_FIELD}). */
    public BodyLine darkField(final int size) {
        final String glyph = switch (size) {
            case ItemPicture.SMALL_FIELD -> Glyphs.UI_DARK_FIELD_22;
            case ItemPicture.LARGE_FIELD -> Glyphs.UI_DARK_FIELD_40;
            default -> throw new IllegalArgumentException("unsupported dark field size " + size);
        };
        pen.art(Pen.artFont(down), glyph, size);
        return this;
    }

    /** Draws the 17 by 72 sunken scroll track. */
    public BodyLine scrollTrack() {
        pen.art(Pen.artFont(down), Glyphs.UI_SCROLL_TRACK_72, ScrollState.COLUMN_WIDTH);
        return this;
    }

    /** Draws a flat scroll position strip slice of height 4, 8 or 16 px. */
    public BodyLine scrollStrip(final int height) {
        final String glyph = switch (height) {
            case 4 -> Glyphs.UI_SCROLL_STRIP_4;
            case 8 -> Glyphs.UI_SCROLL_STRIP_8;
            case 16 -> Glyphs.UI_SCROLL_STRIP_16;
            default -> throw new IllegalArgumentException("unsupported scroll strip height " + height);
        };
        pen.art(Pen.artFont(down), glyph, ScrollState.STRIP_WIDTH);
        return this;
    }

    /** Draws {@code picture} on the line's label row: its atlas sprite or its fallback symbol. */
    public BodyLine itemPicture(final ItemPicture picture) {
        if (picture.hasSprite()) {
            pen.append(picture.spriteComponent(), ItemPicture.SPRITE_ADVANCE);
        } else {
            pen.symbol(textShift, picture.fallback(), picture.fallbackColour());
        }
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
        final BodyLine inner = new BodyLine(down, textShift);
        part.accept(inner);
        if (inner.disabled) {
            Press.rejectDisabledClick(Look.DISABLED, press);
        }
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
        if (look == Look.DISABLED) {
            disabled = true;
        }
        final int x = pen.x();
        pen.plate(down, look, width);
        final int room = width - 2 * PAD - (symbol == null ? 0 : Symbol.BEFORE_TEXT);
        final String text = Letters.cut(label, room);
        int lx = x + (width - Letters.visibleWidth(text) - (symbol == null ? 0 : Symbol.BEFORE_TEXT) + 1) / 2;
        if (symbol != null) {
            pen.moveTo(lx).symbol(down + LABEL, symbol, look.ink());
            lx += Symbol.BEFORE_TEXT;
        }
        pen.moveTo(lx).text(down + LABEL, text, look.ink()).moveTo(x + width);
        return this;
    }

    /** Draws a 54 px high card plate {@code width} wide in normal or disabled look. */
    public BodyLine cardPlate(final int width, final Look look) {
        if (width < 2) {
            throw new IllegalArgumentException("a card plate is at least 2 columns wide, not " + width);
        }
        if (look == Look.DISABLED) {
            disabled = true;
        }
        final String font = Pen.artFont(down);
        final var glyphs = look == Look.DISABLED ? Glyphs.UI_CARD_DISABLED : Glyphs.UI_CARD_NORMAL;
        pen.art(font, glyphs.get(0), 1);
        pen.runs(font, glyphs.subList(2, glyphs.size()), width - 2);
        pen.art(font, glyphs.get(1), 1);
        return this;
    }

    /** Draws a variable-width 4 px progress bar with optional lighter preview and 1 px light bottom edge. */
    public BodyLine varBar(final int width, final double progress, final double preview, final boolean done) {
        if (width <= 0) {
            return this;
        }
        final int x = pen.x();
        final String font = Pen.artFont(down);
        pen.runs(font, Glyphs.UI_VAR_BAR, width, Colours.BAR_TRACK);
        pen.moveTo(x).runs(font, Glyphs.UI_VAR_BAR_EDGE, width, done ? Colours.BAR_EDGE_DONE : Colours.BAR_EDGE);
        final double clamped = Math.min(Math.max(progress, 0), 1);
        int filled = (int) Math.round(width * clamped);
        if (clamped > 0 && filled == 0) {
            filled = 1;
        }
        if (filled > 0) {
            pen.moveTo(x).runs(font, Glyphs.UI_VAR_BAR, filled, done ? Colours.BAR_DONE : Colours.ACCENT);
        }
        if (!done && preview > 0) {
            final double total = Math.min(1.0, clamped + preview);
            final int end = (int) Math.round(width * total);
            final int extra = Math.max(0, end - filled);
            if (extra > 0) {
                pen.moveTo(x + filled).runs(font, Glyphs.UI_VAR_BAR, extra, Colours.PREVIEW);
            }
        }
        pen.moveTo(x + width);
        return this;
    }

    /** Draws a milestone rail node at the current x, 13 by 13 px. */
    BodyLine railNode(final MilestoneRail.Node node) {
        final String glyph = switch (node.state()) {
            case DONE -> Glyphs.UI_RAIL_NODE_DONE;
            case CURRENT -> Glyphs.UI_RAIL_NODE_CURRENT;
            case FUTURE -> node.faded() ? Glyphs.UI_RAIL_NODE_FAR : Glyphs.UI_RAIL_NODE_FUTURE;
        };
        pen.art(Pen.artFont(down), glyph, MilestoneRail.NODE);
        return this;
    }

    /** Draws a milestone rail connector segment (solid or dotted) of {@code width} px. */
    BodyLine railSegment(final MilestoneRail.Segment segment) {
        final int width = segment.endX() - segment.startX();
        if (width <= 0) {
            return this;
        }
        final int x = pen.x();
        final String font = Pen.artFont(down);
        final TextColor barColour = railBarColour(segment.done(), segment.faded());
        if (segment.dotted()) {
            for (int offset = 1; offset + 2 <= width - 1; offset += 3) {
                pen.moveTo(x + offset).art(font, Glyphs.UI_RAIL_DOT, 2, barColour);
            }
            pen.moveTo(x + width);
            return this;
        }
        final TextColor edgeColour = railEdgeColour(segment.done(), segment.faded());
        pen.runs(font, Glyphs.UI_RAIL_BAR, width, barColour);
        pen.moveTo(x).runs(font, Glyphs.UI_RAIL_BAR_EDGE, width, edgeColour);
        pen.moveTo(x + width);
        return this;
    }

    private static TextColor railBarColour(final boolean done, final boolean faded) {
        if (done) {
            return faded ? Colours.RAIL_DONE_FADED : Colours.ACCENT;
        }
        return faded ? Colours.RAIL_FUTURE_FADED : Colours.FAINT;
    }

    private static TextColor railEdgeColour(final boolean done, final boolean faded) {
        if (done) {
            return faded ? Colours.RAIL_EDGE_DONE_FADED : Colours.RAIL_EDGE_DONE;
        }
        return faded ? Colours.RAIL_EDGE_FADED : Colours.RAIL_EDGE;
    }

    /** Draws a list row {@code width} wide: a symbol, {@code name} cut to its room, {@code value} at the right. */
    public BodyLine row(
            final int width,
            final @Nullable Symbol symbol,
            final @Nullable TextColor symbolColour,
            final String name,
            final @Nullable String value,
            final Look look) {
        if (look == Look.DISABLED) {
            disabled = true;
        }
        final int x = pen.x();
        pen.plate(down, look, width);
        final int right = x + width - 1 - PAD;
        int nameX = x + PAD;
        if (symbol != null) {
            pen.moveTo(nameX).symbol(down + LABEL, symbol, symbolColour == null ? look.ink() : symbolColour);
            nameX += Symbol.BEFORE_TEXT;
        }
        int room = right - nameX + 1;
        if (value != null) {
            final int valueX = right - Letters.visibleWidth(value) + 1;
            pen.moveTo(valueX).text(down + LABEL, value, look.soft());
            room = valueX - TEXT_GAP - nameX;
        }
        pen.moveTo(nameX)
                .text(down + LABEL, Letters.cut(name, room), look.ink())
                .moveTo(x + width);
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
        if (look == Look.DISABLED) {
            disabled = true;
        }
        final int x = pen.x();
        pen.plate(down, look, PLATE)
                .moveTo(x + LABEL)
                .symbol(down + LABEL, symbol, colour)
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
     * Next is drawn first, so it is the line's first click and Enter turns the page. {@code words} follow it for the
     * narrator.
     */
    public BodyLine pager(final int page, final int pages, final Press next, final Press previous, final String words) {
        final String label = page + "/" + pages;
        final int x = pen.x();
        final int nextX = x + pagerWidth(page, pages) - PLATE;
        pen.moveTo(nextX);
        button(next, line -> line.symbolPlate(Symbol.RIGHT, Look.NORMAL.ink(), Look.NORMAL));
        // After the first art: the client wraps a line at a space in words that lead it.
        words(words);
        pen.moveTo(x);
        button(previous, line -> line.symbolPlate(Symbol.LEFT, Look.NORMAL.ink(), Look.NORMAL));
        pen.move(SIDE_GAP).text(down + LABEL, label, Look.NORMAL.soft());
        pen.moveTo(nextX + PLATE);
        return this;
    }

    /**
     * The pager's width for {@code page} of {@code pages}, made even.
     * A pixel after the label evens it, since the client centres a line on whole pixels and every content width is
     * even.
     */
    public static int pagerWidth(final int page, final int pages) {
        final int width = 2 * PLATE + 2 * SIDE_GAP + Letters.visibleWidth(page + "/" + pages);
        return width + width % 2;
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
        if (look == Look.DISABLED) {
            disabled = true;
        }
        final int x = pen.x();
        pen.plate(down, look, width);
        pen.moveTo(x + PAD).symbol(down + LABEL, symbol, !done && look == Look.NORMAL ? Colours.ACCENT : look.ink());
        final int right = x + width - 1 - PAD;
        final int barX = right - Letters.visibleWidth(WIDEST_COUNT) - PAD - BAR + 1;
        final int nameX = x + PAD + Symbol.BEFORE_TEXT;
        pen.moveTo(nameX).text(down + LABEL, Letters.cut(name, barX - PAD - nameX), look.ink());
        final int countX = right - Letters.visibleWidth(count) + 1;
        if (done) {
            pen.moveTo(countX - Symbol.BEFORE_TEXT).symbol(down + LABEL, Symbol.TICK, look.soft());
        } else {
            bar(barX, progress);
        }
        pen.moveTo(countX).text(down + LABEL, count, look.soft()).moveTo(x + width);
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
        final String font = Pen.artFont(down);
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
        return pen.build();
    }
}
