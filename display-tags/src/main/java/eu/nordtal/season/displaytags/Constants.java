package eu.nordtal.season.displaytags;

public class Constants {
    /**
     * The ARGB background colour of a vanilla text display.
     * It equals {@code Display$TextDisplay.INITIAL_BACKGROUND}.
     */
    public static final int DEFAULT_TEXT_DISPLAY_BACKGROUND = 1073741824;

    /** A fully transparent ARGB background. */
    public static final int TRANSPARENT_TEXT_DISPLAY_BACKGROUND = 0;

    /**
     * The text opacity of a vanilla name tag that is not in view.
     * A text display drops its text for an opacity from 4 to 26.
     */
    public static final int VANILLA_OCCLUDED_TEXT_OPACITY = 32;
}
