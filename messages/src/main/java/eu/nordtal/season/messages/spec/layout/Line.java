package eu.nordtal.season.messages.spec.layout;

import org.jspecify.annotations.Nullable;

/**
 * One line of a text block: a key, or a data list whose items are a line each.
 *
 * @param key   the text as {@code bundle/key}, or {@code null} for a data list
 * @param list  the data list's element id, or {@code null} for a text
 * @param style how the line stands out, {@code null} for plain text
 */
public record Line(
        @Nullable String key,
        @Nullable String list,
        @Nullable Style style) {

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
}
