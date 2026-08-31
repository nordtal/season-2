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
    // \uEF05..\uEF08 are reserved for the dimension icons (Nordtal, farm world, Nether, End) -
    // allocated in resource-pack/README.md, not named here because nothing in season 2 reads them
    // yet. The four below are the hunger games HUD's own icons - allocated for this feature, still
    // undrawn: resource-pack/README.md#code-point-allocation lists them as "new, undrawn", and
    // drawing the actual sprites is design work, not code (docs/state-of-play.md \u00A73). The bossbar
    // font has no entry for these code points yet either; that is the same known gap, not a second
    // one - see resource-pack/README.md before adding the PNGs and the font.json entries.
    public static final String BOSSBAR_ICON_ALIVE = "\uEF09";
    public static final String BOSSBAR_ICON_DEATHS = "\uEF0A";
    public static final String BOSSBAR_ICON_LOOT_POINT = "\uEF0B";
    public static final String BOSSBAR_ICON_BORDER = "\uEF0C";

    // Bearing arrows - U+EF10..U+EF1F, height 10 / ascent 4, sixteen 22.5-degree steps clockwise
    // from straight ahead. Shared by /navigate (SMP), the hunger games "nearest living player"
    // arrow and its "nearest loot point" arrow. Undrawn - see the note above.
    public static final String BOSSBAR_ARROW_000_0 = "\uEF10";
    public static final String BOSSBAR_ARROW_022_5 = "\uEF11";
    public static final String BOSSBAR_ARROW_045_0 = "\uEF12";
    public static final String BOSSBAR_ARROW_067_5 = "\uEF13";
    public static final String BOSSBAR_ARROW_090_0 = "\uEF14";
    public static final String BOSSBAR_ARROW_112_5 = "\uEF15";
    public static final String BOSSBAR_ARROW_135_0 = "\uEF16";
    public static final String BOSSBAR_ARROW_157_5 = "\uEF17";
    public static final String BOSSBAR_ARROW_180_0 = "\uEF18";
    public static final String BOSSBAR_ARROW_202_5 = "\uEF19";
    public static final String BOSSBAR_ARROW_225_0 = "\uEF1A";
    public static final String BOSSBAR_ARROW_247_5 = "\uEF1B";
    public static final String BOSSBAR_ARROW_270_0 = "\uEF1C";
    public static final String BOSSBAR_ARROW_292_5 = "\uEF1D";
    public static final String BOSSBAR_ARROW_315_0 = "\uEF1E";
    public static final String BOSSBAR_ARROW_337_5 = "\uEF1F";

    /**
     * All sixteen bearing arrows, {@link #BOSSBAR_ARROW_000_0} first, in clockwise order - the
     * array a bearing-in-degrees calculation indexes with {@code Math.floorMod(Math.round(bearing
     * / 22.5), 16)}.
     */
    public static final String[] BOSSBAR_ARROWS = {
            BOSSBAR_ARROW_000_0, BOSSBAR_ARROW_022_5, BOSSBAR_ARROW_045_0, BOSSBAR_ARROW_067_5,
            BOSSBAR_ARROW_090_0, BOSSBAR_ARROW_112_5, BOSSBAR_ARROW_135_0, BOSSBAR_ARROW_157_5,
            BOSSBAR_ARROW_180_0, BOSSBAR_ARROW_202_5, BOSSBAR_ARROW_225_0, BOSSBAR_ARROW_247_5,
            BOSSBAR_ARROW_270_0, BOSSBAR_ARROW_292_5, BOSSBAR_ARROW_315_0, BOSSBAR_ARROW_337_5,
    };
}
