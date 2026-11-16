package eu.nordtal.season.messagerendering.text;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** How far vanilla's letters, the symbols and the moves advance in every ui text font, as the build measured them. */
public final class Letters {

    /** The advance the client gives a character that no sheet holds: its missing glyph box. */
    public static final int MISSING = 6;

    /** The ellipsis that ends a text cut to its room. */
    public static final String ELLIPSIS = "…";

    private static final String RESOURCE = "/nordtal/ui/letter-advances.properties";

    private static final Map<Integer, Integer> TABLE = load();

    private Letters() {}

    /** Returns how far {@code codePoint} moves the cursor. */
    public static int advance(final int codePoint) {
        return TABLE.getOrDefault(codePoint, MISSING);
    }

    /** Returns whether the ui text font carries {@code codePoint}, so it draws as more than a missing-glyph box. */
    public static boolean covers(final int codePoint) {
        return TABLE.containsKey(codePoint);
    }

    /** Returns how far {@code text} moves the cursor, its last spacing column included. */
    public static int width(final String text) {
        return text.codePoints().map(Letters::advance).sum();
    }

    /** Returns the columns {@code text} inks: its advance without the last spacing column. */
    public static int visibleWidth(final String text) {
        return Math.max(width(text) - 1, 0);
    }

    /** Returns {@code text}, or its start ending in an ellipsis where its visible width exceeds {@code room}. */
    public static String cut(final String text, final int room) {
        if (visibleWidth(text) <= room) {
            return text;
        }
        String start = text;
        while (!start.isEmpty() && visibleWidth(start.stripTrailing() + ELLIPSIS) > room) {
            start = start.substring(0, start.offsetByCodePoints(start.length(), -1));
        }
        return start.stripTrailing() + ELLIPSIS;
    }

    /** Breaks {@code text} into rows of at most {@code wrap} advance at spaces, inside a word only if it is wider. */
    public static List<String> wrap(final String text, final int wrap) {
        final List<String> rows = new ArrayList<>();
        for (final String paragraph : text.split("\n", -1)) {
            StringBuilder row = new StringBuilder();
            for (final String word : paragraph.split(" ", -1)) {
                final String candidate = row.isEmpty() ? word : row + " " + word;
                if (width(candidate) <= wrap) {
                    row = new StringBuilder(candidate);
                    continue;
                }
                if (!row.isEmpty()) {
                    rows.add(row.toString());
                }
                row = new StringBuilder();
                String rest = word;
                while (width(rest) > wrap) {
                    int end = rest.length();
                    while (end > 1 && width(rest.substring(0, end)) > wrap) {
                        end = rest.offsetByCodePoints(end, -1);
                    }
                    rows.add(rest.substring(0, end));
                    rest = rest.substring(end);
                }
                row.append(rest);
            }
            rows.add(row.toString());
        }
        return List.copyOf(rows);
    }

    /** Breaks {@code text} at spaces into lines inking at most {@code room} columns, in a word only if it is wider. */
    public static List<String> wrapVisible(final String text, final int room) {
        final List<String> lines = new ArrayList<>();
        String line = "";
        for (final String word : text.split(" ", -1)) {
            final String candidate = line.isEmpty() ? word : line + " " + word;
            if (visibleWidth(candidate) <= room) {
                line = candidate;
                continue;
            }
            if (!line.isEmpty()) {
                lines.add(line);
            }
            String rest = word;
            while (visibleWidth(rest) > room) {
                final int end = fitting(rest, room);
                lines.add(rest.substring(0, end));
                rest = rest.substring(end);
            }
            line = rest;
        }
        lines.add(line);
        return List.copyOf(lines);
    }

    /** Returns where the longest start of {@code word} inking at most {@code room} columns ends, a letter at least. */
    private static int fitting(final String word, final int room) {
        int end = Character.charCount(word.codePointAt(0));
        int advance = advance(word.codePointAt(0));
        while (end < word.length()) {
            final int letter = word.codePointAt(end);
            if (advance + advance(letter) - 1 > room) {
                break;
            }
            advance += advance(letter);
            end += Character.charCount(letter);
        }
        return end;
    }

    private static Map<Integer, Integer> load() {
        final Properties properties = new Properties();
        try (InputStream stream = Letters.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(
                        RESOURCE + " is missing from the classpath: :resource-pack's generateGlyphs task writes it");
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
