package eu.nordtal.season.database.game;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * The servers' game data and the icons drawn for each version; every write signals {@code nordtal_game_data}.
 *
 * A Paper server publishes its catalogue, steward-agent stores the icons, steward reads both.
 */
public interface GameDataStore {

    /** Returns a store over {@code dataSource}, which it borrows and never closes. */
    static GameDataStore using(final DataSource dataSource) {
        return new JdbiGameDataStore(dataSource);
    }

    /** Replaces what {@code server} said of the game before. */
    void publish(String server, GameCatalogue catalogue);

    /** When each server last exported, by server, so a reader can tell whether anything moved. */
    Map<String, Instant> exports();

    /** Every server's catalogue, by server. */
    Map<String, GameCatalogue> catalogues();

    /** The versions some server runs whose icons are not drawn yet. */
    List<String> versionsWithoutIcons();

    /** Stores the icons of a version; a version that has them keeps the first. */
    void storeIcons(String minecraftVersion, Icons icons);

    /** The icons of a version, if they were drawn. */
    Optional<Icons> icons(String minecraftVersion);

    /** The slot of each item's icon in a version's sheet, without the sheet. */
    Optional<IconIndex> iconIndex(String minecraftVersion);

    /** One version's icons: the sheet, 32 pixels an icon, and where each item's is in it. */
    final class Icons {

        private final byte[] png;
        private final IconIndex index;

        /**
         * @param png the sheet as a PNG, {@code columns} icons to a row
         * @param slots the slot of each item's icon, by namespaced id
         */
        public Icons(final byte[] png, final int columns, final Map<String, Integer> slots) {
            this.png = png.clone();
            this.index = new IconIndex(columns, Map.copyOf(slots));
        }

        /** The sheet as a PNG, a copy. */
        public byte[] png() {
            return png.clone();
        }

        public IconIndex index() {
            return index;
        }
    }

    /** Where a version's icons are, for a reader that does not need the pixels. */
    record IconIndex(int columns, Map<String, Integer> slots) {}
}
