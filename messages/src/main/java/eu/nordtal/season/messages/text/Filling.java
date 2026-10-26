package eu.nordtal.season.messages.text;

import eu.nordtal.season.messages.value.Kind;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/** Chooses a text's plurals and selects, finds its values and draws its building blocks. */
public final class Filling {

    /** A building block's text in the reader's language, with the kinds its values declare. */
    public record Block(MessageText text, Map<String, Kind> values) {}

    /** Finds the building blocks a text names. */
    @FunctionalInterface
    public interface Blocks {

        Blocks NONE = key -> null;

        /** Returns the block of {@code key}, else {@code null} for a key that is no building block. */
        @Nullable
        Block block(String key);
    }

    private final Map<String, ?> values;
    private final Map<String, Kind> declared;
    private final Consumer<String> missing;
    private final Blocks blocks;

    /** The blocks being drawn around this text, so a block that names itself shows its replacement word. */
    private final Set<String> open;

    private Filling(
            final Map<String, ?> values,
            final Map<String, Kind> declared,
            final Consumer<String> missing,
            final Blocks blocks,
            final Set<String> open) {
        this.values = values;
        this.declared = declared;
        this.missing = missing;
        this.blocks = blocks;
        this.open = open;
    }

    /**
     * @param values placeholder to value, contexts already flattened
     * @param declared placeholder to the kind its message declares. A placeholder it lacks takes
     *     its value's kind.
     * @param missing receives the name of each placeholder that has no value or a value of no kind,
     *     which shows its replacement word
     */
    public static List<Piece> fill(
            final MessageText text,
            final Map<String, ?> values,
            final Map<String, Kind> declared,
            final Consumer<String> missing) {
        return fill(text, values, declared, missing, Blocks.NONE);
    }

    /** Fills {@code text} and draws each building block it names from {@code blocks}. */
    public static List<Piece> fill(
            final MessageText text,
            final Map<String, ?> values,
            final Map<String, Kind> declared,
            final Consumer<String> missing,
            final Blocks blocks) {
        final List<Piece> pieces = new ArrayList<>();
        new Filling(values, declared, missing, blocks, Set.of()).add(text.nodes(), pieces);
        return pieces;
    }

    private void add(final List<Node> nodes, final List<Piece> into) {
        for (final Node node : nodes) {
            switch (node) {
                case Node.Literal literal -> into.add(new Piece.Text(literal.text()));
                case Node.Value value -> into.add(filled(value.name(), value.kind(), value.style()));
                case Node.Pound pound -> into.add(filled(pound.name(), Kind.NUMBER.token(), null));
                case Node.Choice choice -> add(chosen(choice), into);
                case Node.Block block -> block(block, into);
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

    /** A block that is unknown or names itself shows the replacement word of a text. */
    private void block(final Node.Block reference, final List<Piece> into) {
        final String key = reference.key();
        final Block block = open.contains(key) ? null : blocks.block(key);
        if (block == null) {
            missing.accept("@" + key);
            into.add(new Piece.Filled("@" + key, Kind.TEXT, null, null));
            return;
        }
        final Map<String, Object> inner = new HashMap<>(values);
        reference.with().forEach((name, outer) -> {
            inner.keySet().removeIf(shown -> shown.equals(name) || shown.startsWith(name + "."));
            values.forEach((shown, value) -> {
                if (shown.equals(outer)) {
                    inner.put(name, value);
                } else if (shown.startsWith(outer + ".")) {
                    inner.put(name + shown.substring(outer.length()), value);
                }
            });
        });
        final Set<String> around = new HashSet<>(open);
        around.add(key);
        new Filling(inner, block.values(), missing, blocks, around)
                .add(block.text().nodes(), into);
    }

    /** A value of no kind shows the replacement word, as a missing value does, and never its {@code toString}. */
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

    /** Returns the case for whatever no other case names. */
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
