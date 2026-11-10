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

    /** The panel tops the pack has a font for: over a first plate line, a first text line, a frame, the wheel. */
    public static final List<Integer> TOPS = List.of(49, 50, 43, 64);

    private static final int HEADER_ROW = 280;
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
        // The header row is the title, 10 px and the 20 px warning button, centred: the title starts at (W - 280) / 2.
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

    /** Draws the header line at panel + 7: {@code symbol}, {@code name}, {@code marks} ending at panel + w - 8. */
    public DialogTitle header(final @Nullable Symbol symbol, final String name, final List<Mark> marks) {
        final int shift = top + MARGIN - TITLE_ROW;
        final int nameX = MARGIN + (symbol == null ? 0 : Symbol.BEFORE_TEXT);
        final int right = panel.width() - 1 - MARGIN;
        final int marksLeft = Mark.draw(pen, marks, right, shift, x -> left + x);
        final int room = (marks.isEmpty() ? right + 1 : marksLeft - NAME_TO_MARK) - nameX;
        if (symbol != null) {
            pen.moveTo(left + MARGIN).symbol(shift, symbol, Colours.INK);
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

    /** Returns the title, {@link #NET_WIDTH} wide. */
    public Component build() {
        return pen.build(NET_WIDTH);
    }
}
