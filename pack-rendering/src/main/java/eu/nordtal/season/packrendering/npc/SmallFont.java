package eu.nordtal.season.packrendering.npc;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/** The five-pixel letters of the NPC dialogue's hints, page count and answer numbers, as the build measured them. */
public final class SmallFont {

    /** The character drawn for one that the sheet lacks. */
    public static final char UNKNOWN = '?';

    private static final String RESOURCE = "/nordtal/npc/small-advances.properties";

    private static final Map<Integer, Integer> TABLE = load();

    private SmallFont() {}

    /** Returns the cursor advance of {@code codePoint}, or 0 if the sheet lacks it. */
    public static int advance(final int codePoint) {
        return TABLE.getOrDefault(codePoint, 0);
    }

    public static boolean covers(final int codePoint) {
        return TABLE.containsKey(codePoint);
    }

    /** Returns {@code text} with each character the sheet lacks replaced by {@link #UNKNOWN}, on one line. */
    public static String fold(final String text) {
        final StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> {
            if (codePoint == '\n') {
                out.append(' ');
            } else if (covers(codePoint)) {
                out.appendCodePoint(codePoint);
            } else {
                out.append(UNKNOWN);
            }
        });
        return out.toString();
    }

    /** Returns the cursor advance of {@code text}, which must be folded. */
    public static int width(final String text) {
        return text.codePoints().map(SmallFont::advance).sum();
    }

    /** Returns the columns {@code text} inks: its advance without the last spacing column. */
    public static int visibleWidth(final String text) {
        return Math.max(width(text) - 1, 0);
    }

    public static Map<Integer, Integer> table() {
        return Map.copyOf(TABLE);
    }

    private static Map<Integer, Integer> load() {
        final Properties properties = new Properties();
        try (InputStream stream = SmallFont.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(RESOURCE + " is missing from the classpath: :resource-pack's"
                        + " generateGlyphs task writes it");
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + RESOURCE, e);
        }
        final Map<Integer, Integer> table = new HashMap<>();
        properties.forEach((key, value) -> table.put(
                Integer.parseInt(String.valueOf(key), 16),
                Integer.parseInt(String.valueOf(value).trim())));
        return Map.copyOf(table);
    }
}
