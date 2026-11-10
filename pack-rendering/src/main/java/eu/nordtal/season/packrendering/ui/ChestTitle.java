package eu.nordtal.season.packrendering.ui;

import eu.nordtal.season.packrendering.Glyphs;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.jspecify.annotations.Nullable;

/**
 * Composes a chest title that draws the whole window: the panel, the header line, slot frames and plates on cells.
 * The client draws the title from vanilla's anchor (8, 6); every position here is a window pixel.
 */
public final class ChestTitle {

    /** Vanilla's title anchor, where the component starts. */
    public static final int ANCHOR_X = 8;

    /** The window row of the header's capitals: vanilla's title row, the ui text font of shift 0. */
    public static final int CAPS_Y = 6;

    /** The last column a header's name or marks may ink. */
    public static final int RIGHT = 168;

    /** Where a header's name starts after its symbol: the symbol at 8, the icon gap 5. */
    public static final int NAME_X = ANCHOR_X + Symbol.BEFORE_TEXT;

    /** The room of a vanilla container's name, which has no symbol: x 8 to 168. */
    public static final int CONTAINER_ROOM = RIGHT - ANCHOR_X + 1;

    /** The first content slot frame's corner. */
    public static final int SLOT_X = 7;

    /** The first content slot frame's top. */
    public static final int SLOT_Y = 17;

    /** From one content cell to the next. */
    public static final int STEP = 18;

    /** The rows a chest menu has at most. */
    public static final int MAX_ROWS = 6;

    /** Vanilla's title row, where the ui fonts of shift 0 put their capitals and the top of their art. */
    private static final int TITLE_ROW = 6;

    private static final int WIDTH = 176;
    private static final int NAME_TO_MARK = 8;
    private static final int LABEL_PAD = 4;
    private static final int LABEL_IN_PLATE = 5;

    private final Pen pen = Pen.start();

    private ChestTitle(final int rows, final List<String> panels) {
        if (rows < 1 || rows > MAX_ROWS) {
            throw new IllegalArgumentException("a chest has 1 to " + MAX_ROWS + " rows, not " + rows);
        }
        pen.moveTo(0 - ANCHOR_X).art(Glyphs.FONT_UI_PANEL, panels.get(rows - 1), WIDTH);
    }

    /** Starts the title of a Nordtal menu of {@code rows} rows: the panel with the player's slot frames only. */
    public static ChestTitle of(final int rows) {
        return new ChestTitle(rows, Glyphs.UI_CHEST_PANELS);
    }

    /**
     * Returns the title of a vanilla container: the panel with every slot frame and {@code name}, no symbol.
     * A literal name is cut with an ellipsis past {@link #CONTAINER_ROOM}; a translated name is vanilla's and fits.
     */
    public static Component container(final int rows, final Component name) {
        final ChestTitle title = new ChestTitle(rows, Glyphs.UI_CHEST_PANELS_PLAIN);
        title.pen.moveTo(0);
        return Component.empty().append(title.pen.build()).append(ContainerNames.cut(name, CONTAINER_ROOM));
    }

    /** Draws the header line: {@code symbol} and {@code name} in ink, {@code marks} right-aligned to {@link #RIGHT}. */
    public ChestTitle header(final @Nullable Symbol symbol, final String name, final List<Mark> marks) {
        final int nameX = symbol == null ? ANCHOR_X : NAME_X;
        final int marksLeft = Mark.draw(pen, marks, RIGHT, CAPS_Y - TITLE_ROW, this::local);
        final int room = (marks.isEmpty() ? RIGHT + 1 : marksLeft - NAME_TO_MARK) - nameX;
        if (symbol != null) {
            pen.moveTo(local(ANCHOR_X)).symbol(CAPS_Y - TITLE_ROW, symbol, Colours.INK);
        }
        pen.moveTo(local(nameX)).text(CAPS_Y - TITLE_ROW, Letters.cut(name, room), Colours.INK);
        return this;
    }

    /** Draws vanilla's slot frame on the content cell at {@code column}, {@code row}. */
    public ChestTitle slot(final int column, final int row) {
        pen.moveTo(local(SLOT_X + STEP * column)).art(Pen.artFont(artShift(row)), Glyphs.UI_SLOT, STEP);
        return this;
    }

    /** Draws a plate over {@code cells} cells from {@code column} on {@code row}: cell + 1 to + 16 across each end. */
    public ChestTitle plate(final int column, final int row, final int cells, final Look look) {
        pen.moveTo(local(plateX(column))).plate(artShift(row), look, plateWidth(cells));
        return this;
    }

    /** Draws {@code label} on the plate there, centred, after {@code symbol} if one is given. */
    public ChestTitle label(
            final int column,
            final int row,
            final int cells,
            final @Nullable Symbol symbol,
            final String label,
            final Look look) {
        final int width = plateWidth(cells);
        final int room = width - 2 * LABEL_PAD - (symbol == null ? 0 : Symbol.BEFORE_TEXT);
        final String text = Letters.cut(label, room);
        final int inked = Letters.visibleWidth(text) + (symbol == null ? 0 : Symbol.BEFORE_TEXT);
        int x = plateX(column) + (width - inked + 1) / 2;
        final int shift = textShift(row);
        if (symbol != null) {
            pen.moveTo(local(x)).symbol(shift, symbol, look.ink());
            x += Symbol.BEFORE_TEXT;
        }
        pen.moveTo(local(x)).text(shift, text, look.ink());
        return this;
    }

    /** Draws {@code text} with its first column at window x {@code x} on the label row of content row {@code row}. */
    public ChestTitle text(final int x, final int row, final String text, final TextColor colour) {
        pen.moveTo(local(x)).text(textShift(row), text, colour);
        return this;
    }

    /** Draws {@code symbol} with its first column at window x {@code x} on the label row of content row {@code row}. */
    public ChestTitle symbol(final int x, final int row, final Symbol symbol, final TextColor colour) {
        pen.moveTo(local(x)).symbol(textShift(row), symbol, colour);
        return this;
    }

    /** Draws {@code text} centred over {@code cells} cells from {@code column} on {@code row}, with no plate. */
    public ChestTitle centred(
            final int column, final int row, final int cells, final String text, final TextColor colour) {
        final int width = plateWidth(cells);
        final String fitted = Letters.cut(text, width);
        return text(plateX(column) + (width - Letters.visibleWidth(fitted) + 1) / 2, row, fitted, colour);
    }

    /** Returns the title: the window, then {@code readable}, which the client draws at the anchor. */
    public Component build(final Component readable) {
        return Component.empty().append(pen.build(0)).append(readable);
    }

    /** Returns the title with nothing readable after the drawn header. */
    public Component build() {
        return pen.build(0);
    }

    /** The window x of a plate's first column on {@code column}. */
    public static int plateX(final int column) {
        return SLOT_X + STEP * column + 1;
    }

    /** The width of a plate over {@code cells} cells, which leaves 2 px between plates on neighbouring cells. */
    public static int plateWidth(final int cells) {
        return STEP * (cells - 1) + 16;
    }

    /** The art font shift that puts a glyph's top on content row {@code row}'s cell top. */
    public static int artShift(final int row) {
        return SLOT_Y + STEP * row - TITLE_ROW;
    }

    /** The text font shift that puts capitals at plate + 5 on content row {@code row}. */
    public static int textShift(final int row) {
        return SLOT_Y + STEP * row + LABEL_IN_PLATE - TITLE_ROW;
    }

    private int local(final int windowX) {
        return windowX - ANCHOR_X;
    }

    /** Draws {@code label} at plate + 4 on a plate of {@code cells}, with {@code value} right-aligned in soft. */
    public ChestTitle row(
            final int column,
            final int row,
            final int cells,
            final String label,
            final @Nullable String value,
            final Look look) {
        final int left = plateX(column) + LABEL_PAD;
        final int right = plateX(column) + plateWidth(cells) - 1 - LABEL_PAD;
        final int shift = textShift(row);
        int room = right - left + 1;
        if (value != null) {
            final int valueX = right - Letters.visibleWidth(value) + 1;
            pen.moveTo(local(valueX)).text(shift, value, look.soft());
            room = valueX - NAME_TO_MARK - left;
        }
        pen.moveTo(local(left)).text(shift, Letters.cut(label, room), look.ink());
        return this;
    }
}
