package eu.nordtal.season.packrendering.ui;

import net.kyori.adventure.text.format.TextColor;

/** The design system's text colours. */
public final class Colours {

    /** Art: a glyph that carries its own colours is drawn white, so nothing tints it. */
    public static final TextColor ART = TextColor.color(0xFFFFFF);

    /** Ink: a panel's text, a header, a plate's label. */
    public static final TextColor INK = TextColor.color(0x404040);

    /** Title: a header's symbol and bold name. */
    public static final TextColor TITLE = TextColor.color(0x2B2B2B);

    /** Soft: a value beside a label, a mark, a count. */
    public static final TextColor SOFT = TextColor.color(0x555555);

    /** Faint: a disabled plate's label, a header's rule. */
    public static final TextColor FAINT = TextColor.color(0x8B8B8B);

    /** Accent: a favourite's star, a card's open symbol. */
    public static final TextColor ACCENT = TextColor.color(0x345AB4);

    /** Preview: the lighter accent fill a hand-in offer adds to a progress bar. */
    public static final TextColor PREVIEW = TextColor.color(0x7FA0E4);

    /** Progress bar track fill. */
    public static final TextColor BAR_TRACK = TextColor.color(0x8B8B8B);

    /** Completed progress bar fill. */
    public static final TextColor BAR_DONE = TextColor.color(0x787878);

    /** Progress bar bottom light line on an open card. */
    public static final TextColor BAR_EDGE = TextColor.color(0xF0F0F0);

    /** Progress bar bottom light line on a completed card. */
    public static final TextColor BAR_EDGE_DONE = TextColor.color(0xC6C6C6);

    /** Faded completed milestone rail connector fill. */
    public static final TextColor RAIL_DONE_FADED = TextColor.color(0x8899BF);

    /** Faded future milestone rail connector fill. */
    public static final TextColor RAIL_FUTURE_FADED = TextColor.color(0xAEAEAE);

    /** Faded completed milestone rail connector bottom edge. */
    public static final TextColor RAIL_EDGE_DONE_FADED = TextColor.color(0x7F8CA7);

    /** Completed milestone rail connector bottom edge. */
    public static final TextColor RAIL_EDGE_DONE = TextColor.color(0x1F3B7D);

    /** Faded future milestone rail connector bottom edge. */
    public static final TextColor RAIL_EDGE_FADED = TextColor.color(0xD3D3D3);

    /** Future milestone rail connector bottom edge. */
    public static final TextColor RAIL_EDGE = TextColor.color(0xE4E4E4);

    /** Completed milestone rail node tick. */
    public static final TextColor RAIL_TICK = TextColor.color(0xDFDFDF);

    /** Faded future milestone rail node number. */
    public static final TextColor RAIL_NODE_FADED = TextColor.color(0x9A9A9A);

    /** Future milestone rail node number. */
    public static final TextColor RAIL_NODE = TextColor.color(0x6E6E6E);

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
