package eu.nordtal.s2.common;

/**
 * Code points of the characters the resource pack defines, so a plugin never hardcodes a
 * private-use escape that the pack has since moved.
 *
 * <p><b>The authoritative mapping is {@code resource-pack/README.md}</b>, "Code point allocation"
 * (decided 2026-08-31). It covers all <b>four</b> fonts - {@code minecraft/font/default.json},
 * {@code nordtal/font/board.json}, {@code nordtal/font/bossbar.json} and
 * {@code nordtal/font/gui.json} - and this class, those four files and that table are mirrors of
 * one allocation. A change is a change in all of them, in one commit, and
 * {@code ResourcePackTest} fails when they drift.
 *
 * <p>Constants are grouped by font. {@code minecraft:default} is drawn as ordinary text - tab
 * list, chat, nametags, Text Display boards. {@code nordtal:board} is the boards' frame,
 * {@code nordtal:bossbar} the boss bar HUDs with the vanilla bar made invisible, and
 * {@code nordtal:gui} the menu panels a chest inventory's title carries. <b>The four fonts
 * allocate independently</b>, so the same code point means four different glyphs depending on
 * which font a component names - see {@link #FONT_BOSSBAR} for what that costs when a component
 * names none.
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

    /** The font the {@code GUI_*} code points below resolve in - same rule as {@link #FONT_BOSSBAR}. */
    public static final String FONT_GUI = "nordtal:gui";

    /**
     * The six row fonts, one per chest row, in which every {@code GUI_ROW_*} code point and all
     * readable row text resolves.
     *
     * <h2>Why there are six of them</h2>
     * A glyph's only vertical control is its font's {@code ascent}, and a list menu wants the same
     * picture - a pill, an icon, a line of text - on any of the six rows. Carrying the row in the
     * <em>code point</em> costs one code point per (picture, row) and is impossible for text, whose
     * code points are not ours to choose. Carrying it in the <em>font</em> costs one font per row
     * and nothing per picture: {@code nordtal:gui_r2} is "everything, drawn on chest row 2". All
     * six declare exactly the same characters, at three ascents - furniture, icon and text - which
     * {@code resource-pack/tools/generate_gui_rows.py} derives and writes.
     *
     * <p>Indexed by row, so {@code FONT_GUI_ROWS[0]} is the top chest row. A component drawn on a
     * row names one of these instead of {@link #FONT_GUI}; naming the wrong one draws the right
     * picture one or more rows out, which looks like a layout bug and not like a font mistake.</p>
     */
    public static final String[] FONT_GUI_ROWS = {
            "nordtal:gui_r0", "nordtal:gui_r1", "nordtal:gui_r2",
            "nordtal:gui_r3", "nordtal:gui_r4", "nordtal:gui_r5",
    };

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

    // System-line icons - U+FE080..U+FE085, height 7 / ascent 7, minecraft:default.
    //
    // The six markers in front of the lines a player reads all day: chat, join, leave, death,
    // advancement, and the announcements the whole server is told (docs/presentation.md section 5).
    // Allocated at 0xFE080 rather than at the next free slot after the crests, so that the number
    // alone is unambiguous across all four fonts: nordtal:board already uses 0xFE040..0xFE055 and
    // nordtal:gui reserves through 0xFE07F, and a code point that means two different pictures in
    // two fonts is what put the admin nametag inside the SMP's boss bar for four days.
    //
    // THE ART IS WHITE, and that is load-bearing rather than a taste in colours: Minecraft
    // multiplies a glyph by the component's text colour, so white can be tinted to whatever the
    // message bundle asks for and black cannot be tinted lighter. The board frame was drawn black
    // and was invisible on the surface it hangs on; these six sit in a chat line whose colour the
    // operator can change without a release.

    /** The rule between a player's name and what they said. Not a character - 3 px wide. */
    public static final String SEPARATOR = cp(0xFE080);

    public static final String ICON_JOIN = cp(0xFE081);
    public static final String ICON_LEAVE = cp(0xFE082);
    public static final String ICON_DEATH = cp(0xFE083);
    public static final String ICON_ADVANCEMENT = cp(0xFE084);

    /** In front of a line the whole server is told: a milestone, an objective, a farm reset. */
    public static final String ICON_ANNOUNCE = cp(0xFE085);

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

    // Space advances - positive, added 2026-09-04 with the frame. The same six the bossbar
    // font declares, and for the same reason a frame needs them: the right-hand vertical
    // edge has to be drawn at a known x before the content is, because the content's own
    // width is what nothing here can measure. There is no +64 or +128 - the naming rule puts
    // the decimal advance in the low digits, and "FFF" + "128" is six hex digits, past the
    // end of SPUA-A. Wider shifts repeat the +32, which is what BoardFrame does.
    public static final String BOARD_SPACE_PLUS_1 = cp(0xFFF01);
    public static final String BOARD_SPACE_PLUS_2 = cp(0xFFF02);
    public static final String BOARD_SPACE_PLUS_4 = cp(0xFFF04);
    public static final String BOARD_SPACE_PLUS_8 = cp(0xFFF08);
    public static final String BOARD_SPACE_PLUS_16 = cp(0xFFF16);
    public static final String BOARD_SPACE_PLUS_32 = cp(0xFFF32);

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

    // Bar background segments - U+FE000..U+FE128, height 14 / ascent 6. Since 2026-09-05 a HUD
    // line is one rounded PILL per piece of information: START, a body composed from the
    // power-of-two segments, END. Every segment is exactly as wide as its name and the client
    // advances a bitmap glyph by its width plus one, so the composer (BossBarWidth) steps back a
    // pixel after each; the START cap sits at U+FE0FF because U+FE000 was END before START existed.
    public static final String BOSSBAR_BG_END = cp(0xFE000);
    public static final String BOSSBAR_BG_START = cp(0xFE0FF);
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
    // Dimension icons - U+FEF05..U+FEF08, one per world the SMP/hunger games HUDs name. Real art
    // since 2026-09-05 (resource-pack/tools/generate_hud.py): 10 x 10, a dark outline and one
    // leading colour each - green Nordtal, gold farm world, red Nether, violet End.
    public static final String BOSSBAR_ICON_DIM_OVERWORLD = cp(0xFEF05);
    public static final String BOSSBAR_ICON_DIM_FARM_WORLD = cp(0xFEF06);
    public static final String BOSSBAR_ICON_DIM_NETHER = cp(0xFEF07);
    public static final String BOSSBAR_ICON_DIM_END = cp(0xFEF08);
    // The hunger games HUD's own icons - a heart, a skull, a chest, a dashed border - drawn by
    // the same tool on the same day.
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

    // === nordtal:gui ===
    //
    // The menu panels - docs/presentation.md#2-menu-panels. A menu on this server is an ordinary
    // chest inventory whose TITLE carries a bitmap glyph big enough to cover the whole window; the
    // glyph rises out of the title's baseline on a large positive ascent and the slots draw on top
    // of it, because the client renders labels after the background. That is the whole technique,
    // and it is why there is one glyph per chest size rather than one panel: a window is
    // 114 + 18*rows pixels tall, so a panel drawn for six rows is 90 px too tall for one row.

    // Space advances - negative, U+FF001..U+FF128. The same code points board.json and
    // bossbar.json use, which is not a collision: the fonts allocate independently, and a menu
    // title composed in nordtal:gui can only ever reach nordtal:gui's own table.
    //
    // POSITIVE advances arrived on 2026-09-09, at U+FF801..U+FF928 - the same decimal-digit
    // convention one bit higher, so +16 is U+FF816 next to -16 at U+FF016. This paragraph used to
    // say there were deliberately none, because nothing in a menu title ever moved right: a panel
    // is drawn from the left edge and the readable title walks back behind it. A LIST row does
    // move right. Its pill has to be drawn before the text on top of it, and its icon before the
    // name beside it, so the composition is left-to-right within a row and the cursor has to be
    // able to go back out to the next row's start. Without them a renderer can only ever lay the
    // topmost thing down first, which for a pill means painting it over its own label.
    public static final String GUI_SPACE_MINUS_1 = cp(0xFF001);
    public static final String GUI_SPACE_MINUS_2 = cp(0xFF002);
    public static final String GUI_SPACE_MINUS_4 = cp(0xFF004);
    public static final String GUI_SPACE_MINUS_8 = cp(0xFF008);
    public static final String GUI_SPACE_MINUS_16 = cp(0xFF016);
    public static final String GUI_SPACE_MINUS_32 = cp(0xFF032);
    public static final String GUI_SPACE_MINUS_64 = cp(0xFF064);
    public static final String GUI_SPACE_MINUS_128 = cp(0xFF128);

    public static final String GUI_SPACE_PLUS_1 = cp(0xFF801);
    public static final String GUI_SPACE_PLUS_2 = cp(0xFF802);
    public static final String GUI_SPACE_PLUS_4 = cp(0xFF804);
    public static final String GUI_SPACE_PLUS_8 = cp(0xFF808);
    public static final String GUI_SPACE_PLUS_16 = cp(0xFF816);
    public static final String GUI_SPACE_PLUS_32 = cp(0xFF832);
    public static final String GUI_SPACE_PLUS_64 = cp(0xFF864);
    public static final String GUI_SPACE_PLUS_128 = cp(0xFF928);

    // Panels - U+FE060..U+FE065, one per chest size, ascent 13 and height = the window's own
    // pixel height so each renders 1:1.
    public static final String GUI_PANEL_1 = cp(0xFE060);
    public static final String GUI_PANEL_2 = cp(0xFE061);
    public static final String GUI_PANEL_3 = cp(0xFE062);
    public static final String GUI_PANEL_4 = cp(0xFE063);
    public static final String GUI_PANEL_5 = cp(0xFE064);
    public static final String GUI_PANEL_6 = cp(0xFE065);

    /** The six panels, one row first, for {@code rows - 1} indexing. */
    public static final String[] GUI_PANELS = {
            GUI_PANEL_1, GUI_PANEL_2, GUI_PANEL_3, GUI_PANEL_4, GUI_PANEL_5, GUI_PANEL_6,
    };

    // The balloon's travel panel and its two state overlays - U+FE066..U+FE06A, 2026-09-05.
    // The panel is a 6-row window with the four world cards baked in, because all four are always
    // shown in fixed places; what varies is a card's STATE, and each state is one tile-sized glyph
    // declared TWICE - once per tile row, at the ascent that lands it on that row - and drawn over
    // the panel by walking the cursor back to the card's x. See MenuTitle.Canvas for the
    // composition and docs/presentation.md#2-menu-panels for the technique. The tile geometry
    // (68 x 50 at x 9/99, y 19/73) lives in resource-pack/tools/generate_gui_panels.py and is
    // read back off the PNGs by MenuTitleTest; nothing here restates it.
    public static final String GUI_TRAVEL_PANEL = cp(0xFE066);
    public static final String GUI_TRAVEL_LOCKED_TOP = cp(0xFE067);
    public static final String GUI_TRAVEL_LOCKED_BOTTOM = cp(0xFE068);
    public static final String GUI_TRAVEL_HERE_TOP = cp(0xFE069);
    public static final String GUI_TRAVEL_HERE_BOTTOM = cp(0xFE06A);

    // The same six panels WITHOUT the container's own slot recesses - U+FE06B..U+FE070, 2026-09-09.
    // A list menu draws a pill across a whole row, and a recess under it shows above, below and on
    // both sides of every pill. The player's own three rows and the hotbar keep their recesses in
    // both variants, because those slots hold real items whatever the menu above them is.
    public static final String GUI_PANEL_PLAIN_1 = cp(0xFE06B);
    public static final String GUI_PANEL_PLAIN_2 = cp(0xFE06C);
    public static final String GUI_PANEL_PLAIN_3 = cp(0xFE06D);
    public static final String GUI_PANEL_PLAIN_4 = cp(0xFE06E);
    public static final String GUI_PANEL_PLAIN_5 = cp(0xFE06F);
    public static final String GUI_PANEL_PLAIN_6 = cp(0xFE070);

    /** The six recess-free panels, one row first, for {@code rows - 1} indexing. */
    public static final String[] GUI_PANELS_PLAIN = {
            GUI_PANEL_PLAIN_1, GUI_PANEL_PLAIN_2, GUI_PANEL_PLAIN_3,
            GUI_PANEL_PLAIN_4, GUI_PANEL_PLAIN_5, GUI_PANEL_PLAIN_6,
    };
    // U+FE071..U+FE07F is this font's room to grow.

    // === nordtal:gui_r0 .. nordtal:gui_r5 ===
    //
    // The row furniture - U+FE100..U+FE11F, the block reserved for it on 2026-09-08. Every one of
    // these is declared in ALL SIX row fonts at that row's ascent, so the code point says WHAT is
    // drawn and FONT_GUI_ROWS[row] says WHERE. A GUI_ROW_* constant in nordtal:gui itself draws
    // nothing at all: that font declares none of them.
    //
    // Their advances are not written down here. They are a property of the PNGs, exported by
    // resource-pack/tools/generate_gui_rows.py into :common's own resources and read by MenuFont -
    // the same arrangement BossBarAdvances has, and for the same reason: a redrawn glyph whose
    // rightmost column moved would otherwise be a row one pixel out on a client and nowhere else.

    /** A list entry's plate, 158 px wide and 14 tall - the width of the slot area inset 2. */
    public static final String GUI_ROW_PILL = cp(0xFE100);

    /** The white 2 px frame that marks the one entry a player is currently navigating to. */
    public static final String GUI_ROW_FRAME = cp(0xFE101);

    /** A 52 px button plate in the refusing style - {@code /navigate}'s "stop". */
    public static final String GUI_ROW_BUTTON_WIDE = cp(0xFE102);

    /** A 14 px square button plate, one slot cell inset 2. */
    public static final String GUI_ROW_BUTTON_SMALL = cp(0xFE103);

    /** The same square plate, greyed - a page button with no page on the other side of it. */
    public static final String GUI_ROW_BUTTON_SMALL_OFF = cp(0xFE104);

    /**
     * The same 158 px plate in a darker grey: a <em>heading</em> row rather than an entry.
     *
     * <p>The objective menu's top row names the milestone the cards below belong to. Drawn in the
     * entry grey it would read as a fifth thing to click, which is the one thing a heading must
     * not do.
     */
    public static final String GUI_ROW_PILL_DARK = cp(0xFE105);

    /**
     * A 50 px button plate in the affirming style: gold, the brand's own.
     *
     * <p>The only plate in the pack that is not neutral grey or refusing red, and it is one because
     * it is the only button in these menus whose click cannot be undone by clicking again - the
     * hand-in's confirm.
     */
    public static final String GUI_ROW_BUTTON_CONFIRM = cp(0xFE106);

    // Row icons - U+FE110..U+FE11A, 8 x 8, drawn white so a component's colour can tint them.
    public static final String GUI_ROW_ICON_SPAWN = cp(0xFE110);
    public static final String GUI_ROW_ICON_DEATH = cp(0xFE111);
    public static final String GUI_ROW_ICON_POI = cp(0xFE112);
    public static final String GUI_ROW_ICON_STOP = cp(0xFE113);
    public static final String GUI_ROW_ICON_PREV = cp(0xFE114);
    public static final String GUI_ROW_ICON_NEXT = cp(0xFE115);

    // The objective card's four states. The icon IS the state on that card - a hand you can hand
    // something to, a pickaxe that counts itself, a medal earned elsewhere, a tick when it is done -
    // so these four are never drawn beside one another and one of them always is.
    public static final String GUI_ROW_ICON_HAND_IN = cp(0xFE116);
    public static final String GUI_ROW_ICON_STATISTIC = cp(0xFE117);
    public static final String GUI_ROW_ICON_ADVANCEMENT = cp(0xFE118);
    public static final String GUI_ROW_ICON_DONE = cp(0xFE119);

    /** An experience orb - what the objective menu's share line is about. */
    public static final String GUI_ROW_ICON_AURA = cp(0xFE11A);
    // U+FE11B..U+FE1FF is this block's room to grow.

    // === nordtal:gui, menu surfaces - U+FE200..U+FE2FF (2026-09-09) ===
    //
    // A menu whose art spans MORE THAN ONE chest row cannot be a row glyph: a row font's whole
    // purpose is that it carries one ascent per layer per row, and a card two rows tall has no row.
    // So these live in nordtal:gui with an ascent apiece, exactly as the balloon's overlays do -
    // and, exactly as there, a picture that can land on two different rows is declared twice.
    //
    // The block is its own because U+FE060..U+FE07F was full. Nothing forced a NEW block - fonts
    // allocate independently and U+FE090 was free in this one - but the numbers in this repository
    // are kept globally distinct so that a code point read in a log or a screenshot names one thing,
    // and U+FE080 is the system-line icons in minecraft:default.

    /** The objective card, 68 x 32, on the upper of the two card rows (top y 37). */
    public static final String GUI_CARD_TOP = cp(0xFE200);

    /** The same card on the lower card row (top y 73). */
    public static final String GUI_CARD_BOTTOM = cp(0xFE201);

    /** The green wash over a finished card, upper row. */
    public static final String GUI_CARD_DONE_TOP = cp(0xFE202);

    /** The same wash, lower row. */
    public static final String GUI_CARD_DONE_BOTTOM = cp(0xFE203);

    /**
     * The progress bar's fill, in powers of two, for the upper card row.
     *
     * <p>Indexed the way {@link #GUI_SPACE_MINUS_1} and friends are used: any fill from 0 to 60
     * pixels is at most four of these laid side by side, because 60 is 32 + 16 + 8 + 4. The bar's
     * <em>track</em> is baked into the card, so an empty bar costs nothing at all.
     */
    public static final String[] GUI_BAR_FILL_TOP = {
            cp(0xFE210), cp(0xFE211), cp(0xFE212), cp(0xFE213), cp(0xFE214), cp(0xFE215),
    };

    /**
     * The hand-in screen's tray: one sunken surface 162 x 54 over three chest rows.
     *
     * <p>One surface and not a recess per slot, which is design {@code H2} and is the deliberate
     * opposite of the grave - see {@code HandInPanel}'s own comment for why the two must not be
     * made the same.
     */
    public static final String GUI_HANDIN_TRAY = cp(0xFE204);

    /** The same six, for the lower card row. */
    public static final String[] GUI_BAR_FILL_BOTTOM = {
            cp(0xFE218), cp(0xFE219), cp(0xFE21A), cp(0xFE21B), cp(0xFE21C), cp(0xFE21D),
    };

    /** The widths {@link #GUI_BAR_FILL_TOP} draws, in the same order. */
    public static final int[] GUI_BAR_FILL_WIDTHS = {1, 2, 4, 8, 16, 32};
}
