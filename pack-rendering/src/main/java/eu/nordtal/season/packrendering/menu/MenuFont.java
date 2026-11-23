package eu.nordtal.season.packrendering.menu;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/** The five-pixel alphabet of the row fonts. Text is folded to capitals except ß. */
public final class MenuFont {

    /** The character drawn for one that the sheet lacks. */
    public static final char UNKNOWN = '?';

    /** The height of a row glyph's body: five pixels on the baseline, no descenders. */
    public static final int HEIGHT = 5;

    /** The height of a sheet cell: the body and two rows above it for the umlauts' dots. */
    public static final int CELL = 7;

    /** The metre's small m, the sheet's one lower-case letter. */
    public static final char METRE = '\u1D50';

    private static final String RESOURCE = "/nordtal/menu/gui-row-advances.properties";

    private static final Map<Integer, Integer> TABLE = load();

    private MenuFont() {}

    /** Returns the cursor advance of {@code codePoint} in a row font, or 0 if it has none. */
    public static int advance(final int codePoint) {
        return TABLE.getOrDefault(codePoint, 0);
    }

    public static boolean covers(final int codePoint) {
        return TABLE.containsKey(codePoint);
    }

    /** Returns the character that the row fonts draw for {@code character}. */
    public static char fold(final char character) {
        // A row holds one line.
        if (character == '\n') {
            return ' ';
        }
        final char upper = Character.toUpperCase(character);
        return covers(upper) ? upper : UNKNOWN;
    }

    /** Folds a string. The sheet has no ellipsis, so "…" becomes two dots. */
    public static String fold(final String text) {
        final StringBuilder out = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            final char character = text.charAt(index);
            if (character == '\u2026') {
                out.append("..");
            } else {
                out.append(fold(character));
            }
        }
        return out.toString();
    }

    /** Returns the cursor advance of {@code text}, which must be folded. */
    public static int width(final String text) {
        return text.codePoints().map(MenuFont::advance).sum();
    }

    /**
     * Returns the drawn width of {@code text}, which must be folded: its advance without the clear column after its
     * last glyph.
     */
    public static int visible(final String text) {
        return Math.max(0, width(text) - 1);
    }

    /** Shortens {@code text} to draw at most {@code pixels} GUI pixels wide and ends it in two dots if it does not fit. */
    public static String fit(final String text, final int pixels) {
        final String folded = fold(text);
        if (visible(folded) <= pixels) {
            return folded;
        }
        String shorter = folded;
        while (!shorter.isEmpty() && visible(shorter + "..") > pixels) {
            shorter = shorter.substring(0, shorter.length() - 1);
        }
        return shorter.stripTrailing() + "..";
    }

    public static Map<Integer, Integer> table() {
        return Map.copyOf(TABLE);
    }

    private static Map<Integer, Integer> load() {
        final Properties properties = new Properties();
        try (InputStream stream = MenuFont.class.getResourceAsStream(RESOURCE)) {
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
