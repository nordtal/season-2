package eu.nordtal.displaytags.wrapper.display;

/**
 * The text alignment of a text display. This is not sent as a value of its own - it is encoded in
 * the style flags - so {@code value} only exists for completeness.
 * <p>
 * Verified for Minecraft 26.2 on 2026-08-29 against {@code Display$TextDisplay$Align} in
 * {@code paper-26.2.jar} (CENTER = 0, LEFT = 1, RIGHT = 2).
 */
public enum TextAlignment {
    CENTER(0),
    LEFT(1),
    RIGHT(2);

    public final int value;

    TextAlignment(int value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return super.toString().toLowerCase();
    }
}
