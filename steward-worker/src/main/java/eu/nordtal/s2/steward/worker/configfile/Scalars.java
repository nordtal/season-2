package eu.nordtal.s2.steward.worker.configfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

/**
 * What a scalar is and how to write one back, answered by asking SnakeYAML rather than a regular expression.
 *
 * A value needs quotes when writing it plain and reading it back does not return the same string.
 */
final class Scalars {

    private Scalars() {}

    /** The type of a scalar, from the tag the resolver gave it; a quoted scalar is always {@code str}. */
    static ConfigEntry.Type typeOf(final ScalarNode node) {
        final Tag tag = node.getTag();
        if (Tag.INT.equals(tag)) {
            return ConfigEntry.Type.INTEGER;
        }
        if (Tag.FLOAT.equals(tag)) {
            return ConfigEntry.Type.DECIMAL;
        }
        if (Tag.BOOL.equals(tag)) {
            return ConfigEntry.Type.BOOLEAN;
        }
        // str, null, timestamp, binary and anything explicitly tagged: an empty box produces text.
        return ConfigEntry.Type.STRING;
    }

    /**
     * The exact characters to put after the colon for {@code value}, quoted only if it has to be.
     *
     * @param type the type the key already has in the file
     * @param value what the form sent
     * @param path the dotted path, for the message
     * @return the rendered scalar
     * @throws IllegalArgumentException if {@code value} is not of {@code type}
     */
    static String render(final ConfigEntry.Type type, final String value, final String path) {
        return render(type, value, path, Where.VALUE);
    }

    /**
     * One entry of a sequence.
     *
     * @param type the type the list already holds (see {@link ConfigEntry#type()})
     * @param flow whether the list is written {@code [a, b]} rather than as a block
     */
    static String renderItem(final ConfigEntry.Type type, final String value, final String path, final boolean flow) {
        return render(type, value, path, flow ? Where.FLOW_ITEM : Where.BLOCK_ITEM);
    }

    private static String render(
            final ConfigEntry.Type type, final String value, final String path, final Where where) {
        if (type == ConfigEntry.Type.STRING) {
            return quote(value, path, where);
        }

        // Numbers and booleans are written as typed; canonicalising (1.50 to 1.5) would be an unasked diff.
        final String trimmed = value.strip();
        final Object parsed = parse(trimmed);
        final boolean ok = switch (type) {
            case INTEGER ->
                parsed instanceof Integer || parsed instanceof Long || parsed instanceof java.math.BigInteger;
            case DECIMAL -> parsed instanceof Number number && isFinite(number);
            case BOOLEAN -> parsed instanceof Boolean;
            case STRING -> true;
        };
        if (!ok) {
            throw new IllegalArgumentException(path + " is " + type.name().toLowerCase(java.util.Locale.ROOT)
                    + " in this file" + ", and \"" + value + "\" is not one" + expected(type));
        }
        // `8080 # oops` resolves to 8080, but writing it would keep the comment too.
        if (!trimmed.equals(lexically(trimmed, where))) {
            throw new IllegalArgumentException(
                    path + " is " + type.name().toLowerCase(java.util.Locale.ROOT) + " in this file"
                            + ", and \"" + value + "\" carries something after the value"
                            + " - a comment, or a second word" + expected(type));
        }
        return trimmed;
    }

    /**
     * The scalar's own text as YAML reads it at its destination, without a trailing comment.
     *
     * @return the node's text, not its resolved value, or {@code null} if that position does not hold one plain scalar
     */
    private static @Nullable String lexically(final String rendered, final Where where) {
        final Node root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions()))
                    .compose(new java.io.StringReader(where.document(rendered)));
        } catch (final RuntimeException e) {
            return null;
        }
        if (!(root instanceof MappingNode mapping) || mapping.getValue().size() != 1) {
            return null;
        }
        Node held = mapping.getValue().getFirst().getValueNode();
        if (where != Where.VALUE) {
            if (!(held instanceof SequenceNode sequence) || sequence.getValue().size() != 1) {
                return null;
            }
            held = sequence.getValue().getFirst();
        }
        return held instanceof ScalarNode scalar ? scalar.getValue() : null;
    }

    /** {@code .nan} and {@code .inf} are numbers to YAML and nothing a config should hold. */
    private static boolean isFinite(final Number number) {
        final double d = number.doubleValue();
        return !Double.isNaN(d) && !Double.isInfinite(d);
    }

    private static String expected(final ConfigEntry.Type type) {
        return switch (type) {
            case INTEGER -> " - a whole number, such as 8080";
            case DECIMAL -> " - a number, such as 1.5";
            case BOOLEAN -> " - write true or false";
            case STRING -> "";
        };
    }

    /** Quotes {@code value} only when leaving it bare would change it: plain first, then single, then double quotes. */
    private static String quote(final String value, final String path, final Where where) {
        // A tab or newline in a plain scalar reads back correctly but is a trap, so it is never written that way.
        if (!hasControlCharacter(value) && readsBackAs(value, value, where)) {
            return value;
        }
        final String single = "'" + value.replace("'", "''") + "'";
        if (!hasControlCharacter(value) && readsBackAs(single, value, where)) {
            return single;
        }
        final String doubled = doubleQuoted(value);
        if (readsBackAs(doubled, value, where)) {
            return doubled;
        }
        // Unreachable for any string a browser can send; a bug here must not corrupt a config.
        throw new IllegalArgumentException(path + ": this value cannot be written to a YAML file - " + describe(value));
    }

    private static boolean hasControlCharacter(final String value) {
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return true;
            }
        }
        return false;
    }

    private static String describe(final String value) {
        return value.length() > 40 ? value.substring(0, 40) + "… (" + value.length() + " chars)" : value;
    }

    /** Whether {@code rendered}, put where it is going, reads back as exactly {@code expected}. */
    private static boolean readsBackAs(final String rendered, final String expected, final Where where) {
        final Object parsed;
        try {
            parsed = parse(rendered, where);
        } catch (final RuntimeException e) {
            // Not valid YAML in that position (`a: b`, a lone `[`), so quote it.
            return false;
        }
        return parsed instanceof String s && s.equals(expected);
    }

    /** Reads {@code rendered} as the value of a key, in isolation, which ends a plain scalar where the file would. */
    private static Object parse(final String rendered) {
        return parse(rendered, Where.VALUE);
    }

    private static Object parse(final String rendered, final Where where) {
        final Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        final Object root = yaml.load(where.document(rendered));
        if (!(root instanceof Map<?, ?> map) || map.size() != 1 || !map.containsKey("k")) {
            // `rendered` left the value position, for example a newline followed by another key.
            throw new IllegalArgumentException("not a single scalar");
        }
        final Object value = map.get("k");
        if (where == Where.VALUE) {
            return value;
        }
        // A list entry has to stay one entry: `a,b` is one string after a colon but two inside brackets.
        if (!(value instanceof List<?> list) || list.size() != 1) {
            throw new IllegalArgumentException("not a single entry");
        }
        return list.getFirst();
    }

    private static String doubleQuoted(final String value) {
        final StringBuilder out = new StringBuilder(value.length() + 8).append('"');
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        out.append(String.format("\\x%02x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** Where a rendered scalar is going, which decides what "reads back correctly" means. */
    private enum Where {
        /** After a colon: {@code k: <it>}. */
        VALUE,
        /** One entry of a block sequence: {@code k:\n- <it>}. */
        BLOCK_ITEM,
        /** One entry of a flow sequence: {@code k: [<it>]}. */
        FLOW_ITEM;

        String document(final String rendered) {
            return switch (this) {
                case VALUE -> "k: " + rendered;
                case BLOCK_ITEM -> "k:\n- " + rendered;
                case FLOW_ITEM -> "k: [" + rendered + "]";
            };
        }
    }

    /**
     * A value written as a literal block.
     *
     * @param header the {@code |}, {@code |-}, {@code |+} or {@code |2-} after the colon
     * @param lines the content, indented two columns past the key, with empty lines left empty
     */
    record Block(String header, List<String> lines) {}

    /**
     * Writes {@code value} as a literal block when it can, never as {@code |+} (it grows on every save) or folded.
     *
     * @return the block, or empty when the value cannot be one and the caller writes a double-quoted line
     */
    static Optional<Block> block(final String value) {
        String core = value;
        int trailing = 0;
        while (core.endsWith("\n")) {
            core = core.substring(0, core.length() - 1);
            trailing++;
        }
        if (core.isEmpty()) {
            return Optional.empty();
        }

        if (trailing > 1) {
            // `|+` reads blank lines after the block as part of the value, so quote instead.
            return Optional.empty();
        }
        final String chomp = trailing == 0 ? "-" : "";
        final char first = core.charAt(0);
        final String header = (first == ' ' || first == '\t' ? "|2" : "|") + chomp;

        final List<String> lines = new ArrayList<>();
        for (final String line : core.split("\n", -1)) {
            lines.add(line.isEmpty() ? "" : "  " + line);
        }

        // The probe has the same geometry as the file, so its answer holds for the file.
        final String probe = "k: " + header + "\n" + String.join("\n", lines) + "\n";
        final Object parsed;
        try {
            parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(probe);
        } catch (final RuntimeException e) {
            return Optional.empty();
        }
        if (parsed instanceof Map<?, ?> map && value.equals(map.get("k"))) {
            return Optional.of(new Block(header, List.copyOf(lines)));
        }
        return Optional.empty();
    }
}
