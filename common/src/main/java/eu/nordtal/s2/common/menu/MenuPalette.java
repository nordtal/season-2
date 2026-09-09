package eu.nordtal.s2.common.menu;

import net.kyori.adventure.text.format.TextColor;

/**
 * The five colours a menu paints text and pictograms in - a reading hierarchy, deliberately not the
 * chat palette's moods, because a menu row is neither good nor bad.
 *
 * <p>The values are the panel's own greys, held here rather than in each menu so a window cannot end
 * up with two kinds of "quiet".
 *
 * <p>Pictograms are drawn white in the pack and tinted here: the client multiplies a glyph by its
 * component's colour, so white art can be painted any colour and dark art cannot be lightened.
 */
public final class MenuPalette {

    /** What a row's own subject is written in - a POI's name, a button's label on a light plate. */
    public static final TextColor INK = TextColor.color(0x2A2A30);

    /** The second thing on a row: a distance, a count, a page number. */
    public static final TextColor SOFT = TextColor.color(0x5A5A62);

    /** A label on a dark or saturated plate, where {@link #INK} would not read. */
    public static final TextColor ON_PLATE = TextColor.color(0xFFFFFF);

    /** A control that is there but cannot be used - a page button with no page behind it. */
    public static final TextColor DISABLED = TextColor.color(0x8C8C90);

    /**
     * A progress bar written as text, on a light plate. Its own colour rather than {@link #INK},
     * because otherwise the full and empty halves are told apart only by two character shapes.
     */
    public static final TextColor PROGRESS = TextColor.color(0x3C6A3E);

    private MenuPalette() {
    }
}
