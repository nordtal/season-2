package eu.nordtal.displaytags.wrapper.display;

import java.util.Locale;

/**
 * The text alignment of a text display, sent as style flags rather than as this value.
 *
 * The values match {@code Display$TextDisplay$Align}: CENTER = 0, LEFT = 1, RIGHT = 2.
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
