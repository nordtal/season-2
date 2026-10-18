package eu.nordtal.season.papercommon.menu;

import net.kyori.adventure.text.format.TextColor;

/**
 * The five colours of menu text and pictograms.
 * The pack draws pictograms white, and the client multiplies them by the colour.
 */
public final class MenuPalette {

    /** The colour of a row's subject, such as a POI name or the label of a button on a light plate. */
    public static final TextColor INK = TextColor.color(0x2A2A30);

    public static final TextColor SOFT = TextColor.color(0x5A5A62);

    public static final TextColor ON_PLATE = TextColor.color(0xFFFFFF);

    public static final TextColor DISABLED = TextColor.color(0x8C8C90);

    public static final TextColor PROGRESS = TextColor.color(0x3C6A3E);

    private MenuPalette() {}
}
