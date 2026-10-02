package eu.nordtal.s2.steward.web;

import eu.nordtal.s2.database.game.GameCatalogue;
import eu.nordtal.s2.database.game.GameDataStore;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What the servers know of the game, for the pickers: one catalogue for the version the network runs, and its icons.
 *
 * The catalogue is merged again only when a server exported since; the icons are immutable per version.
 */
final class GameDataRoutes {

    /** A year, and never revalidated: a version's icons are drawn once. */
    static final String ICONS_CACHE = "private, max-age=31536000, immutable";

    private final @Nullable GameDataStore store;
    private @Nullable Merged merged;

    GameDataRoutes(final @Nullable GameDataStore store) {
        this.store = store;
    }

    /**
     * The game as the newest export's version knows it, with the icons when they are drawn.
     *
     * @param version none before any server exported, and then every registry is empty
     * @param icons none until steward-agent drew them, which it does only with the installer's consent
     */
    record GameData(
            @Nullable String version,
            List<String> datapacks,
            Map<String, List<GameCatalogue.Entry>> registries,
            Map<String, List<GameCatalogue.Tag>> tags,
            @Nullable Icons icons) {}

    /**
     * Where one version's sheet is and where each item's icon sits in it.
     *
     * @param url the sheet, 32 pixels an icon
     * @param slots an item's slot, counted left to right and then down, {@code columns} to a row
     */
    record Icons(String url, int columns, Map<String, Integer> slots) {}

    void read(final Context ctx) {
        ctx.json(read());
    }

    /** What changes when a server exports or icons are drawn, which the live topic compares instead of all of it. */
    Object changes() {
        return store == null ? List.of() : List.of(store.exports(), store.versionsWithoutIcons());
    }

    GameData read() {
        if (store == null) {
            return new GameData(null, List.of(), Map.of(), Map.of(), null);
        }
        final GameCatalogue catalogue = catalogue(store);
        if (catalogue == null) {
            return new GameData(null, List.of(), Map.of(), Map.of(), null);
        }
        final String version = catalogue.minecraftVersion();
        final Icons icons = store.iconIndex(version)
                .map(index -> new Icons(iconsUrl(version), index.columns(), index.slots()))
                .orElse(null);
        return new GameData(version, catalogue.datapacks(), catalogue.registries(), catalogue.tags(), icons);
    }

    /** The sheet of one version, which a signed-in admin's browser keeps for a year. */
    void icons(final Context ctx) {
        final GameDataStore.Icons icons =
                store == null ? null : store.icons(ctx.pathParam("version")).orElse(null);
        if (icons == null) {
            throw new NotFoundResponse("no icons are drawn for this version");
        }
        ctx.header("Cache-Control", ICONS_CACHE);
        ctx.contentType("image/png");
        ctx.result(icons.png());
    }

    static String iconsUrl(final String version) {
        return "/api/game-data/" + URLEncoder.encode(version, StandardCharsets.UTF_8) + "/icons.png";
    }

    /** The union of the newest export's version, merged again only when some server exported since. */
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
