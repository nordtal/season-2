package eu.nordtal.season.packrendering.ui;

import java.util.List;
import java.util.function.IntUnaryOperator;
import net.kyori.adventure.text.format.TextColor;
import org.jspecify.annotations.Nullable;

/** One mark at the right end of a header line: a short text or a symbol, in its colour. */
public record Mark(@Nullable String text, @Nullable Symbol symbol, TextColor colour) {

    /** The columns between two marks. */
    public static final int GAP = 3;

    public Mark {
        if ((text == null) == (symbol == null)) {
            throw new IllegalArgumentException("a mark is a text or a symbol");
        }
    }

    /** A text mark, such as a count. */
    public static Mark text(final String text, final TextColor colour) {
        return new Mark(text, null, colour);
    }

    /** A symbol mark. */
    public static Mark symbol(final Symbol symbol, final TextColor colour) {
        return new Mark(null, symbol, colour);
    }

    /** The columns this mark inks. */
    public int width() {
        return text == null ? Symbol.ADVANCE - 1 : Letters.visibleWidth(text);
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
                pen.text(shift, mark.text, mark.colour);
            }
            end = left - 1 - GAP;
        }
        return left;
    }
}
