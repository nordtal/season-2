package eu.nordtal.season.packrendering.ui;

import eu.nordtal.season.packrendering.Glyphs;
import java.util.List;

/** The dialog panels of the design system's Panels table, each in tiles of at most 256 columns. */
public enum DialogPanel {
    RIDE_LIST_146(280, 146, Glyphs.UI_DIALOG_RIDE_LIST_146),
    RIDE_LIST_42(280, 42, Glyphs.UI_DIALOG_RIDE_LIST_42),
    RIDE_LIST_62(280, 62, Glyphs.UI_DIALOG_RIDE_LIST_62),
    RIDE_LIST_82(280, 82, Glyphs.UI_DIALOG_RIDE_LIST_82),
    RIDE_LIST_102(280, 102, Glyphs.UI_DIALOG_RIDE_LIST_102),
    RIDE_LIST_122(280, 122, Glyphs.UI_DIALOG_RIDE_LIST_122),
    RIDE_LIST_33(280, 33, Glyphs.UI_DIALOG_RIDE_LIST_33),
    WIDE_161(280, 161, Glyphs.UI_DIALOG_WIDE_161),
    COMPACT_116(200, 116, Glyphs.UI_DIALOG_COMPACT_116),
    STATION_48(200, 48, Glyphs.UI_DIALOG_STATION_48),
    STATION_57(200, 57, Glyphs.UI_DIALOG_STATION_57),
    STATION_66(200, 66, Glyphs.UI_DIALOG_STATION_66),
    STATION_102(200, 102, Glyphs.UI_DIALOG_STATION_102),
    STATION_117(200, 117, Glyphs.UI_DIALOG_STATION_117),
    OBJECTIVES_124(262, 124, Glyphs.UI_DIALOG_OBJECTIVES_124),
    OBJECTIVES_33(262, 33, Glyphs.UI_DIALOG_OBJECTIVES_33),
    DUEL_124(206, 124, Glyphs.UI_DIALOG_DUEL_124),
    WHEEL_197(262, 197, Glyphs.UI_DIALOG_WHEEL_197);

    /** The widest tile the pack draws. */
    public static final int TILE = 256;

    /** The ride list's panels for 1 to 5 rows on one page. */
    public static final List<DialogPanel> RIDE_LIST_ROWS =
            List.of(RIDE_LIST_42, RIDE_LIST_62, RIDE_LIST_82, RIDE_LIST_102, RIDE_LIST_122);

    /** The station visitor's panels for 1 to 3 rows of its hint. */
    public static final List<DialogPanel> STATION_VISITOR_ROWS = List.of(STATION_48, STATION_57, STATION_66);

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

    /** The width inside the panel's margins, 7 px in from each edge. */
    public int contentWidth() {
        return width - 2 * DialogTitle.MARGIN;
    }

    /** The height inside the panel's header and foot margin, from panel + 18 to 7 px above the foot. */
    public int contentHeight() {
        return height - BodyColumn.PANEL_TO_CONTENT - DialogTitle.MARGIN;
    }

    List<String> tiles() {
        return tiles;
    }
}
