package eu.nordtal.season.messages.text;

import eu.nordtal.season.messages.value.Kind;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/** Makes a text's choices and finds its values, once for every target. */
public final class Filling {

    private final Map<String, ?> values;
    private final Map<String, Kind> declared;
    private final Consumer<String> missing;

    private Filling(final Map<String, ?> values, final Map<String, Kind> declared, final Consumer<String> missing) {
        this.values = values;
        this.declared = declared;
        this.missing = missing;
    }

    /**
     * Returns the text's pieces with every plural and select chosen and every value found.
     *
     * @param values   placeholder to value, contexts already flattened
     * @param declared placeholder to the kind its message declares; one it lacks takes its value's kind
     * @param missing  told the name of each placeholder that has no value or one of no kind, which shows its
     *                 replacement word
     */
    public static List<Piece> fill(
            final MessageText text,
            final Map<String, ?> values,
            final Map<String, Kind> declared,
            final Consumer<String> missing) {
        final List<Piece> pieces = new ArrayList<>();
        new Filling(values, declared, missing).add(text.nodes(), pieces);
        return pieces;
    }

    private void add(final List<Node> nodes, final List<Piece> into) {
        for (final Node node : nodes) {
            switch (node) {
                case Node.Literal literal -> into.add(new Piece.Text(literal.text()));
                case Node.Value value -> into.add(filled(value.name(), value.kind(), value.style()));
                case Node.Pound pound -> into.add(filled(pound.name(), Kind.NUMBER.token(), null));
                case Node.Choice choice -> add(chosen(choice), into);
                case Node.Tag tag -> {
                    final List<List<Piece>> args = new ArrayList<>();
                    for (final Node.Tag.Arg arg : tag.args()) {
                        final List<Piece> parts = new ArrayList<>();
                        for (final Node part : arg.parts()) {
                            parts.add(
                                    part instanceof final Node.Value value
                                            ? filled(value.name(), value.kind(), value.style())
                                            : new Piece.Text(((Node.Literal) part).text()));
                        }
                        args.add(parts);
                    }
                    into.add(new Piece.Markup(tag, args));
                }
            }
        }
    }

    /** A value of no kind is no value: it shows the replacement word like a missing one, never its toString. */
    private Piece.Filled filled(final String name, final @Nullable String written, final @Nullable String style) {
        final Object value = values.get(name);
        final Kind kind = Kind.ofValue(value).orElse(null);
        if (kind == null) {
            missing.accept(name);
            final Kind shown = declared.containsKey(name)
                    ? declared.get(name)
                    : written == null ? Kind.TEXT : Kind.byToken(written).orElse(Kind.TEXT);
            return new Piece.Filled(name, shown, null, style);
        }
        return new Piece.Filled(name, kind, value, style);
    }

    private List<Node> chosen(final Node.Choice choice) {
        final Object value = values.get(choice.name());
        if (value == null) {
            missing.accept(choice.name());
            return other(choice);
        }
        if (!choice.plural()) {
            final List<Node> named = choice.cases().get(Kind.choiceOf(value));
            return named == null ? other(choice) : named;
        }
        final BigDecimal number = number(value);
        if (number != null) {
            final List<Node> exact =
                    choice.cases().get("=" + number.stripTrailingZeros().toPlainString());
            if (exact != null) {
                return exact;
            }
            final List<Node> category = choice.cases().get(PluralRules.category(number));
            if (category != null) {
                return category;
            }
        }
        return other(choice);
    }

    /** The case for whatever no other names, which the parser makes sure every choice has. */
    private static List<Node> other(final Node.Choice choice) {
        return java.util.Objects.requireNonNull(choice.cases().get("other"), "other");
    }

    private static @Nullable BigDecimal number(final Object value) {
        if (value instanceof final BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof final Number number) {
            return number instanceof Double || number instanceof Float
                    ? BigDecimal.valueOf(number.doubleValue())
                    : BigDecimal.valueOf(number.longValue());
        }
        return null;
    }
}
