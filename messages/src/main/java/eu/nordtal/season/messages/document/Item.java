package eu.nordtal.season.messages.document;

import org.jspecify.annotations.Nullable;

/** A button, a select or a modal field that the code fills, or a link button that the document adds.
 *
 * @param slot the code's slot, else {@code null} for a link button
 * @param link the https address a link button opens
 * @param label a button's or a field's label
 * @param placeholder what a select or a field shows while empty
 * @param option a select's line per option, filled with the option's values
 */
public record Item(
        @Nullable String slot,
        @Nullable String link,
        @Nullable Text label,
        @Nullable Text placeholder,
        @Nullable Text option,
        @Nullable String when) {}
