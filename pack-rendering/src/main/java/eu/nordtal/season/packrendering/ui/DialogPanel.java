package eu.nordtal.season.packrendering.ui;

import eu.nordtal.season.packrendering.Glyphs;
import java.util.List;

/** The dialog panels of the design system's Panels table, each in tiles of at most 256 columns. */
public enum DialogPanel {
    RIDE_LIST_153(276, 153, Glyphs.UI_DIALOG_RIDE_LIST_153),
    RIDE_LIST_42(276, 42, Glyphs.UI_DIALOG_RIDE_LIST_42),
    RIDE_LIST_62(276, 62, Glyphs.UI_DIALOG_RIDE_LIST_62),
    RIDE_LIST_82(276, 82, Glyphs.UI_DIALOG_RIDE_LIST_82),
    RIDE_LIST_102(276, 102, Glyphs.UI_DIALOG_RIDE_LIST_102),
    RIDE_LIST_122(276, 122, Glyphs.UI_DIALOG_RIDE_LIST_122),
    RIDE_LIST_33(276, 33, Glyphs.UI_DIALOG_RIDE_LIST_33),
    STATION_78(198, 78, Glyphs.UI_DIALOG_STATION_78),
    STATION_104(198, 104, Glyphs.UI_DIALOG_STATION_104),
    STATION_122(198, 122, Glyphs.UI_DIALOG_STATION_122),
    OBJECTIVES_124(262, 124, Glyphs.UI_DIALOG_OBJECTIVES_124),
    OBJECTIVES_33(262, 33, Glyphs.UI_DIALOG_OBJECTIVES_33),
    DUEL_124(206, 124, Glyphs.UI_DIALOG_DUEL_124),
    DRAFT_PREVIEW_113(278, 113, Glyphs.UI_DIALOG_DRAFT_PREVIEW_113),
    DRAFT_PREVIEW_42(278, 42, Glyphs.UI_DIALOG_DRAFT_PREVIEW_42),
    DRAFT_PREVIEW_62(278, 62, Glyphs.UI_DIALOG_DRAFT_PREVIEW_62),
    DRAFT_PREVIEW_82(278, 82, Glyphs.UI_DIALOG_DRAFT_PREVIEW_82),
    WHEEL_137(196, 137, Glyphs.UI_DIALOG_WHEEL_137);

    /** The widest tile the pack draws. */
    public static final int TILE = 256;

    /** The ride list's panels for 1 to 5 rows on one page. */
    public static final List<DialogPanel> RIDE_LIST_ROWS =
            List.of(RIDE_LIST_42, RIDE_LIST_62, RIDE_LIST_82, RIDE_LIST_102, RIDE_LIST_122);

    /** The draft preview's panels for 1 to 3 rows on one page. */
    public static final List<DialogPanel> DRAFT_PREVIEW_ROWS =
            List.of(DRAFT_PREVIEW_42, DRAFT_PREVIEW_62, DRAFT_PREVIEW_82);

    private final int width;
    private final int height;
    // Glyphs holds List.of lists, which are immutable.
    @SuppressWarnings("ImmutableEnumChecker")
    private final List<String> tiles;

    DialogPanel(final int width, final int height, final List<String> tiles) {
        this.width = width;
        this.height = height;
        this.tiles = tiles;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    List<String> tiles() {
        return tiles;
    }
}
