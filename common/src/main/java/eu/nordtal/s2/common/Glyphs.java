package eu.nordtal.s2.common;

/**
 * Code points of the characters the resource pack defines, so a plugin never hardcodes a
 * private-use escape that the pack has since moved.
 *
 * <p><b>The authoritative mapping is {@code resource-pack/README.md}</b>, "Code point allocation"
 * (decided 2026-08-31). It covers all <b>three</b> fonts - {@code minecraft/font/default.json},
 * {@code nordtal/font/board.json} and {@code nordtal/font/bossbar.json} - and this class, those
 * three files and that table are mirrors of one allocation. A change is a change in all of them,
 * in one commit, and {@code ResourcePackTest} fails when they drift.
 *
 * <p>Constants are grouped by font. {@code minecraft:default} is drawn as ordinary text - tab
 * list, chat, nametags, Text Display boards. {@code nordtal:board} is the boards' frame and
 * {@code nordtal:bossbar} the boss bar HUDs, with the vanilla bar made invisible. <b>The three
 * fonts allocate independently</b>, so the same code point means three different glyphs depending
 * on which font a component names - see {@link #FONT_BOSSBAR} for what that costs when a
 * component names none.
 */
public final class Glyphs {

    private Glyphs() {
    }

    /**
     * A code point as a string, so the constants below can be read as hex instead of as
     * surrogate pairs.
     *
     * <p>Every glyph in this pack lives in <b>Supplementary Private Use Area-A</b>,
     * {@code U+F0000..U+FFFFD}, and that is a deliberate move away from the basic plane's
     * {@code U+E000..U+F8FF} (2026-09-04). Two reasons. The glyph plugins everyone else runs -
     * ItemsAdder, Oraxen, Nexo - auto-assign from {@code U+E000} upward, so a pack merged with
     * ours would collide silently: the later provider wins and the glyph renders at another
     * font's ascent, which reads as a positioning bug rather than as a collision. And Minecraft
     * has shipped a {@code unifont_pua} provider since 1.21.6 that nothing currently references -
     * the day something does, an unmapped basic-plane code point stops being visibly broken and
     * starts being quietly wrong, which is the worse half.
     *
     * <p>The mapping was mechanical: {@code 0xE004} became {@code 0xFE004}, one hex digit in
     * front, so every code point kept its place in the allocation table. Written as
     * {@code cp(0x...)} rather than as a {@code "\uDBB8\uDC04"} literal because a surrogate pair
     * in source is unreadable and ungreppable. Nothing outside this class and the three font files
     * ever names a code point - {@code TabListTest} asserts message bundles carry none.</p>
     */
    private static String cp(final int codePoint) {
        return Character.toString(codePoint);
    }

    // === Font keys ===

    /**
     * The font a component has to name for the {@code nordtal:bossbar} code points below to
     * resolve at all.
     *
     * <p><b>This is not decoration, and forgetting it is not a subtle bug.</b> The three fonts
     * allocate independently - the class comment above says so - which means a bossbar code point
     * left in {@code minecraft:default} does not fall back to nothing, it draws whatever
     * {@code default.json} happens to have put at that code point. {@link #BOSSBAR_BG_4} is
     * {@code U+FE004} and so is {@link #TAG_ADMIN}, so a boss bar rendered in the default font
     * draws the admin tag in the middle of its background bar. That is exactly what a real client
     * showed on 2026-09-04, and it is why every component carrying these glyphs names its font.
     */
    public static final String FONT_BOSSBAR = "nordtal:bossbar";

    /** The font the {@code BOARD_*} code points below resolve in - same rule as {@link #FONT_BOSSBAR}. */
    public static final String FONT_BOARD = "nordtal:board";

    // === minecraft:default ===

    // Player badges - U+FE000..U+FE00F
    // Donor star re-uses the retired settler tag's code point (resource-pack/README.md,
    // 2026-08-31) - drawn as of the dummy-texture pass, still placeholder-quality art.
    public static final String BADGE_DONOR_STAR = cp(0xFE000);
    public static final String TAG_ADMIN = cp(0xFE004);

    // Region flags - U+FE010..U+FE01F
    public static final String FLAG_OTHER = cp(0xFE010);
    public static final String FLAG_GERMANY = cp(0xFE011);
    public static final String FLAG_NETHERLANDS = cp(0xFE012);
    public static final String FLAG_UNITED_KINGDOM = cp(0xFE013);
    public static final String FLAG_UNITED_STATES = cp(0xFE014);

    // Logo assets - U+FE020..U+FE02F
    /**
     * The flag glyph for a language, which is the one every surface draws beside a player's name.
     *
     * <p><b>It is the language, not the country</b>, and the mapping is therefore a choice rather
     * than a lookup - there is no flag for "German" any more than there is a language called
     * "Belgian". The pack draws five, and this is how the season's languages land on them:
     *
     * <ul>
     *   <li>{@code de} - Germany</li>
     *   <li>{@code nl} - the Netherlands</li>
     *   <li>{@code en} with country {@code US} - the United States</li>
     *   <li>{@code en} otherwise - the United Kingdom, which is the default English flag because
     *       the server is a European one and {@code Locale.ENGLISH} carries no country at all</li>
     *   <li>anything else - the neutral flag, which exists so an unexpected locale renders as
     *       something rather than as a missing glyph</li>
     * </ul>
     *
     * <p>A null locale is the neutral flag too: it means the account link has not been read yet,
     * which is a state a player can be in for the first second of a session.
     */
    public static String flagFor(final java.util.Locale locale) {
        if (locale == null) {
            return FLAG_OTHER;
        }
        final String language = locale.getLanguage();
        if ("de".equals(language)) {
            return FLAG_GERMANY;
        }
        if ("nl".equals(language)) {
            return FLAG_NETHERLANDS;
        }
        if ("en".equals(language)) {
            return "US".equals(locale.getCountry()) ? FLAG_UNITED_STATES : FLAG_UNITED_KINGDOM;
        }
        return FLAG_OTHER;
    }

    public static final String LOGO_HEIGHT_24 = cp(0xFE020);
    public static final String LOGO_HEIGHT_32 = cp(0xFE021);

    // Prestige crests - U+FE030..U+FE03C, height 9 / ascent 8, tiers 1-13 in order
    // (smp.md#prestige--a-crest-earned-by-time). Placeholder art: a shield outline with
    // a bottom-up fill gauge proportional to the tier, not the real thirteen-tier
    // coat-of-arms design - see resource-pack/README.md before treating these as final.
    public static final String PRESTIGE_CREST_01 = cp(0xFE030);
    public static final String PRESTIGE_CREST_02 = cp(0xFE031);
    public static final String PRESTIGE_CREST_03 = cp(0xFE032);
    public static final String PRESTIGE_CREST_04 = cp(0xFE033);
    public static final String PRESTIGE_CREST_05 = cp(0xFE034);
    public static final String PRESTIGE_CREST_06 = cp(0xFE035);
    public static final String PRESTIGE_CREST_07 = cp(0xFE036);
    public static final String PRESTIGE_CREST_08 = cp(0xFE037);
    public static final String PRESTIGE_CREST_09 = cp(0xFE038);
    public static final String PRESTIGE_CREST_10 = cp(0xFE039);
    public static final String PRESTIGE_CREST_11 = cp(0xFE03A);
    public static final String PRESTIGE_CREST_12 = cp(0xFE03B);
    public static final String PRESTIGE_CREST_13 = cp(0xFE03C);

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
    public static final String BOARD_SPACE_MINUS_1 = cp(0xFF001);
    public static final String BOARD_SPACE_MINUS_2 = cp(0xFF002);
    public static final String BOARD_SPACE_MINUS_4 = cp(0xFF004);
    public static final String BOARD_SPACE_MINUS_8 = cp(0xFF008);
    public static final String BOARD_SPACE_MINUS_16 = cp(0xFF016);
    public static final String BOARD_SPACE_MINUS_32 = cp(0xFF032);
    public static final String BOARD_SPACE_MINUS_64 = cp(0xFF064);
    public static final String BOARD_SPACE_MINUS_128 = cp(0xFF128);

    // Corners - U+FE040..U+FE043, height 9 / ascent 8
    public static final String BOARD_CORNER_TOP_LEFT = cp(0xFE040);
    public static final String BOARD_CORNER_TOP_RIGHT = cp(0xFE041);
    public static final String BOARD_CORNER_BOTTOM_LEFT = cp(0xFE042);
    public static final String BOARD_CORNER_BOTTOM_RIGHT = cp(0xFE043);

    // Horizontal edge, tiled in powers of two - U+FE044..U+FE04B
    public static final String BOARD_EDGE_H_1 = cp(0xFE044);
    public static final String BOARD_EDGE_H_2 = cp(0xFE045);
    public static final String BOARD_EDGE_H_4 = cp(0xFE046);
    public static final String BOARD_EDGE_H_8 = cp(0xFE047);
    public static final String BOARD_EDGE_H_16 = cp(0xFE048);
    public static final String BOARD_EDGE_H_32 = cp(0xFE049);
    public static final String BOARD_EDGE_H_64 = cp(0xFE04A);
    public static final String BOARD_EDGE_H_128 = cp(0xFE04B);

    // Vertical edge - U+FE04C..U+FE04D
    public static final String BOARD_EDGE_V_LEFT = cp(0xFE04C);
    public static final String BOARD_EDGE_V_RIGHT = cp(0xFE04D);

    // Divider, tiled in powers of two like the horizontal edge - U+FE04E..U+FE055.
    // Allocated separately from the outer edge (2026-08-31) so an interior rule can
    // differ from the border, even though the current placeholder art draws both
    // identically - see resource-pack/README.md.
    public static final String BOARD_DIVIDER_1 = cp(0xFE04E);
    public static final String BOARD_DIVIDER_2 = cp(0xFE04F);
    public static final String BOARD_DIVIDER_4 = cp(0xFE050);
    public static final String BOARD_DIVIDER_8 = cp(0xFE051);
    public static final String BOARD_DIVIDER_16 = cp(0xFE052);
    public static final String BOARD_DIVIDER_32 = cp(0xFE053);
    public static final String BOARD_DIVIDER_64 = cp(0xFE054);
    public static final String BOARD_DIVIDER_128 = cp(0xFE055);
    // U+FE056..U+FE05F reserved for this font's own future growth.

    // === nordtal:bossbar ===

    // Space advances - negative, U+FF001..U+FF128
    public static final String BOSSBAR_SPACE_MINUS_1 = cp(0xFF001);
    public static final String BOSSBAR_SPACE_MINUS_2 = cp(0xFF002);
    public static final String BOSSBAR_SPACE_MINUS_4 = cp(0xFF004);
    public static final String BOSSBAR_SPACE_MINUS_8 = cp(0xFF008);
    public static final String BOSSBAR_SPACE_MINUS_16 = cp(0xFF016);
    public static final String BOSSBAR_SPACE_MINUS_32 = cp(0xFF032);
    public static final String BOSSBAR_SPACE_MINUS_64 = cp(0xFF064);
    public static final String BOSSBAR_SPACE_MINUS_128 = cp(0xFF128);

    // Space advances - positive, U+FFF01..U+FFF32 plus the ordinary space.
    // These sat on real fullwidth punctuation (U+FF01..U+FF32) until 2026-09-04, which
    // resource-pack/README.md carried as a standing finding: a HUD line containing a fullwidth
    // exclamation mark would have been silently eaten. The move to SPUA-A closed it.
    public static final String BOSSBAR_SPACE_PLUS_1 = cp(0xFFF01);
    public static final String BOSSBAR_SPACE_PLUS_2 = cp(0xFFF02);
    public static final String BOSSBAR_SPACE_PLUS_3 = " ";
    public static final String BOSSBAR_SPACE_PLUS_4 = cp(0xFFF04);
    public static final String BOSSBAR_SPACE_PLUS_8 = cp(0xFFF08);
    public static final String BOSSBAR_SPACE_PLUS_16 = cp(0xFFF16);
    public static final String BOSSBAR_SPACE_PLUS_32 = cp(0xFFF32);

    // Bar background segments - U+FE000..U+FE128, height 14 / ascent 6
    public static final String BOSSBAR_BG_END = cp(0xFE000);
    public static final String BOSSBAR_BG_1 = cp(0xFE001);
    public static final String BOSSBAR_BG_2 = cp(0xFE002);
    public static final String BOSSBAR_BG_4 = cp(0xFE004);
    public static final String BOSSBAR_BG_8 = cp(0xFE008);
    public static final String BOSSBAR_BG_16 = cp(0xFE016);
    public static final String BOSSBAR_BG_32 = cp(0xFE032);
    public static final String BOSSBAR_BG_64 = cp(0xFE064);
    public static final String BOSSBAR_BG_128 = cp(0xFE128);

    // Status icons - U+FEF00..U+FEF0F, height 10 / ascent 4
    public static final String BOSSBAR_ICON_COMPASS = cp(0xFEF00);
    // fblue/fgreen/fred/fwhite are one pennant-on-a-pole sprite in four colours - season 1's land
    // indicator, picked by the player's position: blue inside a player's preserved area, green on
    // permanent land, red in a reset zone, white in the server-protected spawn. Nothing in season 2
    // draws them yet; the meanings are recorded in resource-pack/README.md.
    public static final String BOSSBAR_ICON_FBLUE = cp(0xFEF01);
    public static final String BOSSBAR_ICON_FGREEN = cp(0xFEF02);
    public static final String BOSSBAR_ICON_FRED = cp(0xFEF03);
    public static final String BOSSBAR_ICON_FWHITE = cp(0xFEF04);
    // Dimension icons - U+FEF05..U+FEF08, one per world the SMP/hunger games HUDs name.
    // Placeholder art from the 2026-08-31 dummy-texture pass (simple geometric silhouettes,
    // not the real design) - see resource-pack/README.md before treating these as final.
    public static final String BOSSBAR_ICON_DIM_OVERWORLD = cp(0xFEF05);
    public static final String BOSSBAR_ICON_DIM_FARM_WORLD = cp(0xFEF06);
    public static final String BOSSBAR_ICON_DIM_NETHER = cp(0xFEF07);
    public static final String BOSSBAR_ICON_DIM_END = cp(0xFEF08);
    // The hunger games HUD's own icons - placeholder art, same caveat as the dimension icons
    // above.
    public static final String BOSSBAR_ICON_ALIVE = cp(0xFEF09);
    public static final String BOSSBAR_ICON_DEATHS = cp(0xFEF0A);
    public static final String BOSSBAR_ICON_LOOT_POINT = cp(0xFEF0B);
    public static final String BOSSBAR_ICON_BORDER = cp(0xFEF0C);

    // Bearing arrows - U+FEF10..U+FEF1F, height 10 / ascent 4, sixteen 22.5-degree steps clockwise
    // from straight ahead. Shared by /navigate (SMP), the hunger games "nearest living player"
    // arrow and its "nearest loot point" arrow. Drawn as of the 2026-08-31 dummy-texture pass -
    // a real rotated arrowhead per step, not a rough placeholder; see resource-pack/README.md.
    public static final String BOSSBAR_ARROW_000_0 = cp(0xFEF10);
    public static final String BOSSBAR_ARROW_022_5 = cp(0xFEF11);
    public static final String BOSSBAR_ARROW_045_0 = cp(0xFEF12);
    public static final String BOSSBAR_ARROW_067_5 = cp(0xFEF13);
    public static final String BOSSBAR_ARROW_090_0 = cp(0xFEF14);
    public static final String BOSSBAR_ARROW_112_5 = cp(0xFEF15);
    public static final String BOSSBAR_ARROW_135_0 = cp(0xFEF16);
    public static final String BOSSBAR_ARROW_157_5 = cp(0xFEF17);
    public static final String BOSSBAR_ARROW_180_0 = cp(0xFEF18);
    public static final String BOSSBAR_ARROW_202_5 = cp(0xFEF19);
    public static final String BOSSBAR_ARROW_225_0 = cp(0xFEF1A);
    public static final String BOSSBAR_ARROW_247_5 = cp(0xFEF1B);
    public static final String BOSSBAR_ARROW_270_0 = cp(0xFEF1C);
    public static final String BOSSBAR_ARROW_292_5 = cp(0xFEF1D);
    public static final String BOSSBAR_ARROW_315_0 = cp(0xFEF1E);
    public static final String BOSSBAR_ARROW_337_5 = cp(0xFEF1F);

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
