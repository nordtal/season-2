package eu.nordtal.season.displaytags.wrapper.display;

import java.util.Locale;

/** The alignment of a text display, sent as style flags. The values match {@code Display$TextDisplay$Align}. */
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
