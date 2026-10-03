package eu.nordtal.s2.messages.text;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** One part of a parsed message text. */
public sealed interface Node {

    /**
     * Characters shown as they are; in a tag's argument, the argument's own markup as written.
     *
     * @param text the characters, escapes already resolved outside an argument
     */
    record Literal(String text) implements Node {

        public Literal {
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * A value, {@code {name}} or {@code {name, kind}} or {@code {name, kind, style}}.
     *
     * @param name  the placeholder, such as {@code winner.team.name}
     * @param kind  the kind's token as the text wrote it, or {@code null}
     * @param style the style as the text wrote it, or {@code null}
     */
    record Value(
            String name, @Nullable String kind, @Nullable String style) implements Node {

        public Value {
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * {@code {name, plural, one {…} other {…}}} or {@code {name, select, a {…} other {…}}}.
     *
     * @param name   the placeholder chosen on
     * @param plural whether the cases are plural categories and {@code =n} rather than choices
     * @param cases  each case's text, in the order written
     */
    record Choice(String name, boolean plural, Map<String, List<Node>> cases) implements Node {

        public Choice {
            Objects.requireNonNull(name, "name");
            cases = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(cases));
        }
    }

    /** {@code #} inside a plural case: the number chosen on. */
    record Pound(String name) implements Node {}

    /**
     * A MiniMessage tag, kept as structure so a value inside an argument is escaped for where it stands.
     *
     * @param name  the tag's name, lowercase, such as {@code click} or {@code #8ba888}
     * @param args  its arguments after the name
     * @param shape whether it opens, closes or stands alone
     */
    record Tag(String name, List<Arg> args, Shape shape) implements Node {

        public Tag {
            Objects.requireNonNull(name, "name");
            args = List.copyOf(args);
            Objects.requireNonNull(shape, "shape");
        }

        /** How a tag stands in the text. */
        public enum Shape {
            OPEN,
            CLOSE,
            SELF_CLOSING
        }

        /**
         * One argument, {@code :value} or {@code :'value'}.
         *
         * @param parts literal markup and values, in order
         * @param quote the quote it was written in, or {@code 0} for none
         */
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
