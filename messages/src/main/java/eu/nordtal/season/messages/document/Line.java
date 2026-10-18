package eu.nordtal.season.messages.document;

import org.jspecify.annotations.Nullable;

/** A line of a text block: a text, or a data list whose text is drawn once per item.
 *
 * @param list the slot of a data list, else {@code null}
 * @param style how the line stands out, {@code null} for plain text
 */
public record Line(
        Text text,
        @Nullable Style style,
        @Nullable String when,
        @Nullable String list) {

    /** The ways a line stands out from plain text, like Discord's markdown headings. */
    public enum Style {
        TITLE,
        HEADING
    }
}
