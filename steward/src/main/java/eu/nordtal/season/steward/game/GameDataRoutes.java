package eu.nordtal.season.steward.game;

import eu.nordtal.season.database.game.GameCatalogue;
import eu.nordtal.season.database.game.GameDataStore;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Serves the game catalogue of the network's version and its icons to the pickers. */
public final class GameDataRoutes {

    /** The cache header of an icon sheet. The URL of a sheet names its painter. */
    public static final String ICONS_CACHE = "private, max-age=31536000, immutable";

    private final @Nullable GameDataStore store;
    private @Nullable Merged merged;

    public GameDataRoutes(final @Nullable GameDataStore store) {
        this.store = store;
    }

    /**
     * The game data of the newest export's version.
     *
     * @param version {@code null} before any server exported, and then every registry is empty
     * @param icons {@code null} until steward-agent has drawn them
     */
    public record GameData(
            @Nullable String version,
            List<String> datapacks,
            Map<String, List<GameCatalogue.Entry>> registries,
            Map<String, List<GameCatalogue.Tag>> tags,
            @Nullable Icons icons) {}

    /**
     * The icon sheet of one version.
     *
     * @param url the sheet, with 32 pixels per icon
     * @param slots the slot of each item, counted left to right and then down with {@code columns} per row
     */
    public record Icons(String url, int columns, Map<String, Integer> slots) {}

    public void read(final Context ctx) {
        ctx.json(read());
    }

    /** Returns the exports and icon painters, which the live topic compares. */
    public Object changes() {
        return store == null ? List.of() : List.of(store.exports(), store.iconPainters());
    }

    public GameData read() {
        if (store == null) {
            return new GameData(null, List.of(), Map.of(), Map.of(), null);
        }
        final GameCatalogue catalogue = catalogue(store);
        if (catalogue == null) {
            return new GameData(null, List.of(), Map.of(), Map.of(), null);
        }
        final String version = catalogue.minecraftVersion();
        final Icons icons = store.iconIndex(version)
                .map(index -> new Icons(iconsUrl(version, index.painter()), index.columns(), index.slots()))
                .orElse(null);
        return new GameData(version, catalogue.datapacks(), catalogue.registries(), catalogue.tags(), icons);
    }

    /** Answers the icon sheet of one version. */
    public void icons(final Context ctx) {
        final GameDataStore.Icons icons =
                store == null ? null : store.icons(ctx.pathParam("version")).orElse(null);
        if (icons == null) {
            throw new NotFoundResponse("no icons are drawn for this version");
        }
        ctx.header("Cache-Control", ICONS_CACHE);
        ctx.contentType("image/png");
        ctx.result(icons.png());
    }

    static String iconsUrl(final String version, final String painter) {
        return "/api/game-data/" + URLEncoder.encode(version, StandardCharsets.UTF_8) + "/icons.png?painter="
                + URLEncoder.encode(painter, StandardCharsets.UTF_8);
    }

    /** Returns the union catalogue of the newest export's version, merged again after a new export. */
    private synchronized @Nullable GameCatalogue catalogue(final GameDataStore store) {
        final Map<String, Instant> exports = store.exports();
        if (merged != null && merged.exports().equals(exports)) {
            return merged.catalogue();
        }
        final Map<String, GameCatalogue> catalogues = store.catalogues();
        final String newest = exports.entrySet().stream()
                .max(Map.Entry.comparingByValue(Comparator.naturalOrder()))
                .map(Map.Entry::getKey)
                .orElse(null);
        final GameCatalogue latest = newest == null ? null : catalogues.get(newest);
        final GameCatalogue union =
                latest == null ? null : GameCatalogue.union(latest.minecraftVersion(), catalogues.values());
        merged = new Merged(exports, union);
        return union;
    }

    private record Merged(
            Map<String, Instant> exports, @Nullable GameCatalogue catalogue) {}
}
