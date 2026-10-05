package eu.nordtal.season.papercommon.menu;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * The five-pixel alphabet the row fonts draw, and how wide text written in it is.
 * Text is folded to capitals except ß, and a character the sheet lacks becomes {@code ?}.
 */
public final class MenuFont {

    /** What a character the sheet does not carry is drawn as. */
    public static final char UNKNOWN = '?';

    /** The tallest a row glyph is: the sheet is five pixels, with no descenders. */
    public static final int HEIGHT = 5;

    private static final String RESOURCE = "/nordtal/menu/gui-row-advances.properties";

    private static final Map<Integer, Integer> TABLE = load();

    private MenuFont() {}

    /** Returns how far {@code codePoint} moves the cursor in a row font, or 0 if it has none. */
    public static int advance(final int codePoint) {
        return TABLE.getOrDefault(codePoint, 0);
    }

    /** Returns whether the row fonts declare {@code codePoint}. */
    public static boolean covers(final int codePoint) {
        return TABLE.containsKey(codePoint);
    }

    /** Returns the character the row fonts would draw for {@code character}. */
    public static char fold(final char character) {
        final char upper = Character.toUpperCase(character);
        return covers(upper) ? upper : UNKNOWN;
    }

    /** Folds a whole string, the one place text becomes drawable and the one place it is measured. */
    public static String fold(final String text) {
        final StringBuilder out = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            out.append(fold(text.charAt(index)));
        }
        return out.toString();
    }

    /** Returns the cursor's displacement after drawing {@code text}, which must already be folded. */
    public static int width(final String text) {
        return text.codePoints().map(MenuFont::advance).sum();
    }

    /**
     * Shortens {@code text} until it fits {@code pixels}, ending it in two dots when it does not.
     *
     * @param text   any string, folded here
     * @param pixels the space available, in GUI pixels
     */
    public static String fit(final String text, final int pixels) {
        final String folded = fold(text);
        if (width(folded) <= pixels) {
            return folded;
        }
        String shorter = folded;
        while (!shorter.isEmpty() && width(shorter + "..") > pixels) {
            shorter = shorter.substring(0, shorter.length() - 1);
        }
        return shorter.stripTrailing() + "..";
    }

    /** Returns the whole table, code point to advance, for tests. */
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
