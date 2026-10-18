package eu.nordtal.season.database.game;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Stores the game data of the servers and the icons of each version. Every write signals {@code nordtal_game_data}.
 *
 * A Paper server publishes its catalogue, steward-agent stores the icons, steward reads both.
 */
public interface GameDataStore {

    /** Returns a store that uses {@code dataSource} and never closes it. */
    static GameDataStore using(final DataSource dataSource) {
        return new JdbiGameDataStore(dataSource);
    }

    /** Replaces the catalogue of {@code server}. */
    void publish(String server, GameCatalogue catalogue);

    /** Returns the time of each server's last export, by server. */
    Map<String, Instant> exports();

    /** Returns the catalogue of each server, by server. */
    Map<String, GameCatalogue> catalogues();

    /** Returns the versions that a server runs and whose icons are not drawn or were drawn by another painter. */
    List<String> versionsToDraw(String painter);

    /** Returns the painter of each version's icons, by version. */
    Map<String, String> iconPainters();

    /** Stores the icons of a version and replaces those of an earlier painter. */
    void storeIcons(String minecraftVersion, Icons icons);

    /** Returns the icons of a version, if drawn. */
    Optional<Icons> icons(String minecraftVersion);

    /** Returns the slot of each item's icon in the sheet of a version, without the sheet. */
    Optional<IconIndex> iconIndex(String minecraftVersion);

    /** The icons of one version: a sheet of 32-pixel icons and the slot of each item's icon. */
    final class Icons {

        private final byte[] png;
        private final IconIndex index;

        /**
         * @param png the sheet as a PNG, {@code columns} icons to a row
         * @param slots the slot of each item's icon, by namespaced id
         * @param painter the identity of the drawing code
         */
        public Icons(final byte[] png, final int columns, final Map<String, Integer> slots, final String painter) {
            this.png = png.clone();
            this.index = new IconIndex(columns, Map.copyOf(slots), painter);
        }

        public byte[] png() {
            return png.clone();
        }

        public IconIndex index() {
            return index;
        }
    }

    /**
     * Locates the icons of a version, without the pixels.
     *
     * @param painter the identity of the drawing code, empty if none was recorded
     */
    record IconIndex(int columns, Map<String, Integer> slots, String painter) {}
}
