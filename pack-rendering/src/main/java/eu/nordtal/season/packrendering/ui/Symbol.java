package eu.nordtal.season.packrendering.ui;

import eu.nordtal.season.packrendering.Glyphs;

/** The 7 x 7 symbols of the design system, in every ui text font. Each advances 8: 7 columns and its spacing column. */
public enum Symbol {
    STAR(Glyphs.UI_SYMBOL_STAR),
    STAR_HOLLOW(Glyphs.UI_SYMBOL_STAR_HOLLOW),
    LEFT(Glyphs.UI_SYMBOL_LEFT),
    RIGHT(Glyphs.UI_SYMBOL_RIGHT),
    TICK(Glyphs.UI_SYMBOL_TICK),
    PIN(Glyphs.UI_SYMBOL_PIN),
    RAIL(Glyphs.UI_SYMBOL_RAIL),
    MEDAL(Glyphs.UI_SYMBOL_MEDAL),
    FLAG(Glyphs.UI_SYMBOL_FLAG),
    WHEEL(Glyphs.UI_SYMBOL_WHEEL),
    SWORDS(Glyphs.UI_SYMBOL_SWORDS),
    SHIELD(Glyphs.UI_SYMBOL_SHIELD),
    GRAVE(Glyphs.UI_SYMBOL_GRAVE),
    HANDIN(Glyphs.UI_SYMBOL_HANDIN),
    CHEST(Glyphs.UI_SYMBOL_CHEST),
    EYE(Glyphs.UI_SYMBOL_EYE),
    PICKAXE(Glyphs.UI_SYMBOL_PICKAXE),
    BOX(Glyphs.UI_SYMBOL_BOX),
    XP(Glyphs.UI_SYMBOL_XP),
    POINTER(Glyphs.UI_SYMBOL_POINTER),
    HEART(Glyphs.UI_SYMBOL_HEART),
    BORDER(Glyphs.UI_SYMBOL_BORDER),
    SKULL(Glyphs.UI_SYMBOL_SKULL),
    ARROW0(Glyphs.UI_SYMBOL_ARROW0),
    ARROW1(Glyphs.UI_SYMBOL_ARROW1),
    ARROW2(Glyphs.UI_SYMBOL_ARROW2),
    ARROW3(Glyphs.UI_SYMBOL_ARROW3),
    ARROW4(Glyphs.UI_SYMBOL_ARROW4),
    ARROW5(Glyphs.UI_SYMBOL_ARROW5),
    ARROW6(Glyphs.UI_SYMBOL_ARROW6),
    ARROW7(Glyphs.UI_SYMBOL_ARROW7),
    ARROW8(Glyphs.UI_SYMBOL_ARROW8),
    ARROW9(Glyphs.UI_SYMBOL_ARROW9),
    ARROW10(Glyphs.UI_SYMBOL_ARROW10),
    ARROW11(Glyphs.UI_SYMBOL_ARROW11),
    ARROW12(Glyphs.UI_SYMBOL_ARROW12),
    ARROW13(Glyphs.UI_SYMBOL_ARROW13),
    ARROW14(Glyphs.UI_SYMBOL_ARROW14),
    ARROW15(Glyphs.UI_SYMBOL_ARROW15);

    /** The cursor advance of every symbol. */
    public static final int ADVANCE = 8;

    /** From a symbol's first column to the text after it: the symbol, its spacing column and a 4 px move. */
    public static final int BEFORE_TEXT = 12;

    private final String glyph;

    Symbol(final String glyph) {
        this.glyph = glyph;
    }

    String glyph() {
        return glyph;
    }

    /** Returns the compass arrow for {@code direction}, 0 ahead and clockwise in 16 steps. */
    public static Symbol arrow(final int direction) {
        return valueOf("ARROW" + Math.floorMod(direction, 16));
    }
}
