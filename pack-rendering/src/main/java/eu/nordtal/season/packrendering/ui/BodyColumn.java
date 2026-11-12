package eu.nordtal.season.packrendering.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import net.kyori.adventure.text.Component;

/**
 * Stacks a dialog's rows in one body line, {@link BodyLine#SIDE_GAP} apart, where separate lines stand 10 apart.
 * Rows are drawn through lowered fonts, and the client finds a click under the glyph where it is drawn, so each
 * plate keeps its click and tooltip. The column is one keyboard stop, and Enter fires its first click.
 */
public final class BodyColumn {

    /** From one plate row to the next. */
    public static final int STEP = BodyLine.PLATE + BodyLine.SIDE_GAP;

    /** Between the art of two body lines: the engine's 10, and on either side the focus ring's row and a clear row. */
    public static final int LINE_GAP = 14;

    /** Where the art of the line after a column starts: the ring's row and a clear row under its box's top. */
    public static final int AFTER = 2;

    /** A text row's height: the capitals and the glyph line's last row. */
    public static final int TEXT = 8;

    /** From a text row's capitals to the content under it, as from the header's capitals. */
    public static final int UNDER_TEXT = 11;

    /** The screen row of the first body line's box. */
    public static final int BODY_TOP = 63;

    /** From the panel's top to its first row of content: the margin, the header's capitals and the header gap. */
    public static final int PANEL_TO_CONTENT = 18;

    private static final int TEXT_ROW = 4;
    private static final int ROW = 9;
    private static final int BOX = 8;
    private static final int BELOW_LAST = 4;

    /** The text rows the client gives a line of plates by itself, measured in 26.3. */
    private static final int PLATE_ROWS = 2;

    private record Piece(int y, boolean plate, Consumer<BodyLine> draw) {}

    private final int width;
    private final boolean followed;
    private final List<Piece> pieces = new ArrayList<>();
    private int height;

    private BodyColumn(final int width, final boolean followed) {
        this.width = width;
        this.followed = followed;
    }

    /**
     * Starts a column {@code width} wide.
     * A {@code followed} column ends {@link #LINE_GAP} above the next line's art, which {@link #next()} starts.
     */
    public static BodyColumn of(final int width, final boolean followed) {
        return new BodyColumn(width, followed);
    }

    /** Starts the plate line after a followed column, its art {@link #AFTER} under its box's top. */
    public static BodyLine next() {
        return BodyLine.start(AFTER - TEXT_ROW);
    }

    /** Draws a row of plates at the column's foot; {@code row} draws on a plate line. */
    public BodyColumn plates(final Consumer<BodyLine> row) {
        pieces.add(new Piece(height, true, row));
        height += BodyLine.PLATE;
        return this;
    }

    /**
     * Draws {@code count} plate rows {@link BodyLine#SIDE_GAP} apart, {@code row} drawing each index's row.
     * It leaves room for {@code room} rows in all, so a short page keeps what follows in its place.
     */
    public BodyColumn rows(final int count, final int room, final IntFunction<Consumer<BodyLine>> row) {
        for (int index = 0; index < Math.max(count, room); index++) {
            if (index > 0) {
                gap(BodyLine.SIDE_GAP);
            }
            if (index < count) {
                plates(row.apply(index));
            } else {
                gap(BodyLine.PLATE);
            }
        }
        return this;
    }

    /** Draws a row of text at the column's foot; {@code row} draws on a text row. */
    public BodyColumn text(final Consumer<BodyLine> row) {
        pieces.add(new Piece(height, false, row));
        height += TEXT;
        return this;
    }

    /** Leaves {@code pixels} clear, between rows or for rows a page does not fill. */
    public BodyColumn gap(final int pixels) {
        height += pixels;
        return this;
    }

    /** The column's art height. */
    public int height() {
        return height;
    }

    /** The text rows of the column's box: 9 n + 8 high, with room for the ring under the art. */
    public int rows() {
        final int below = followed ? AFTER : BELOW_LAST;
        return Math.max(1, Math.ceilDiv(TEXT_ROW + height + below - BOX, ROW));
    }

    /** Where the art starts under the box's top: at the text row, or as low as a following line needs. */
    public int top() {
        return followed ? ROW * rows() + BOX - AFTER - height : TEXT_ROW;
    }

    /** The screen row of a dialog's panel top when this column is its first body line. */
    public int panelTop() {
        return BODY_TOP + top() - PANEL_TO_CONTENT;
    }

    /** Returns the column, {@code width} wide, with one text row for each 9 px of its box. */
    public Component build() {
        final Pen pen = Pen.start();
        final int shift = top() - TEXT_ROW;
        for (final Piece piece : pieces) {
            final BodyLine line =
                    piece.plate() ? BodyLine.start(shift + piece.y()) : BodyLine.textRow(shift + piece.y());
            piece.draw().accept(line);
            pen.moveTo(0).append(line.build(), line.width());
        }
        final Component drawn = pen.build(width);
        final int own = pieces.stream().anyMatch(Piece::plate) ? PLATE_ROWS : 1;
        final int rows = rows();
        return rows <= own ? drawn : drawn.append(Component.text("\n".repeat(rows - own)));
    }
}
