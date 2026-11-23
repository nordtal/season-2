package eu.nordtal.season.packrendering.ui;

import eu.nordtal.season.packrendering.menu.MenuFont;
import java.util.List;
import java.util.function.IntUnaryOperator;
import net.kyori.adventure.text.format.TextColor;
import org.jspecify.annotations.Nullable;

/** One mark at the right end of a header line: a short text, five-pixel figure or symbol, in its colour. */
public record Mark(@Nullable String text, @Nullable Symbol symbol, TextColor colour, boolean small) {

    /** The columns between two marks. */
    public static final int GAP = 3;

    public Mark {
        if ((text == null) == (symbol == null)) {
            throw new IllegalArgumentException("a mark is a text or a symbol");
        }
        if (small && text == null) {
            throw new IllegalArgumentException("a five-pixel mark requires text");
        }
    }

    /** A text mark, such as a container name or state word. */
    public static Mark text(final String text, final TextColor colour) {
        return new Mark(text, null, colour, false);
    }

    /** A key-number mark drawn in the five-pixel font, such as a grave's experience or a count. */
    public static Mark small(final String text, final TextColor colour) {
        return new Mark(text, null, colour, true);
    }

    /** A symbol mark. */
    public static Mark symbol(final Symbol symbol, final TextColor colour) {
        return new Mark(null, symbol, colour, false);
    }

    /** The columns this mark inks. */
    public int width() {
        if (text == null) {
            return Symbol.ADVANCE - 1;
        }
        return small ? MenuFont.visible(MenuFont.fold(text)) : Letters.visibleWidth(text);
    }

    /** Draws {@code marks} right to left, the first ending on {@code right}, and returns the last's first column. */
    static int draw(
            final Pen pen, final List<Mark> marks, final int right, final int shift, final IntUnaryOperator local) {
        int end = right;
        int left = right + 1;
        for (final Mark mark : marks) {
            left = end - mark.width() + 1;
            pen.moveTo(local.applyAsInt(left));
            if (mark.symbol != null) {
                pen.symbol(shift, mark.symbol, mark.colour);
            } else if (mark.text != null) {
                if (mark.small) {
                    pen.small(mark.text, mark.colour);
                } else {
                    pen.text(shift, mark.text, mark.colour);
                }
            }
            end = left - 1 - GAP;
        }
        return left;
    }
}
