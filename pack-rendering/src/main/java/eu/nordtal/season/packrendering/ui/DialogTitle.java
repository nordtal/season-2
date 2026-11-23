package eu.nordtal.season.packrendering.ui;

import eu.nordtal.season.packrendering.Glyphs;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.jspecify.annotations.Nullable;

/**
 * Composes a dialog title that draws the panel behind the dialog and its header line, at a fixed net width of 250.
 * The title's text row is screen y 13; x positions here count from the panel's left edge.
 */
public final class DialogTitle {

    /** Every title's net width, so the hidden warning button and its tooltip sit at one spot. */
    public static final int NET_WIDTH = 250;

    /** The screen row of the title's capitals. */
    public static final int TITLE_ROW = 13;

    /** The panel's margin: from its edge to the header's capitals and to its content. */
    public static final int MARGIN = 7;

    /** The panel tops the pack has a font for, as glyphs.json describes them. */
    public static final List<Integer> TOPS = List.of(43, 45, 46, 49, 50, 51, 52, 53, 54, 55, 56, 57);

    private static final int HEADER_ROW = 280;

    private static final int NAME_TO_MARK = 8;

    /** From the name's last advance to the rule's first column. */
    private static final int NAME_TO_RULE = 5;

    /** The shortest rule drawn, so a long name stands without a stub after it. */
    private static final int SHORTEST_RULE = 9;

    /** From the header's capitals to its last row of ink, a descender's. */
    private static final int HEADER_INK = 7;

    private final Pen pen = Pen.start();
    private final DialogPanel panel;
    private final int top;
    private final int left;
    private @Nullable Header header;

    /** What the header drew, so a body line can draw it again over its focus cover. */
    private record Header(
            @Nullable Symbol symbol,
            String name,
            List<Mark> marks,
            int margin,
            int nameX,
            int right,
            int ruleStart,
            int ruleWidth) {}

    private DialogTitle(final DialogPanel panel, final int top) {
        final String font = Glyphs.FONT_UI_DIALOG.get(top);
        if (font == null) {
            throw new IllegalArgumentException("the pack draws a dialog panel from y " + TOPS + ", not " + top);
        }
        this.panel = panel;
        this.top = top;
        // The title, 10 px and the 20 px warning button are centred: the title starts 140 left of the centre.
        this.left = HEADER_ROW / 2 - panel.width() / 2;
        int rest = panel.width();
        pen.moveTo(left);
        for (final String tile : panel.tiles()) {
            final int width = Math.min(rest, DialogPanel.TILE);
            pen.art(font, tile, width);
            rest -= width;
        }
    }

    /** Starts the title of {@code panel} whose top is at screen y {@code top}, one of {@link #TOPS}. */
    public static DialogTitle of(final DialogPanel panel, final int top) {
        return new DialogTitle(panel, top);
    }

    /**
     * Draws the header at panel + 7: {@code symbol}, the bold {@code name}, a rule and {@code marks}.
     * The rule runs out to the marks or the right margin. The marks end 8 inside the right edge.
     */
    public DialogTitle header(final @Nullable Symbol symbol, final String name, final List<Mark> marks) {
        return header(symbol, name, marks, MARGIN);
    }

    /** Draws the header {@code margin} inside the panel, for a panel with a wider margin, such as the wheel's. */
    public DialogTitle header(
            final @Nullable Symbol symbol, final String name, final List<Mark> marks, final int margin) {
        final int shift = top + margin - TITLE_ROW;
        final int nameX = margin + (symbol == null ? 0 : Symbol.BEFORE_TEXT);
        final int right = panel.width() - 1 - margin;
        final int marksLeft = Mark.draw(pen, marks, right, shift, x -> left + x);
        final int end = marks.isEmpty() ? right : marksLeft - NAME_TO_MARK - 1;
        if (symbol != null) {
            pen.moveTo(left + margin).symbol(shift, symbol, Colours.TITLE);
        }
        final String shown = Letters.boldCut(name, end + 1 - nameX);
        pen.moveTo(left + nameX).bold(Pen.textFont(shift), shown, Colours.TITLE);
        final int rule = nameX + Letters.boldWidth(shown) + NAME_TO_RULE;
        final int ruleWidth = end + 1 - rule >= SHORTEST_RULE ? end + 1 - rule : 0;
        if (ruleWidth > 0) {
            pen.moveTo(left + rule).inkRuns(Pen.textFont(shift), Glyphs.UI_RULE, ruleWidth, Colours.FAINT);
        }
        header = new Header(symbol, shown, List.copyOf(marks), margin, nameX, right, rule, ruleWidth);
        return this;
    }

    /**
     * The header drawn again for a body line whose box's top lies on panel row {@code row}.
     * It is empty when the line's focus cover misses the header's ink. Its capitals stand {@code textRow} under the
     * box's top less than the line's text row does; its x counts from the panel's centre; it advances 0.
     */
    public Component ink(final int row, final int textRow) {
        final Header header = this.header;
        if (header == null || row < header.margin() || row > header.margin() + HEADER_INK) {
            return Component.empty();
        }
        final int shift = header.margin() - row - textRow;
        final int centre = panel.width() / 2;
        final Pen ink = Pen.start(Pen.Layer.INK);
        Mark.draw(ink, header.marks(), header.right(), shift, x -> x - centre);
        final Symbol symbol = header.symbol();
        if (symbol != null) {
            ink.moveTo(header.margin() - centre).symbol(shift, symbol, Colours.TITLE);
        }
        ink.moveTo(header.nameX() - centre).bold(Pen.textFont(shift), header.name(), Colours.TITLE);
        if (header.ruleWidth() > 0) {
            ink.moveTo(header.ruleStart() - centre)
                    .inkRuns(Pen.textFont(shift), Glyphs.UI_RULE, header.ruleWidth(), Colours.FAINT);
        }
        return ink.build(0);
    }

    /** Draws {@code text} centred on the panel, its capitals at screen y {@code y}. */
    public DialogTitle centred(final int y, final String text, final TextColor colour) {
        pen.moveTo(left + panel.width() / 2 - Letters.width(text) / 2).text(y - TITLE_ROW, text, colour);
        return this;
    }

    /** Draws {@code text} at panel + {@code x} with its capitals at screen y {@code y}. */
    public DialogTitle text(final int x, final int y, final String text, final TextColor colour) {
        pen.moveTo(left + x).text(y - TITLE_ROW, text, colour);
        return this;
    }

    /** Adds {@code words} for the narrator, which it reads with the title, such as what a redraw changed. */
    public DialogTitle words(final String words) {
        pen.append(BodyLine.silent(words), 0);
        return this;
    }

    /** Returns the title, {@link #NET_WIDTH} wide. */
    public Component build() {
        return pen.build(NET_WIDTH);
    }
}
