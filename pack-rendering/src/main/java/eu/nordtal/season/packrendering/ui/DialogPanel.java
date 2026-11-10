package eu.nordtal.season.packrendering.ui;

import eu.nordtal.season.packrendering.Glyphs;
import java.util.List;

/** The dialog panels of the design system's Panels table, each in tiles of at most 256 columns. */
public enum DialogPanel {
    RIDE_LIST_150(276, 150, Glyphs.UI_DIALOG_RIDE_LIST_150),
    RIDE_LIST_114(276, 114, Glyphs.UI_DIALOG_RIDE_LIST_114),
    RIDE_LIST_33(276, 33, Glyphs.UI_DIALOG_RIDE_LIST_33),
    STATION_78(198, 78, Glyphs.UI_DIALOG_STATION_78),
    STATION_104(198, 104, Glyphs.UI_DIALOG_STATION_104),
    STATION_122(198, 122, Glyphs.UI_DIALOG_STATION_122),
    OBJECTIVES_176(262, 176, Glyphs.UI_DIALOG_OBJECTIVES_176),
    OBJECTIVES_33(262, 33, Glyphs.UI_DIALOG_OBJECTIVES_33),
    DUEL_178(206, 178, Glyphs.UI_DIALOG_DUEL_178),
    DRAFT_PREVIEW_150(278, 150, Glyphs.UI_DIALOG_DRAFT_PREVIEW_150),
    WHEEL_137(196, 137, Glyphs.UI_DIALOG_WHEEL_137);

    /** The widest tile the pack draws. */
    public static final int TILE = 256;

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
