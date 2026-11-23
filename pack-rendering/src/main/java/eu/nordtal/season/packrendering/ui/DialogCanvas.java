package eu.nordtal.season.packrendering.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextColor;
import org.jspecify.annotations.Nullable;

/**
 * Places dialog elements freely at content coordinates (x, y) on a {@link DialogPanel} inside one focus-framed box.
 * Supports 280 by 161 and 200 by 116 panels, two-column card grids, item pictures and a scroll column, while enforcing
 * that disabled controls and the scroll position strip carry no click callback.
 */
public final class DialogCanvas {

    private static final int TEXT_ROW = 4;
    private static final int ROW = 9;
    private static final int BOX = 8;
    private static final int FOOT = 7;

    private record Piece(int x, int y, boolean plate, Consumer<BodyLine> draw) {}

    private final int width;
    private final int height;
    private final int rows;
    private final int top;
    private final List<Piece> pieces = new ArrayList<>();

    private DialogCanvas(final int width, final int height, final int rows, final int top) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("canvas dimensions must be positive");
        }
        this.width = width;
        this.height = height;
        this.rows = rows;
        this.top = top;
    }

    /**
     * Starts a full-panel canvas for {@code panel}, its box ending on the panel's black border.
     * Content (0, 0) lies at (panel + 7, panel + 18).
     */
    public static DialogCanvas of(final DialogPanel panel) {
        return of(panel.contentWidth(), panel.contentHeight(), false);
    }

    /**
     * Starts a canvas {@code width} by {@code height} content pixels.
     * A {@code followed} canvas ends {@link BodyColumn#AFTER} under its art, otherwise on the panel's black border.
     */
    public static DialogCanvas of(final int width, final int height, final boolean followed) {
        final int below = followed ? BodyColumn.AFTER : FOOT;
        final int rows = Math.max(FocusRing.MIN_ROWS, Math.ceilDiv(TEXT_ROW + height + below - BOX, ROW));
        final int top = ROW * rows + BOX - below - height;
        return new DialogCanvas(width, height, rows, top);
    }

    /** Starts a canvas with explicit {@code rows} and {@code top} inside its box, for tight multi-band layouts. */
    public static DialogCanvas exact(final int width, final int height, final int rows, final int top) {
        return new DialogCanvas(width, height, rows, top);
    }

    /** The canvas's content width in pixels. */
    public int width() {
        return width;
    }

    /** The canvas's content height in pixels. */
    public int height() {
        return height;
    }

    /** The text rows of the canvas's focus-ring box. */
    public int rows() {
        return rows;
    }

    /** Where content y 0 stands under the box's top. */
    public int top() {
        return top;
    }

    /** The screen y of the panel's top when this canvas is the dialog's first body. */
    public int panelTop() {
        return BodyColumn.BODY_TOP + top - BodyColumn.PANEL_TO_CONTENT;
    }

    /** Places a button plate at ({@code x}, {@code y}). Refuses a click callback when {@code look} is disabled. */
    public DialogCanvas plate(
            final int x,
            final int y,
            final int plateWidth,
            final @Nullable Symbol symbol,
            final String label,
            final Look look,
            final BodyLine.Press press) {
        BodyLine.Press.rejectDisabledClick(look, press);
        return place(x, y, true, line -> line.button(press, part -> part.plate(plateWidth, symbol, label, look)));
    }

    /** Places a 17 by 17 symbol plate at ({@code x}, {@code y}). Refuses a click callback when disabled. */
    public DialogCanvas symbolPlate(
            final int x,
            final int y,
            final Symbol symbol,
            final TextColor colour,
            final Look look,
            final BodyLine.Press press,
            final @Nullable String words) {
        BodyLine.Press.rejectDisabledClick(look, press);
        return place(
                x,
                y,
                true,
                line -> line.button(press, part -> {
                    part.symbolPlate(symbol, colour, look);
                    if (words != null) {
                        part.words(words);
                    }
                }));
    }

    /** Places a list row at ({@code x}, {@code y}). Refuses a click callback when {@code look} is disabled. */
    public DialogCanvas row(
            final int x,
            final int y,
            final int rowWidth,
            final @Nullable Symbol symbol,
            final @Nullable TextColor symbolColour,
            final String name,
            final @Nullable String value,
            final Look look,
            final BodyLine.Press press) {
        BodyLine.Press.rejectDisabledClick(look, press);
        return place(
                x,
                y,
                true,
                line -> line.button(press, part -> part.row(rowWidth, symbol, symbolColour, name, value, look)));
    }

    /** Places a favourite star toggle at ({@code x}, {@code y}). */
    public DialogCanvas star(
            final int x, final int y, final boolean on, final @Nullable String words, final BodyLine.Press press) {
        return place(
                x,
                y,
                true,
                line -> line.button(press, part -> {
                    part.star(on);
                    if (words != null) {
                        part.words(words);
                    }
                }));
    }

    /** Places plain text at ({@code x}, {@code y}) in {@code colour}. */
    public DialogCanvas text(final int x, final int y, final String text, final TextColor colour) {
        return place(x, y, false, line -> line.text(text, colour));
    }

    /** Places bold text at ({@code x}, {@code y}) in {@code colour}. */
    public DialogCanvas bold(final int x, final int y, final String text, final TextColor colour) {
        return place(x, y, false, line -> line.bold(text, colour));
    }

    /** Places a 7 by 7 symbol at ({@code x}, {@code y}) in {@code colour}. */
    public DialogCanvas symbol(final int x, final int y, final Symbol symbol, final TextColor colour) {
        return place(x, y, false, line -> line.symbol(symbol, colour));
    }

    /** Places a dark item field ({@link ItemPicture#SMALL_FIELD} or {@link ItemPicture#LARGE_FIELD}) at (x, y). */
    public DialogCanvas darkField(final int x, final int y, final int size) {
        return place(x, y, true, line -> line.darkField(size));
    }

    /** Places a 54 px high card plate at ({@code x}, {@code y}). Refuses a click callback when disabled. */
    public DialogCanvas cardPlate(
            final int x, final int y, final int cardWidth, final Look look, final BodyLine.Press press) {
        BodyLine.Press.rejectDisabledClick(look, press);
        return place(x, y, true, line -> line.button(press, part -> part.cardPlate(cardWidth, look)));
    }

    /** Places a variable-width 4 px progress bar with 1 px light bottom edge at ({@code x}, {@code y}). */
    public DialogCanvas varBar(
            final int x,
            final int y,
            final int barWidth,
            final double progress,
            final double preview,
            final boolean done) {
        return place(x, y, false, line -> line.varBar(barWidth, progress, preview, done));
    }

    /** Places the milestone rail at ({@code x}, {@code y}) across {@code railWidth} px for {@code total} milestones. */
    public DialogCanvas rail(final int x, final int y, final int railWidth, final int total, final int current) {
        final List<MilestoneRail.Node> nodes = MilestoneRail.nodes(total, current, railWidth);
        for (final MilestoneRail.Segment segment : MilestoneRail.segments(nodes, total, current)) {
            place(x + segment.startX(), y, true, line -> line.railSegment(segment));
        }
        for (final MilestoneRail.Node node : nodes) {
            final int nx = x + node.x();
            place(nx, y, true, line -> line.railNode(node));
            if (node.state() == MilestoneRail.State.DONE) {
                symbol(nx + 3, y + 3, Symbol.TICK, TextColor.color(0xDFDFDF));
            } else {
                final String label = Integer.toString(node.index() + 1);
                final TextColor colour = node.state() == MilestoneRail.State.CURRENT
                        ? Colours.ACCENT
                        : (node.faded() ? TextColor.color(0x9A9A9A) : TextColor.color(0x6E6E6E));
                final int lx = nx + (MilestoneRail.NODE - Letters.visibleWidth(label) + 1) / 2;
                text(lx, y + 3, label, colour);
            }
        }
        return this;
    }

    /** Places an item picture at ({@code x}, {@code y}), with optional hover or click. */
    public DialogCanvas itemPicture(final int x, final int y, final ItemPicture picture, final BodyLine.Press press) {
        Objects.requireNonNull(picture, "picture");
        return place(x, y, false, line -> line.button(press, part -> part.itemPicture(picture)));
    }

    /**
     * Places the vertical scroll column at ({@code x}, {@code y}) with arrows, track and flat position strip.
     * Disabled arrows and the position strip carry no click callback.
     */
    public DialogCanvas scrollColumn(
            final int x,
            final int y,
            final ScrollState state,
            final @Nullable ClickEvent upClick,
            final @Nullable ClickEvent downClick,
            final @Nullable ClickEvent pageUpClick,
            final @Nullable ClickEvent pageDownClick,
            final @Nullable String upWords,
            final @Nullable String downWords) {
        final Look upLook = state.canScrollUp() ? Look.NORMAL : Look.DISABLED;
        final BodyLine.Press upPress = state.canScrollUp() ? new BodyLine.Press(upClick, null) : BodyLine.Press.NONE;
        symbolPlate(x, y, Symbol.UP, upLook.ink(), upLook, upPress, upWords);

        final int trackY = y + BodyLine.PLATE + BodyLine.SIDE_GAP;
        place(x, trackY, true, BodyLine::scrollTrack);

        final int innerTop = trackY + 2;
        final ScrollState.Strip strip = state.strip(ScrollState.TRACK_INNER);
        if (strip.top() > 0 && pageUpClick != null) {
            placeTrackClicks(x + 2, innerTop, strip.top(), pageUpClick);
        }
        // The position strip is flat and carries no callback, so it never promises dragging.
        placeStrip(x + 2, innerTop + strip.top(), strip.height());
        final int below = ScrollState.TRACK_INNER - strip.bottom();
        if (below > 0 && pageDownClick != null) {
            placeTrackClicks(x + 2, innerTop + strip.bottom(), below, pageDownClick);
        }

        final int downY = trackY + ScrollState.TRACK_HEIGHT + BodyLine.SIDE_GAP;
        final Look downLook = state.canScrollDown() ? Look.NORMAL : Look.DISABLED;
        final BodyLine.Press downPress =
                state.canScrollDown() ? new BodyLine.Press(downClick, null) : BodyLine.Press.NONE;
        symbolPlate(x, downY, Symbol.DOWN, downLook.ink(), downLook, downPress, downWords);
        return this;
    }

    private void placeStrip(final int x, final int y, final int stripHeight) {
        int rest = stripHeight;
        int currentY = y;
        for (final int size : new int[] {16, 8, 4}) {
            while (rest >= size) {
                final int drawSize = size;
                place(x, currentY, true, line -> line.scrollStrip(drawSize));
                currentY += size;
                rest -= size;
            }
        }
    }

    private void placeTrackClicks(final int x, final int y, final int span, final ClickEvent click) {
        final BodyLine.Press press = new BodyLine.Press(click, null);
        int rest = span;
        int currentY = y;
        while (rest >= ScrollState.STRIP_STEP) {
            place(x, currentY, false, line -> line.button(press, part -> part.gap(ScrollState.STRIP_WIDTH)));
            currentY += ScrollState.STRIP_STEP;
            rest -= ScrollState.STRIP_STEP;
        }
    }

    /** Places an arbitrary {@link BodyLine} piece at content ({@code x}, {@code y}). */
    public DialogCanvas place(final int x, final int y, final boolean plate, final Consumer<BodyLine> draw) {
        pieces.add(new Piece(x, y, plate, Objects.requireNonNull(draw, "draw")));
        return this;
    }

    /** Builds the canvas framed on {@code ground}. */
    public Component build(final FocusRing.Ground ground) {
        return build(ground, Component.empty());
    }

    /** Builds the canvas framed on {@code ground}, redrawing {@code title}'s header where the cover overlaps it. */
    public Component build(final FocusRing.Ground ground, final DialogTitle title) {
        return build(ground, title.ink(BodyColumn.PANEL_TO_CONTENT - top, TEXT_ROW));
    }

    private Component build(final FocusRing.Ground ground, final Component over) {
        final Pen pen = Pen.start();
        final int shift = top - TEXT_ROW;
        for (final Piece piece : pieces) {
            final BodyLine line =
                    piece.plate() ? BodyLine.start(shift + piece.y()) : BodyLine.textRow(shift + piece.y());
            piece.draw().accept(line);
            pen.moveTo(piece.x()).append(line.build(), line.width());
        }
        return FocusRing.frame(pen.build(width), width, rows, ground, over);
    }
}
