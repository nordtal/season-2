package eu.nordtal.s2.common;

/**
 * Code points of the characters the resource pack defines, so a plugin never hardcodes a
 * private-use escape that the pack has since moved.
 *
 * <p><b>The authoritative mapping is {@code resource-pack/README.md}</b>, "Code point allocation"
 * (decided 2026-08-31). It covers both fonts - {@code minecraft/font/default.json} and
 * {@code nordtal/font/bossbar.json} - and this class, those two files and that table are three
 * mirrors of one allocation. A change is a change in all of them, in one commit.
 *
 * <p>Constants are grouped by font. {@code minecraft:default} is drawn as ordinary text - tab
 * list, chat, nametags, Text Display boards. {@code nordtal:bossbar} is the boss bar HUDs only,
 * with the vanilla bar made invisible; the two fonts allocate independently, so the same code
 * point can mean two different glyphs depending on which font a component names.
 */
public final class Glyphs {

    private Glyphs() {
    }

    // === minecraft:default ===

    // Player badges - U+E000..U+E00F
    public static final String TAG_ADMIN = "\uE004";

    // Region flags - U+E010..U+E01F
    public static final String FLAG_OTHER = "\uE010";
    public static final String FLAG_GERMANY = "\uE011";
    public static final String FLAG_NETHERLANDS = "\uE012";
    public static final String FLAG_UNITED_KINGDOM = "\uE013";
    public static final String FLAG_UNITED_STATES = "\uE014";

    // Logo assets - U+E020..U+E02F
    public static final String LOGO_HEIGHT_24 = "\uE020";
    public static final String LOGO_HEIGHT_32 = "\uE021";

    // === nordtal:bossbar ===

    // Space advances - negative, U+F001..U+F128
    public static final String BOSSBAR_SPACE_MINUS_1 = "\uF001";
    public static final String BOSSBAR_SPACE_MINUS_2 = "\uF002";
    public static final String BOSSBAR_SPACE_MINUS_4 = "\uF004";
    public static final String BOSSBAR_SPACE_MINUS_8 = "\uF008";
    public static final String BOSSBAR_SPACE_MINUS_16 = "\uF016";
    public static final String BOSSBAR_SPACE_MINUS_32 = "\uF032";
    public static final String BOSSBAR_SPACE_MINUS_64 = "\uF064";
    public static final String BOSSBAR_SPACE_MINUS_128 = "\uF128";

    // Space advances - positive, U+FF01..U+FF32 plus the ordinary space
    // Not private use - see resource-pack/README.md's note on nordtal:bossbar before adding more.
    public static final String BOSSBAR_SPACE_PLUS_1 = "\uFF01";
    public static final String BOSSBAR_SPACE_PLUS_2 = "\uFF02";
    public static final String BOSSBAR_SPACE_PLUS_3 = " ";
    public static final String BOSSBAR_SPACE_PLUS_4 = "\uFF04";
    public static final String BOSSBAR_SPACE_PLUS_8 = "\uFF08";
    public static final String BOSSBAR_SPACE_PLUS_16 = "\uFF16";
    public static final String BOSSBAR_SPACE_PLUS_32 = "\uFF32";

    // Bar background segments - U+E000..U+E128, height 14 / ascent 6
    public static final String BOSSBAR_BG_END = "\uE000";
    public static final String BOSSBAR_BG_1 = "\uE001";
    public static final String BOSSBAR_BG_2 = "\uE002";
    public static final String BOSSBAR_BG_4 = "\uE004";
    public static final String BOSSBAR_BG_8 = "\uE008";
    public static final String BOSSBAR_BG_16 = "\uE016";
    public static final String BOSSBAR_BG_32 = "\uE032";
    public static final String BOSSBAR_BG_64 = "\uE064";
    public static final String BOSSBAR_BG_128 = "\uE128";

    // Status icons - U+EF00..U+EF0F, height 10 / ascent 4
    public static final String BOSSBAR_ICON_COMPASS = "\uEF00";
    // fblue/fgreen/fred/fwhite are one pennant-on-a-pole sprite in four colours - season 1's land
    // indicator, picked by the player's position: blue inside a player's preserved area, green on
    // permanent land, red in a reset zone, white in the server-protected spawn. Nothing in season 2
    // draws them yet; the meanings are recorded in resource-pack/README.md.
    public static final String BOSSBAR_ICON_FBLUE = "\uEF01";
    public static final String BOSSBAR_ICON_FGREEN = "\uEF02";
    public static final String BOSSBAR_ICON_FRED = "\uEF03";
    public static final String BOSSBAR_ICON_FWHITE = "\uEF04";
}
