package eu.nordtal.season.messages.text;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public sealed interface Node {

    /** Characters shown as they are.
     * In a tag's argument they are the argument's own markup as written.
     */
    record Literal(String text) implements Node {

        public Literal {
            Objects.requireNonNull(text, "text");
        }
    }

    /** A value written as {@code {name}}, {@code {name, kind}} or {@code {name, kind, style}}. */
    record Value(
            String name, @Nullable String kind, @Nullable String style) implements Node {

        public Value {
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * {@code {name, plural, one {…} other {…}}} or {@code {name, select, a {…} other {…}}}.
     *
     * @param plural whether the cases are plural categories and {@code =n}, rather than select
     *     choices
     */
    record Choice(String name, boolean plural, Map<String, List<Node>> cases) implements Node {

        public Choice {
            Objects.requireNonNull(name, "name");
            cases = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(cases));
        }
    }

    /**
     * A building block, {@code {@key}} or {@code {@key, value=outer}}, drawn where it stands.
     *
     * @param with each value of the block to the value of the text it shows. A value of the block
     *     that it leaves out shows the text's value of the same name.
     */
    record Block(String key, Map<String, String> with) implements Node {

        public Block {
            Objects.requireNonNull(key, "key");
            with = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(with));
        }
    }

    /** {@code #} in a plural case. It stands for the number the plural depends on. */
    record Pound(String name) implements Node {}

    /** A MiniMessage tag, kept as structure so a value inside an argument is escaped for where it stands. */
    record Tag(String name, List<Arg> args, Shape shape) implements Node {

        public Tag {
            Objects.requireNonNull(name, "name");
            args = List.copyOf(args);
            Objects.requireNonNull(shape, "shape");
        }

        public enum Shape {
            OPEN,
            CLOSE,
            SELF_CLOSING
        }

        /** One argument, {@code :value} or {@code :'value'}. */
        public record Arg(List<Node> parts, char quote) {

            public Arg {
                parts = List.copyOf(parts);
            }

            /** Returns the argument's text when it holds no value, else {@code null}. */
            public @Nullable String literal() {
                final StringBuilder out = new StringBuilder();
                for (final Node part : parts) {
                    if (!(part instanceof final Literal literal)) {
                        return null;
                    }
                    out.append(literal.text());
                }
                return out.toString();
            }
        }
    }
}
