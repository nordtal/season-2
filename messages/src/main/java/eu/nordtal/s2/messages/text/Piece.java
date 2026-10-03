package eu.nordtal.s2.messages.text;

import eu.nordtal.s2.messages.value.Kind;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** One part of a text once its choices are made and its values found, which a target turns into its own form. */
public sealed interface Piece {

    /**
     * Characters, shown as they are.
     *
     * @param text the characters; inside a tag's argument, that argument's own markup
     */
    record Text(String text) implements Piece {}

    /**
     * A MiniMessage tag, its arguments filled.
     *
     * @param tag  the tag as written
     * @param args each argument's pieces: {@link Text} and {@link Filled} only
     */
    record Markup(Node.Tag tag, List<List<Piece>> args) implements Piece {

        public Markup {
            Objects.requireNonNull(tag, "tag");
            args = args.stream().map(List::copyOf).toList();
        }
    }

    /**
     * A value to show.
     *
     * @param name  the placeholder it fills
     * @param kind  what it is
     * @param value the value, or {@code null} when none was given, which shows the kind's replacement word
     * @param style the style the text asked for, or {@code null}
     */
    record Filled(
            String name,
            Kind kind,
            @Nullable Object value,
            @Nullable String style) implements Piece {}
}
