package eu.nordtal.season.packrendering.ui;

import net.kyori.adventure.text.format.TextColor;

/** The design system's text colours. */
public final class Colours {

    /** Art: a glyph that carries its own colours is drawn white, so nothing tints it. */
    public static final TextColor ART = TextColor.color(0xFFFFFF);

    /** Ink: a panel's text, a header, a plate's label. */
    public static final TextColor INK = TextColor.color(0x404040);

    /** Soft: a value beside a label, a mark, a count. */
    public static final TextColor SOFT = TextColor.color(0x555555);

    /** Faint: a disabled plate's label. */
    public static final TextColor FAINT = TextColor.color(0x8B8B8B);

    /** Accent: a favourite's star, a card's open symbol. */
    public static final TextColor ACCENT = TextColor.color(0x345AB4);

    /** On accent: a label on a primary plate. */
    public static final TextColor ON_ACCENT = TextColor.color(0xFFFFFF);

    /** Refusal: the fact that blocks the reader. */
    public static final TextColor REFUSAL = TextColor.color(0xAA0000);

    /** A tooltip's title. */
    public static final TextColor TOOLTIP_TITLE = TextColor.color(0xFFFFFF);

    /** A tooltip's text. */
    public static final TextColor TOOLTIP_TEXT = TextColor.color(0xAAAAAA);

    /** A tooltip's last line when it says what a click does. */
    public static final TextColor HINT = TextColor.color(0x9DB4EE);

    private Colours() {}
}
