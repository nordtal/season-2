package eu.nordtal.s2.common.menu;

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
 * The five-pixel alphabet the row fonts draw, and how wide anything written in it is.
 *
 * <h2>Why a server can know this</h2>
 * The same reason {@link eu.nordtal.s2.common.hud.BossBarAdvances} can: the sheet is ours. Every
 * advance is a property of a PNG in this repository, and the client derives it by one rule - the
 * rightmost drawn column of the glyph's cell, plus one for that column and one it adds after every
 * glyph. So "how wide is BAECKEREI AM FLUSS on chest row 2" is a question this JVM can answer
 * exactly, which is what makes a right-aligned distance and a truncated name possible at all.
 *
 * <h2>Capitals, and the one exception</h2>
 * The sheet has no lower case (owner, 2026-09-07): at eight pixels almost every POI name was cut
 * off on an objective card, and five pixels only works without descenders. So text is folded to
 * capitals on the way in - <b>except ß</b>, whose upper case is the two letters SS and therefore
 * not a character. {@code Character.toUpperCase(char)} already answers ß for ß, which is exactly
 * the behaviour wanted here and exactly what {@code String.toUpperCase()} does <em>not</em> do.
 *
 * <h2>What happens to a character the sheet has never heard of</h2>
 * It becomes {@code ?}. A POI name is typed by a player, so this is not a theoretical case: an
 * emoji, a Cyrillic letter or a {@code <} all reach this class. The alternative is the client's
 * missing-glyph box, which is six pixels wide, is not in the table, and would therefore make every
 * width computed after it wrong - so the row would not merely look bad, it would be laid out
 * wrong. Folding is visible and bounded; a box is neither.
 */
public final class MenuFont {

    /** What a character the sheet does not carry is drawn as. */
    public static final char UNKNOWN = '?';

    /** The tallest a row glyph is: the sheet is five pixels, with no descenders. */
    public static final int HEIGHT = 5;

    private static final String RESOURCE = "/nordtal/menu/gui-row-advances.properties";

    private static final Map<Integer, Integer> TABLE = load();

    private MenuFont() {
    }

    /** @return how far {@code codePoint} moves the cursor in a row font, or 0 if it has none */
    public static int advance(final int codePoint) {
        return TABLE.getOrDefault(codePoint, 0);
    }

    /** @return true when the row fonts declare {@code codePoint} */
    public static boolean covers(final int codePoint) {
        return TABLE.containsKey(codePoint);
    }

    /**
     * Folds one character onto the sheet's alphabet.
     *
     * @return the character the row fonts would draw for {@code character}
     */
    public static char fold(final char character) {
        final char upper = Character.toUpperCase(character);
        return covers(upper) ? upper : UNKNOWN;
    }

    /**
     * Folds a whole string - the one place text becomes drawable, and the one place it is measured.
     *
     * <p>Everything a caller hands to a row is passed through this first, so that
     * {@link #width(String)} is measuring the same characters that will be drawn. Measuring the
     * unfolded string and drawing the folded one is the way a right-aligned number ends up a
     * pixel or two out for exactly the names that contain something unusual.</p>
     */
    public static String fold(final String text) {
        final StringBuilder out = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            out.append(fold(text.charAt(index)));
        }
        return out.toString();
    }

    /** @return the cursor's displacement after drawing {@code text}, which must already be folded */
    public static int width(final String text) {
        return text.codePoints().map(MenuFont::advance).sum();
    }

    /**
     * Shortens {@code text} until it fits {@code pixels}, ending it in two dots when it does not.
     *
     * <p>Two dots rather than an ellipsis because the sheet has no ellipsis, and two dots rather
     * than one because one reads as a full stop. The text is folded first, so the width measured
     * here is the width drawn.</p>
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

    /** @return the whole table, code point to advance - for tests, and for nothing else */
    public static Map<Integer, Integer> table() {
        return Map.copyOf(TABLE);
    }

    private static Map<Integer, Integer> load() {
        final Properties properties = new Properties();
        try (InputStream stream = MenuFont.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(RESOURCE + " is missing from the classpath - run"
                        + " resource-pack/tools/generate_gui_rows.py");
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + RESOURCE, e);
        }
        final Map<Integer, Integer> table = new HashMap<>();
        properties.forEach((key, value) -> table.put(Integer.parseInt(String.valueOf(key), 16),
                Integer.parseInt(String.valueOf(value).trim())));
        return Map.copyOf(table);
    }
}
