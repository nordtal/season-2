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
    public static final List<Integer> TOPS = List.of(49, 50, 53, 55, 57, 43, 62);

    private static final int HEADER_ROW = 280;
    private static final int FIELD_FRAME = 184;

    /** The frame glyph's top stands this far above its font's text row. */
    private static final int FRAME_ABOVE = 2;

    private static final int NAME_TO_MARK = 8;

    private final Pen pen = Pen.start();
    private final DialogPanel panel;
    private final int top;
    private final int left;

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

    /** Draws the header at panel + 7: {@code symbol}, {@code name}, {@code marks} ending 8 inside the right edge. */
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
        final int room = (marks.isEmpty() ? right + 1 : marksLeft - NAME_TO_MARK) - nameX;
        if (symbol != null) {
            pen.moveTo(left + margin).symbol(shift, symbol, Colours.INK);
        }
        pen.moveTo(left + nameX).text(shift, Letters.cut(name, room), Colours.INK);
        return this;
    }

    /** Draws {@code text} centred on the panel, its capitals at screen y {@code y}, such as the station's refusal. */
    public DialogTitle centred(final int y, final String text, final TextColor colour) {
        pen.moveTo(left + panel.width() / 2 - Letters.visibleWidth(text) / 2).text(y - TITLE_ROW, text, colour);
        return this;
    }

    /** Draws {@code text} at panel + {@code x} with its capitals at screen y {@code y}. */
    public DialogTitle text(final int x, final int y, final String text, final TextColor colour) {
        pen.moveTo(left + x).text(y - TITLE_ROW, text, colour);
        return this;
    }

    /** Draws the station field's 184 x 24 frame at panel + {@code x}, its top at screen y {@code y}. */
    public DialogTitle fieldFrame(final int x, final int y) {
        pen.moveTo(left + x).art(Pen.artFont(y - TITLE_ROW + FRAME_ABOVE), Glyphs.UI_FIELD_FRAME, FIELD_FRAME);
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
