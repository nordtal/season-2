package eu.nordtal.season.packrendering.menu;

import eu.nordtal.season.messages.value.GameContent;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/** The atlas sprite that draws an item in a text, from the table {@code :resource-pack} writes.
 * An item without one, such as a chest, a tinted leaf or an item whose look changes, shows its name alone.
 */
public final class ItemSprites {

    private static final String RESOURCE = "/nordtal/menu/item-sprites.properties";

    private static final Map<String, GameContent.Sprite> TABLE = load();

    private ItemSprites() {}

    /** Returns the sprite of the item {@code key}, a full id such as {@code minecraft:diamond}, or empty. */
    public static Optional<GameContent.Sprite> of(final String key) {
        return Optional.ofNullable(TABLE.get(key));
    }

    /** Returns every item's sprite by its full id, empty when the table is missing from the classpath. */
    public static Map<String, GameContent.Sprite> table() {
        return TABLE;
    }

    private static Map<String, GameContent.Sprite> load() {
        final Properties properties = new Properties();
        try (InputStream stream = ItemSprites.class.getResourceAsStream(RESOURCE)) {
            // A classpath without the table, such as an IDE run, draws every item's name alone.
            if (stream == null) {
                return Map.of();
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + RESOURCE, e);
        }
        final Map<String, GameContent.Sprite> table = new HashMap<>();
        properties.forEach((key, value) -> {
            final String[] parts = String.valueOf(value).trim().split(" ", 2);
            table.put(String.valueOf(key), new GameContent.Sprite(parts[0], parts[1]));
        });
        return Map.copyOf(table);
    }
}
