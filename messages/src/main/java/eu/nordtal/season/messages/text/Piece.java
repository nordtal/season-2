package eu.nordtal.season.messages.text;

import eu.nordtal.season.messages.value.Kind;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** One part of a text once its choices are made and its values found. */
public sealed interface Piece {

    /**
     * Characters shown as they are.
     *
     * @param text the characters. Inside a tag's argument they are that argument's own markup.
     */
    record Text(String text) implements Piece {}

    /**
     * A MiniMessage tag with its arguments filled.
     *
     * @param args each argument's pieces, only {@link Text} and {@link Filled}
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
     * @param value the value, else {@code null} when none was given, which shows the kind's
     *     replacement word
     */
    record Filled(
            String name,
            Kind kind,
            @Nullable Object value,
            @Nullable String style) implements Piece {}
}
