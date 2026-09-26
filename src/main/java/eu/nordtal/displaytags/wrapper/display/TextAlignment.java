package eu.nordtal.displaytags.wrapper.display;

import java.util.Locale;

/**
 * The text alignment of a text display.
 *
 * This is not sent as a value of its own - it is encoded in the style flags - so {@code value}
 * only exists for completeness. The values match {@code Display$TextDisplay$Align} in Paper's own
 * jar: CENTER = 0, LEFT = 1, RIGHT = 2.
 */
public enum TextAlignment {
    CENTER(0),
    LEFT(1),
    RIGHT(2);

    public final int value;

    TextAlignment(final int value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return super.toString().toLowerCase(Locale.ROOT);
    }
}
