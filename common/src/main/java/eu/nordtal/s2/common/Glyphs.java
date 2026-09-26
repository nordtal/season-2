package eu.nordtal.s2.common;

import java.util.List;

/**
 * Code points of the characters the resource pack defines, grouped by font.
 *
 * This class and the pack's font files mirror one allocation, and {@code ResourcePackTest} fails
 * when they drift. The fonts allocate independently, so a code point means a different glyph in
 * each font a component names - see {@link #FONT_BOSSBAR}.
 */
public final class Glyphs {

    private Glyphs() {}

    /**
     * Returns a code point as a string, so the constants below read as hex rather than surrogates.
     *
     * Every glyph lives in Supplementary Private Use Area-A, {@code U+F0000..U+FFFFD}: glyph plugins
     * auto-assign from {@code U+E000} upward, and Minecraft's {@code unifont_pua} covers that range.
     */
    private static String cp(final int codePoint) {
        return Character.toString(codePoint);
    }

    // === Font keys ===

    /**
     * The font a component names for the {@code nordtal:bossbar} code points below to resolve.
     *
     * A bossbar code point left in {@code minecraft:default} draws whatever that font has there
     * ({@link #BOSSBAR_BG_4} and {@link #TAG_ADMIN} are both {@code U+FE004}).
     */
    public static final String FONT_BOSSBAR = "nordtal:bossbar";

    /** The font the {@code BOARD_*} code points below resolve in - same rule as {@link #FONT_BOSSBAR}. */
    public static final String FONT_BOARD = "nordtal:board";

    /** The font the {@code GUI_*} code points below resolve in - same rule as {@link #FONT_BOSSBAR}. */
    public static final String FONT_GUI = "nordtal:gui";

    /**
     * The six row fonts, indexed by chest row from the top, in which every {@code GUI_ROW_*} resolves.
     *
     * A glyph's only vertical control is its font's {@code ascent}, so the row is carried in the font:
     * all six declare the same characters at that row's ascents.
     */
    public static final List<String> FONT_GUI_ROWS = List.of(
            "nordtal:gui_r0", "nordtal:gui_r1", "nordtal:gui_r2", "nordtal:gui_r3", "nordtal:gui_r4", "nordtal:gui_r5");

    // === minecraft:default ===

    // Player badges - U+FE000..U+FE00F
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
     * It maps a language, not a country, so the mapping is a choice: {@code en} without a
     * country falls to the United Kingdom because the server is a European one, and anything
     * unmapped (a null locale included) gets the neutral flag rather than a missing glyph.
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

    // Prestige crests - U+FE030..U+FE03C, height 9 / ascent 8, tiers 1-13 in order.
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

    /** All thirteen prestige crests, tier 1 first, for {@code tier - 1} indexing. */
    public static final List<String> PRESTIGE_CRESTS = List.of(
            PRESTIGE_CREST_01,
            PRESTIGE_CREST_02,
            PRESTIGE_CREST_03,
            PRESTIGE_CREST_04,
            PRESTIGE_CREST_05,
            PRESTIGE_CREST_06,
            PRESTIGE_CREST_07,
            PRESTIGE_CREST_08,
            PRESTIGE_CREST_09,
            PRESTIGE_CREST_10,
            PRESTIGE_CREST_11,
            PRESTIGE_CREST_12,
            PRESTIGE_CREST_13);

    // System-line icons, U+FE080..U+FE085: white, because a glyph is multiplied by the text colour.

    /** The rule between a player's name and what they said. Not a character - 3 px wide. */
    public static final String SEPARATOR = cp(0xFE080);

    public static final String ICON_JOIN = cp(0xFE081);
    public static final String ICON_LEAVE = cp(0xFE082);
    public static final String ICON_DEATH = cp(0xFE083);
    public static final String ICON_ADVANCEMENT = cp(0xFE084);

    /** In front of a line the whole server is told: a milestone, an objective, a restart. */
    public static final String ICON_ANNOUNCE = cp(0xFE085);

    /**
     * Returns the glyphs of {@code minecraft:default} by the name a message writes as {@code <glyph:name>}.
     *
     * Read from {@code glyph-names.txt} beside this class, which the steward-ui build reads as well.
     */
    public static java.util.Map<String, String> named() {
        return Named.TABLE;
    }

    private static final class Named {
        private static final java.util.Map<String, String> TABLE = load();

        private static java.util.Map<String, String> load() {
            final java.util.Map<String, String> table = new java.util.LinkedHashMap<>();
            try (var in = Glyphs.class.getResourceAsStream("glyph-names.txt")) {
                if (in == null) {
                    throw new IllegalStateException("glyph-names.txt is missing beside Glyphs");
                }
                for (final String line :
                        new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n", -1)) {
                    final String trimmed = line.strip();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    final String[] parts = trimmed.split("\\s+", -1);
                    table.put(parts[0], cp(Integer.parseInt(parts[1], 16)));
                }
            } catch (final java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return java.util.Collections.unmodifiableMap(table);
        }
    }

    // nordtal:board: its own font, because closing the tiles' 1 px gap needs a negative-space provider.

    // Negative space advances; the fonts allocate independently, so these code points do not collide.
    public static final String BOARD_SPACE_MINUS_1 = cp(0xFF001);
    public static final String BOARD_SPACE_MINUS_2 = cp(0xFF002);
    public static final String BOARD_SPACE_MINUS_4 = cp(0xFF004);
    public static final String BOARD_SPACE_MINUS_8 = cp(0xFF008);
    public static final String BOARD_SPACE_MINUS_16 = cp(0xFF016);
    public static final String BOARD_SPACE_MINUS_32 = cp(0xFF032);
    public static final String BOARD_SPACE_MINUS_64 = cp(0xFF064);
    public static final String BOARD_SPACE_MINUS_128 = cp(0xFF128);

    // Positive space advances; no +64 or +128, because "FFF" + "128" is past the end of SPUA-A.
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

    // Divider, U+FE04E..U+FE055, apart from the outer edge so an interior rule can differ from it.
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
    public static final String BOSSBAR_SPACE_PLUS_1 = cp(0xFFF01);
    public static final String BOSSBAR_SPACE_PLUS_2 = cp(0xFFF02);
    public static final String BOSSBAR_SPACE_PLUS_3 = " ";
    public static final String BOSSBAR_SPACE_PLUS_4 = cp(0xFFF04);
    public static final String BOSSBAR_SPACE_PLUS_8 = cp(0xFFF08);
    public static final String BOSSBAR_SPACE_PLUS_16 = cp(0xFFF16);
    public static final String BOSSBAR_SPACE_PLUS_32 = cp(0xFFF32);

    // Bar background segments; the client adds 1 px after each, which BossBarWidth steps back over.
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
    // Land-indicator pennants: blue preserved, green permanent, red reset zone, white spawn.
    public static final String BOSSBAR_ICON_FBLUE = cp(0xFEF01);
    public static final String BOSSBAR_ICON_FGREEN = cp(0xFEF02);
    public static final String BOSSBAR_ICON_FRED = cp(0xFEF03);
    public static final String BOSSBAR_ICON_FWHITE = cp(0xFEF04);
    // Dimension icons; U+FEF06 is a deliberate gap, because a code point is an address.
    public static final String BOSSBAR_ICON_DIM_OVERWORLD = cp(0xFEF05);
    public static final String BOSSBAR_ICON_DIM_NETHER = cp(0xFEF07);
    public static final String BOSSBAR_ICON_DIM_END = cp(0xFEF08);
    // The hunger games HUD's own icons - a heart, a skull, a chest, a dashed border.
    public static final String BOSSBAR_ICON_ALIVE = cp(0xFEF09);
    public static final String BOSSBAR_ICON_DEATHS = cp(0xFEF0A);
    public static final String BOSSBAR_ICON_LOOT_POINT = cp(0xFEF0B);
    public static final String BOSSBAR_ICON_BORDER = cp(0xFEF0C);

    // Bearing arrows, sixteen 22.5-degree steps clockwise from straight ahead.
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
     * All sixteen bearing arrows, clockwise from {@link #BOSSBAR_ARROW_000_0}.
     *
     * A bearing in degrees indexes it with {@code Math.floorMod(Math.round(bearing / 22.5), 16)}.
     */
    public static final List<String> BOSSBAR_ARROWS = List.of(
            BOSSBAR_ARROW_000_0,
            BOSSBAR_ARROW_022_5,
            BOSSBAR_ARROW_045_0,
            BOSSBAR_ARROW_067_5,
            BOSSBAR_ARROW_090_0,
            BOSSBAR_ARROW_112_5,
            BOSSBAR_ARROW_135_0,
            BOSSBAR_ARROW_157_5,
            BOSSBAR_ARROW_180_0,
            BOSSBAR_ARROW_202_5,
            BOSSBAR_ARROW_225_0,
            BOSSBAR_ARROW_247_5,
            BOSSBAR_ARROW_270_0,
            BOSSBAR_ARROW_292_5,
            BOSSBAR_ARROW_315_0,
            BOSSBAR_ARROW_337_5);

    // nordtal:gui: a menu is a chest whose title glyph covers the window, one glyph per chest size.

    // Space advances: negative at U+FF001..U+FF128, positive at U+FF801..U+FF928.
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

    // Panels, one per chest size, each as tall as its window so it renders 1:1.
    public static final String GUI_PANEL_1 = cp(0xFE060);
    public static final String GUI_PANEL_2 = cp(0xFE061);
    public static final String GUI_PANEL_3 = cp(0xFE062);
    public static final String GUI_PANEL_4 = cp(0xFE063);
    public static final String GUI_PANEL_5 = cp(0xFE064);
    public static final String GUI_PANEL_6 = cp(0xFE065);

    /** The six panels, one row first, for {@code rows - 1} indexing. */
    public static final List<String> GUI_PANELS =
            List.of(GUI_PANEL_1, GUI_PANEL_2, GUI_PANEL_3, GUI_PANEL_4, GUI_PANEL_5, GUI_PANEL_6);

    // Travel panel and its card-state overlays, each overlay declared once per tile row.
    public static final String GUI_TRAVEL_PANEL = cp(0xFE066);
    public static final String GUI_TRAVEL_LOCKED_TOP = cp(0xFE067);
    public static final String GUI_TRAVEL_LOCKED_BOTTOM = cp(0xFE068);
    public static final String GUI_TRAVEL_HERE_TOP = cp(0xFE069);
    public static final String GUI_TRAVEL_HERE_BOTTOM = cp(0xFE06A);

    // The same panels without slot recesses, for list menus whose pills span a whole row.
    public static final String GUI_PANEL_PLAIN_1 = cp(0xFE06B);
    public static final String GUI_PANEL_PLAIN_2 = cp(0xFE06C);
    public static final String GUI_PANEL_PLAIN_3 = cp(0xFE06D);
    public static final String GUI_PANEL_PLAIN_4 = cp(0xFE06E);
    public static final String GUI_PANEL_PLAIN_5 = cp(0xFE06F);
    public static final String GUI_PANEL_PLAIN_6 = cp(0xFE070);

    /** The six recess-free panels, one row first, for {@code rows - 1} indexing. */
    public static final List<String> GUI_PANELS_PLAIN = List.of(
            GUI_PANEL_PLAIN_1,
            GUI_PANEL_PLAIN_2,
            GUI_PANEL_PLAIN_3,
            GUI_PANEL_PLAIN_4,
            GUI_PANEL_PLAIN_5,
            GUI_PANEL_PLAIN_6);
    // U+FE071..U+FE07F is this font's room to grow.

    // Row furniture, declared in all six row fonts; advances come from the PNGs via MenuFont.

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

    /** The 158 px plate in a darker grey, for a heading row that is not clickable. */
    public static final String GUI_ROW_PILL_DARK = cp(0xFE105);

    /** A 50 px gold button plate, for the hand-in's confirm, which a second click cannot undo. */
    public static final String GUI_ROW_BUTTON_CONFIRM = cp(0xFE106);

    /** A 68 px button plate in the affirming style - the grave's "take everything". */
    public static final String GUI_ROW_BUTTON_TAKE = cp(0xFE107);

    /** The entry plate at 122 px, for a row that carries controls on its right. */
    public static final String GUI_ROW_PILL_SHORT = cp(0xFE108);

    // Row icons - U+FE110..U+FE11A, 8 x 8, drawn white so a component's colour can tint them.
    public static final String GUI_ROW_ICON_SPAWN = cp(0xFE110);
    public static final String GUI_ROW_ICON_DEATH = cp(0xFE111);
    public static final String GUI_ROW_ICON_POI = cp(0xFE112);
    public static final String GUI_ROW_ICON_STOP = cp(0xFE113);
    public static final String GUI_ROW_ICON_PREV = cp(0xFE114);
    public static final String GUI_ROW_ICON_NEXT = cp(0xFE115);

    // The objective card's four states; exactly one is drawn on a card.
    public static final String GUI_ROW_ICON_HAND_IN = cp(0xFE116);
    public static final String GUI_ROW_ICON_STATISTIC = cp(0xFE117);
    public static final String GUI_ROW_ICON_ADVANCEMENT = cp(0xFE118);
    public static final String GUI_ROW_ICON_DONE = cp(0xFE119);

    /** An experience orb - what the objective menu's share line is about. */
    public static final String GUI_ROW_ICON_AURA = cp(0xFE11A);
    // U+FE11B..U+FE1FF is this block's room to grow.

    // Menu surfaces taller than one row live in nordtal:gui, one ascent per glyph.

    /** The objective card, 68 x 32, on the upper of the two card rows (top y 37). */
    public static final String GUI_CARD_TOP = cp(0xFE200);

    /** The same card on the lower card row (top y 73). */
    public static final String GUI_CARD_BOTTOM = cp(0xFE201);

    /** The green wash over a finished card, upper row. */
    public static final String GUI_CARD_DONE_TOP = cp(0xFE202);

    /** The same wash, lower row. */
    public static final String GUI_CARD_DONE_BOTTOM = cp(0xFE203);

    /**
     * The progress bar's fill in powers of two, for the upper card row.
     *
     * The track is baked into the card, so an empty bar draws nothing.
     */
    public static final List<String> GUI_BAR_FILL_TOP =
            List.of(cp(0xFE210), cp(0xFE211), cp(0xFE212), cp(0xFE213), cp(0xFE214), cp(0xFE215));

    /** The hand-in's tray: one sunken surface of 162 x 54 over three chest rows. */
    public static final String GUI_HANDIN_TRAY = cp(0xFE204);

    /**
     * The grave's slab, one glyph per row count from one row up to five.
     *
     * Five is the most there can be: a player carries at most forty-one stacks and row six is the footer.
     */
    public static final List<String> GUI_GRAVE_SLAB =
            List.of(cp(0xFE205), cp(0xFE206), cp(0xFE207), cp(0xFE208), cp(0xFE209));

    /** The wheel's panel: five rows with a ring of twelve prize cells and a hub. */
    public static final String GUI_WHEEL_RING = cp(0xFE20A);

    /** The same six, for the lower card row. */
    public static final List<String> GUI_BAR_FILL_BOTTOM =
            List.of(cp(0xFE218), cp(0xFE219), cp(0xFE21A), cp(0xFE21B), cp(0xFE21C), cp(0xFE21D));

    /** The widths {@link #GUI_BAR_FILL_TOP} draws, in the same order. */
    public static final List<Integer> GUI_BAR_FILL_WIDTHS = List.of(1, 2, 4, 8, 16, 32);
}
