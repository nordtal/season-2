package eu.nordtal.season.messages.spec.layout;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * A line of a text block: a key, a data list with a line per item, or an added text.
 *
 * @param style how the line stands out, {@code null} for plain text
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

    /** The ways a line stands out from plain text, like Discord's markdown headings. */
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

    public static Line added(final @Nullable Style style, final Map<String, String> texts) {
        return new Line(null, null, style, texts);
    }
}
