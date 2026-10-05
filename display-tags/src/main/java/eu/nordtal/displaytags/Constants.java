package eu.nordtal.displaytags;

public class Constants {
    /**
     * The ARGB background colour a vanilla text display starts out with.
     *
     * Matches {@code Display$TextDisplay.INITIAL_BACKGROUND} in Paper's own jar.
     */
    public static final int DEFAULT_TEXT_DISPLAY_BACKGROUND = 1073741824;

    /**
     * A fully transparent ARGB background: nothing is drawn behind the text.
     */
    public static final int TRANSPARENT_TEXT_DISPLAY_BACKGROUND = 0;

    /**
     * The text opacity vanilla draws a name tag with where it is not in view, {@code 0x20FFFFFF}'s alpha.
     *
     * A text display drops its text entirely for an opacity between 4 and 26, so it cannot be lower.
     */
    public static final int VANILLA_OCCLUDED_TEXT_OPACITY = 32;
}
