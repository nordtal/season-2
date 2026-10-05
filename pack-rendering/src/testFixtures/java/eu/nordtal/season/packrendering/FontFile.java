package eu.nordtal.season.packrendering;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.season.common.RepositoryRoot;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One of the resource pack's font files, read the way the client reads it.
 * A {@code bitmap} provider lays a grid over one texture whose cell size comes from the image; {@code U+0000} is empty.
 */
public final class FontFile {

    /** Where a texture id such as {@code nordtal:font/ascii.png} resolves against. */
    private static final String ASSETS = RepositoryRoot.packAssets();

    /**
     * Resolves {@code namespace:path/to.png} to its file in the unpacked pack, as the client does.
     *
     * @param textureId e.g. {@code nordtal:system/join.png}, or {@code font/ascii.png} for vanilla
     */
    public static Path texturePath(final String textureId) {
        final int colon = textureId.indexOf(':');
        final String namespace = colon < 0 ? "minecraft" : textureId.substring(0, colon);
        final String path = textureId.substring(colon + 1);
        return RepositoryRoot.resolve(ASSETS)
                .resolve(namespace)
                .resolve("textures")
                .resolve(path);
    }

    private final String id;
    private final Path source;
    private final List<Bitmap> bitmaps = new ArrayList<>();
    private final Set<Integer> spaced = new LinkedHashSet<>();

    private FontFile(final String id, final Path source) {
        this.id = id;
        this.source = source;
    }

    /**
     * Loads a font file.
     *
     * @param id       the namespaced font id, e.g. {@code nordtal:bossbar}
     * @param relative the font file's path from the repository root
     */
    static FontFile load(final String id, final String relative) {
        final FontFile font = new FontFile(id, RepositoryRoot.resolve(relative));
        final JsonObject root =
                JsonParser.parseString(RepositoryRoot.read(relative)).getAsJsonObject();

        for (final JsonElement element : root.getAsJsonArray("providers")) {
            final JsonObject provider = element.getAsJsonObject();
            switch (provider.get("type").getAsString()) {
                case "space" -> {
                    for (final Map.Entry<String, JsonElement> advance :
                            provider.getAsJsonObject("advances").entrySet()) {
                        advance.getKey().codePoints().forEach(font.spaced::add);
                    }
                }
                case "bitmap" -> font.bitmaps.add(Bitmap.of(provider));
                default ->
                    throw new IllegalStateException(relative + " has a provider of unknown type "
                            + provider.get("type").getAsString());
            }
        }
        return font;
    }

    String id() {
        return id;
    }

    Path source() {
        return source;
    }

    List<Bitmap> bitmaps() {
        return List.copyOf(bitmaps);
    }

    /** Returns whether this font draws or advances {@code codePoint}. */
    boolean covers(final int codePoint) {
        return spaced.contains(codePoint)
                || bitmaps.stream().anyMatch(bitmap -> bitmap.cellOf(codePoint).isPresent());
    }

    /** Every code point this font resolves at all, advances and pixels alike. */
    Set<Integer> declared() {
        final Set<Integer> all = new LinkedHashSet<>(spaced);
        all.addAll(drawn());
        return all;
    }

    /** Every code point this font draws pixels for, without the space providers. */
    Set<Integer> drawn() {
        final Set<Integer> all = new LinkedHashSet<>();
        bitmaps.forEach(bitmap -> all.addAll(bitmap.characters()));
        return all;
    }

    /** One bitmap provider: a texture, and the grid of code points laid over it. */
    record Bitmap(String textureId, Path texture, List<int[]> grid, int rows, int columns) {

        private static Bitmap of(final JsonObject provider) {
            final String textureId = provider.get("file").getAsString();
            final JsonArray chars = provider.getAsJsonArray("chars");

            final List<int[]> grid = new ArrayList<>();
            for (final JsonElement row : chars) {
                grid.add(row.getAsString().codePoints().toArray());
            }
            final int columns = grid.isEmpty() ? 0 : grid.get(0).length;
            for (final int[] row : grid) {
                if (row.length != columns) {
                    throw new IllegalStateException(textureId
                            + " has chars rows of different lengths (" + columns + " and "
                            + row.length + "); the client would place every glyph after it wrong");
                }
            }
            return new Bitmap(textureId, texturePath(textureId), List.copyOf(grid), grid.size(), columns);
        }

        /** Returns the {@code {row, column}} of {@code codePoint} in this provider's grid. */
        Optional<int[]> cellOf(final int codePoint) {
            for (int row = 0; row < grid.size(); row++) {
                for (int column = 0; column < grid.get(row).length; column++) {
                    if (grid.get(row)[column] == codePoint) {
                        return Optional.of(new int[] {row, column});
                    }
                }
            }
            return Optional.empty();
        }

        /** Every code point this provider declares, without {@code U+0000} placeholders. */
        Set<Integer> characters() {
            final Set<Integer> all = new LinkedHashSet<>();
            for (final int[] row : grid) {
                for (final int codePoint : row) {
                    if (codePoint != 0) {
                        all.add(codePoint);
                    }
                }
            }
            return all;
        }
    }
}
