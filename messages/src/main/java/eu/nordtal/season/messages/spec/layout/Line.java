package eu.nordtal.season.messages.spec.layout;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * One line of a text block: a key, a data list whose items are a line each, or a text an editor added.
 *
 * @param key   the text as {@code bundle/key}, or {@code null}
 * @param list  the data list's element id, or {@code null}
 * @param style how the line stands out, {@code null} for plain text
 * @param texts an added text by language tag, or {@code null}
 */
public record Line(
        @Nullable String key,
        @Nullable String list,
        @Nullable Style style,
        @Nullable Map<String, String> texts) {

    public Line {
        texts = texts == null ? null : Collections.unmodifiableMap(new TreeMap<>(texts));
    }

    public Line(final @Nullable String key, final @Nullable String list, final @Nullable Style style) {
        this(key, list, style, null);
    }

    /** How a line stands out from plain text, as Discord's markdown heads a block. */
    public enum Style {
        TITLE,
        HEADING
    }

    public static Line text(final String key) {
        return new Line(key, null, null);
    }

    public static Line title(final String key) {
        return new Line(key, null, Style.TITLE);
    }

    public static Line heading(final String key) {
        return new Line(key, null, Style.HEADING);
    }

    public static Line list(final String element) {
        return new Line(null, element, null);
    }

    /** A text an editor added, which no bundle declares. */
    public static Line added(final @Nullable Style style, final Map<String, String> texts) {
        return new Line(null, null, style, texts);
    }
}
