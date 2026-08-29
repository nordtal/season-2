package eu.nordtal.displaytags.util;

public class Constants {
    /**
     * The ARGB background colour a vanilla text display starts out with (0x40000000, 25% black).
     * Verified for Minecraft 26.2 on 2026-08-29 against {@code Display$TextDisplay.INITIAL_BACKGROUND}
     * in {@code paper-26.2.jar}.
     */
    public static int DEFAULT_TEXT_DISPLAY_BACKGROUND = 1073741824;
}
