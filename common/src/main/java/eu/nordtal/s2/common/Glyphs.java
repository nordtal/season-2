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
    // Donor star re-uses the retired settler tag's code point (resource-pack/README.md,
    // 2026-08-31) - drawn as of the dummy-texture pass, still placeholder-quality art.
    public static final String BADGE_DONOR_STAR = "\uE000";
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

    // Prestige crests - U+E030..U+E03C, height 9 / ascent 8, tiers 1-13 in order
    // (smp.md#prestige--a-crest-earned-by-time). Placeholder art: a shield outline with
    // a bottom-up fill gauge proportional to the tier, not the real thirteen-tier
    // coat-of-arms design - see resource-pack/README.md before treating these as final.
    public static final String PRESTIGE_CREST_01 = "\uE030";
    public static final String PRESTIGE_CREST_02 = "\uE031";
    public static final String PRESTIGE_CREST_03 = "\uE032";
    public static final String PRESTIGE_CREST_04 = "\uE033";
    public static final String PRESTIGE_CREST_05 = "\uE034";
    public static final String PRESTIGE_CREST_06 = "\uE035";
    public static final String PRESTIGE_CREST_07 = "\uE036";
    public static final String PRESTIGE_CREST_08 = "\uE037";
    public static final String PRESTIGE_CREST_09 = "\uE038";
    public static final String PRESTIGE_CREST_10 = "\uE039";
    public static final String PRESTIGE_CREST_11 = "\uE03A";
    public static final String PRESTIGE_CREST_12 = "\uE03B";
    public static final String PRESTIGE_CREST_13 = "\uE03C";

    /**
     * All thirteen prestige crests, tier 1 first, for {@code tier - 1} indexing against
     * {@code player_playtime.seconds} thresholds.
     */
    public static final String[] PRESTIGE_CRESTS = {
            PRESTIGE_CREST_01, PRESTIGE_CREST_02, PRESTIGE_CREST_03, PRESTIGE_CREST_04,
            PRESTIGE_CREST_05, PRESTIGE_CREST_06, PRESTIGE_CREST_07, PRESTIGE_CREST_08,
            PRESTIGE_CREST_09, PRESTIGE_CREST_10, PRESTIGE_CREST_11, PRESTIGE_CREST_12,
            PRESTIGE_CREST_13,
    };

    // === nordtal:board ===
    // The objective board and aura leaderboard's frame (smp.md#the-boards-and-the-npc).
    // A dedicated font, not minecraft:default - separated 2026-08-31 because, like
    // nordtal:bossbar, the frame's tiled segments need their own negative-advance space
    // provider to close the automatic 1px trailing gap every bitmap glyph gets, and that
    // mechanic has no business in the font ordinary chat and nametags use. Corners and
    // edges connect at each cell's center (4.5, 4.5) so any board width butts up
    // seamlessly - see resource-pack/README.md's board-frame section for the geometry.

    // Space advances - negative, mirrors nordtal:bossbar's own block (fonts allocate
    // independently, so reusing the same code points here is not a collision).
    public static final String BOARD_SPACE_MINUS_1 = "\uF001";
    public static final String BOARD_SPACE_MINUS_2 = "\uF002";
    public static final String BOARD_SPACE_MINUS_4 = "\uF004";
    public static final String BOARD_SPACE_MINUS_8 = "\uF008";
    public static final String BOARD_SPACE_MINUS_16 = "\uF016";
    public static final String BOARD_SPACE_MINUS_32 = "\uF032";
    public static final String BOARD_SPACE_MINUS_64 = "\uF064";
    public static final String BOARD_SPACE_MINUS_128 = "\uF128";

    // Corners - U+E040..U+E043, height 9 / ascent 8
    public static final String BOARD_CORNER_TOP_LEFT = "\uE040";
    public static final String BOARD_CORNER_TOP_RIGHT = "\uE041";
    public static final String BOARD_CORNER_BOTTOM_LEFT = "\uE042";
    public static final String BOARD_CORNER_BOTTOM_RIGHT = "\uE043";

    // Horizontal edge, tiled in powers of two - U+E044..U+E04B
    public static final String BOARD_EDGE_H_1 = "\uE044";
    public static final String BOARD_EDGE_H_2 = "\uE045";
    public static final String BOARD_EDGE_H_4 = "\uE046";
    public static final String BOARD_EDGE_H_8 = "\uE047";
    public static final String BOARD_EDGE_H_16 = "\uE048";
    public static final String BOARD_EDGE_H_32 = "\uE049";
    public static final String BOARD_EDGE_H_64 = "\uE04A";
    public static final String BOARD_EDGE_H_128 = "\uE04B";

    // Vertical edge - U+E04C..U+E04D
    public static final String BOARD_EDGE_V_LEFT = "\uE04C";
    public static final String BOARD_EDGE_V_RIGHT = "\uE04D";

    // Divider, tiled in powers of two like the horizontal edge - U+E04E..U+E055.
    // Allocated separately from the outer edge (2026-08-31) so an interior rule can
    // differ from the border, even though the current placeholder art draws both
    // identically - see resource-pack/README.md.
    public static final String BOARD_DIVIDER_1 = "\uE04E";
    public static final String BOARD_DIVIDER_2 = "\uE04F";
    public static final String BOARD_DIVIDER_4 = "\uE050";
    public static final String BOARD_DIVIDER_8 = "\uE051";
    public static final String BOARD_DIVIDER_16 = "\uE052";
    public static final String BOARD_DIVIDER_32 = "\uE053";
    public static final String BOARD_DIVIDER_64 = "\uE054";
    public static final String BOARD_DIVIDER_128 = "\uE055";
    // U+E056..U+E05F reserved for this font's own future growth.

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
    // Dimension icons - U+EF05..U+EF08, one per world the SMP/hunger games HUDs name.
    // Placeholder art from the 2026-08-31 dummy-texture pass (simple geometric silhouettes,
    // not the real design) - see resource-pack/README.md before treating these as final.
    public static final String BOSSBAR_ICON_DIM_OVERWORLD = "\uEF05";
    public static final String BOSSBAR_ICON_DIM_FARM_WORLD = "\uEF06";
    public static final String BOSSBAR_ICON_DIM_NETHER = "\uEF07";
    public static final String BOSSBAR_ICON_DIM_END = "\uEF08";
    // The hunger games HUD's own icons - placeholder art, same caveat as the dimension icons
    // above.
    public static final String BOSSBAR_ICON_ALIVE = "\uEF09";
    public static final String BOSSBAR_ICON_DEATHS = "\uEF0A";
    public static final String BOSSBAR_ICON_LOOT_POINT = "\uEF0B";
    public static final String BOSSBAR_ICON_BORDER = "\uEF0C";

    // Bearing arrows - U+EF10..U+EF1F, height 10 / ascent 4, sixteen 22.5-degree steps clockwise
    // from straight ahead. Shared by /navigate (SMP), the hunger games "nearest living player"
    // arrow and its "nearest loot point" arrow. Drawn as of the 2026-08-31 dummy-texture pass -
    // a real rotated arrowhead per step, not a rough placeholder; see resource-pack/README.md.
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
