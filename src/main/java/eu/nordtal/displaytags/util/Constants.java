package eu.nordtal.displaytags.util;

public class Constants {
    /**
     * The ARGB background colour a vanilla text display starts out with (0x40000000, 25% black).
     * Verified for Minecraft 26.2 on 2026-08-29 against {@code Display$TextDisplay.INITIAL_BACKGROUND}
     * in {@code paper-26.2.jar}.
     */
    public static int DEFAULT_TEXT_DISPLAY_BACKGROUND = 1073741824;

    /**
     * A fully transparent ARGB background - nothing is drawn behind the text.
     */
    public static final int TRANSPARENT_TEXT_DISPLAY_BACKGROUND = 0;

    /**
     * The text opacity vanilla draws a name tag with where it is <b>not</b> in view.
     * <p>
     * {@code EntityRenderer#renderNameTag} passes the colour {@code 553648127} = {@code 0x20FFFFFF}
     * to its see-through pass, so alpha 32 is not a taste decision - it is the number the game
     * itself uses, and it is also the reason the value is not any lower: a text display discards
     * its text entirely for an opacity between 4 and 26 (minecraft.wiki, "Display", checked
     * 2026-09-19).
     */
    public static final int VANILLA_OCCLUDED_TEXT_OPACITY = 32;
}
