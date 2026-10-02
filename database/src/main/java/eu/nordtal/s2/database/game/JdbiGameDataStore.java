package eu.nordtal.s2.database.game;

import com.google.gson.reflect.TypeToken;
import eu.nordtal.s2.common.json.Json;
import eu.nordtal.s2.database.Jdbis;
import eu.nordtal.s2.database.notify.Channel;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

/** The only implementation of {@link GameDataStore}; it borrows the pool it is given and owns nothing. */
final class JdbiGameDataStore implements GameDataStore {

    private static final TypeToken<Map<String, Integer>> SLOTS = new TypeToken<>() {};

    private final Jdbi jdbi;

    JdbiGameDataStore(final DataSource dataSource) {
        this.jdbi = Jdbis.over(Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Override
    public void publish(final String server, final GameCatalogue catalogue) {
        jdbi.useTransaction(handle -> {
            handle.createUpdate("""
                            INSERT INTO game_catalogue (server, minecraft_version, datapacks, catalogue, exported)
                            VALUES (:server, :version, :datapacks, CAST(:catalogue AS jsonb), now())
                            ON CONFLICT (server) DO UPDATE
                                SET minecraft_version = excluded.minecraft_version, datapacks = excluded.datapacks,
                                    catalogue = excluded.catalogue, exported = excluded.exported""")
                    .bind("server", server)
                    .bind("version", catalogue.minecraftVersion())
                    .bindArray("datapacks", String.class, catalogue.datapacks())
                    .bind("catalogue", Json.encode(new Stored(catalogue.registries(), catalogue.tags())))
                    .execute();
            signal(handle);
        });
    }

    @Override
    public Map<String, Instant> exports() {
        return jdbi.withHandle(handle -> {
            final Map<String, Instant> exports = new LinkedHashMap<>();
            handle.createQuery("SELECT server, exported FROM game_catalogue ORDER BY server")
                    .map((rows, context) -> Map.entry(
                            rows.getString("server"),
                            rows.getObject("exported", OffsetDateTime.class).toInstant()))
                    .forEach(entry -> exports.put(entry.getKey(), entry.getValue()));
            return exports;
        });
    }

    @Override
    public Map<String, GameCatalogue> catalogues() {
        return jdbi.withHandle(handle -> {
            final Map<String, GameCatalogue> catalogues = new LinkedHashMap<>();
            handle.createQuery("""
                            SELECT server, minecraft_version, datapacks, catalogue::text AS catalogue
                            FROM game_catalogue ORDER BY server""")
                    .map((rows, context) -> Map.entry(rows.getString("server"), catalogueOf(rows)))
                    .forEach(entry -> catalogues.put(entry.getKey(), entry.getValue()));
            return catalogues;
        });
    }

    @Override
    public List<String> versionsWithoutIcons() {
        return jdbi.withHandle(
                handle -> handle.createQuery("""
                        SELECT DISTINCT catalogue.minecraft_version FROM game_catalogue catalogue
                        WHERE NOT EXISTS (SELECT 1 FROM game_assets assets
                                          WHERE assets.minecraft_version = catalogue.minecraft_version)
                        ORDER BY 1""").mapTo(String.class).list());
    }

    @Override
    public void storeIcons(final String minecraftVersion, final Icons icons) {
        jdbi.useTransaction(handle -> {
            handle.createUpdate("""
                            INSERT INTO game_assets (minecraft_version, icons, columns, icon_index, fetched)
                            VALUES (:version, :icons, :columns, CAST(:slots AS jsonb), now())
                            ON CONFLICT (minecraft_version) DO NOTHING""")
                    .bind("version", minecraftVersion)
                    .bind("icons", icons.png())
                    .bind("columns", icons.index().columns())
                    .bind("slots", Json.encode(icons.index().slots()))
                    .execute();
            signal(handle);
        });
    }

    @Override
    public Optional<Icons> icons(final String minecraftVersion) {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT icons, columns, icon_index::text AS slots FROM game_assets
                        WHERE minecraft_version = :version""")
                .bind("version", minecraftVersion)
                .map((rows, context) -> new Icons(
                        rows.getBytes("icons"), rows.getInt("columns"), Json.decode(rows.getString("slots"), SLOTS)))
                .findOne());
    }

    @Override
    public Optional<IconIndex> iconIndex(final String minecraftVersion) {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT columns, icon_index::text AS slots FROM game_assets
                        WHERE minecraft_version = :version""")
                .bind("version", minecraftVersion)
                .map((rows, context) ->
                        new IconIndex(rows.getInt("columns"), Json.decode(rows.getString("slots"), SLOTS)))
                .findOne());
    }

    private static GameCatalogue catalogueOf(final ResultSet rows) throws SQLException {
        final Stored stored = Json.decode(rows.getString("catalogue"), Stored.class);
        final Array datapacks = rows.getArray("datapacks");
        return new GameCatalogue(
                rows.getString("minecraft_version"),
                List.of((String[]) datapacks.getArray()),
                stored.registries() == null ? Map.of() : stored.registries(),
                stored.tags() == null ? Map.of() : stored.tags());
    }

    // In the writing transaction, so a listener woken by it reads what was written.
    private static void signal(final Handle handle) {
        handle.createQuery("SELECT pg_notify(:channel, '') IS NULL")
                .bind("channel", Channel.GAME_DATA.sqlName())
                .mapTo(Boolean.class)
                .one();
    }

    // The catalogue column: what the server said beyond the version and the datapacks, which are columns.
    private record Stored(
            Map<String, List<GameCatalogue.Entry>> registries, Map<String, List<GameCatalogue.Tag>> tags) {}
}
