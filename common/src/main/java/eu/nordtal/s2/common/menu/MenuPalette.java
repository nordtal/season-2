package eu.nordtal.s2.common.menu;

import net.kyori.adventure.text.format.TextColor;

/**
 * The four colours a menu paints text and pictograms in.
 *
 * <h2>Why these are not the five in docs/presentation.md</h2>
 * That palette says what a <em>sentence</em> is - good, bad, warning, furniture, quoted - and it is
 * used where a line of chat has a mood. A menu row has none: a POI's name is neither good nor bad,
 * and painting it green because the click succeeds would be inventing a mood the design does not
 * have. What a row needs instead is a reading hierarchy - the thing you are looking for, and the
 * number beside it - which is what {@link #INK} and {@link #SOFT} are.
 *
 * <p>The values are the panel's own greys, the same family
 * {@code resource-pack/tools/generate_gui_panels.py} draws the frame and the recesses in, so
 * nothing here adds a colour to the pack. They are held in this module rather than in each menu
 * because two menus picking their own greys is how a window ends up with two kinds of "quiet".</p>
 *
 * <p><b>Pictograms are drawn white in the pack and tinted here.</b> That is the rule section 5 of
 * docs/presentation.md states for the system-line icons and it holds for the same reason: the
 * client multiplies a glyph by its component's colour, so white art can be painted any colour and
 * dark art cannot be painted lighter.</p>
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

    private MenuPalette() {
    }
}
