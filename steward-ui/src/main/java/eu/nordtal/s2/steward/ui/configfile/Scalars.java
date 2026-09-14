package eu.nordtal.s2.steward.ui.configfile;

import eu.nordtal.s2.steward.ui.configfile.ConfigEntry.Type;
import org.jetbrains.annotations.NotNull;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a scalar is, and how to write one back.
 *
 * <p><b>Every question here is answered by asking YAML, not by a regular expression of my own.</b>
 * The type of a value is the tag SnakeYAML's resolver gave it while reading the file, so
 * {@code weird-string: '12'} is a string and {@code port: 8080} is an integer without this class
 * having an opinion. Whether a value needs quotes is decided by writing it out plain, reading that
 * back, and seeing whether the same string came back - the parser's answer to the parser's
 * question. A hand-written list of "characters that need quoting" is a list that is missing one.</p>
 */
final class Scalars {

    private Scalars() {
    }

    /**
     * The type of a scalar, from the tag the resolver gave it.
     *
     * <p>A quoted scalar resolves to {@code str} whatever it contains, which is exactly right: a
     * value jcore wrote as {@code '12'} came out of a {@code String} method and has to go back into
     * one.</p>
     */
    static @NotNull Type typeOf(final @NotNull ScalarNode node) {
        final Tag tag = node.getTag();
        if (Tag.INT.equals(tag)) {
            return Type.INTEGER;
        }
        if (Tag.FLOAT.equals(tag)) {
            return Type.DECIMAL;
        }
        if (Tag.BOOL.equals(tag)) {
            return Type.BOOLEAN;
        }
        // str, null, timestamp, binary and anything explicitly tagged. A null is a string as far
        // as a form is concerned: the box is empty and typing in it produces text.
        return Type.STRING;
    }

    /**
     * The exact characters to put after the colon for {@code value}, quoted only if it has to be.
     *
     * @param type  the type the key already has in the file
     * @param value what the form sent
     * @param path  the dotted path, for the message
     * @return the rendered scalar
     * @throws IllegalArgumentException if {@code value} is not of {@code type}
     */
    static @NotNull String render(final @NotNull Type type,
                                  final @NotNull String value,
                                  final @NotNull String path) {
        return render(type, value, path, Where.VALUE);
    }

    /**
     * One entry of a sequence.
     *
     * @param type the type the list already holds - see {@link ConfigEntry#type()}
     * @param flow whether the list is written {@code [a, b]} rather than as a block
     */
    static @NotNull String renderItem(final @NotNull Type type,
                                      final @NotNull String value,
                                      final @NotNull String path,
                                      final boolean flow) {
        return render(type, value, path, flow ? Where.FLOW_ITEM : Where.BLOCK_ITEM);
    }

    private static String render(final Type type,
                                 final String value,
                                 final String path,
                                 final Where where) {
        if (type == Type.STRING) {
            return quote(value, path, where);
        }

        // Numbers and booleans are written exactly as they were typed, once YAML agrees that is
        // what they are. Canonicalising them - 1.50 to 1.5, 007 to 7 - would rewrite a line the
        // operator did not ask to have rewritten, and the diff is the thing they will read.
        final String trimmed = value.strip();
        final Object parsed = parse(trimmed);
        final boolean ok = switch (type) {
            case INTEGER -> parsed instanceof Integer || parsed instanceof Long
                    || parsed instanceof java.math.BigInteger;
            case DECIMAL -> parsed instanceof Number number && isFinite(number);
            case BOOLEAN -> parsed instanceof Boolean;
            case STRING -> true;
        };
        if (!ok) {
            throw new IllegalArgumentException(
                    path + " is " + type.name().toLowerCase(java.util.Locale.ROOT) + " in this file"
                            + ", and \"" + value + "\" is not one" + expected(type));
        }
        // ASKING THE TYPE IS NOT ENOUGH. `8080 # oops` resolves to the integer 8080, so the switch
        // above is happy - and what would be written is the whole line, comment included. The file
        // then reads back 8080 where 8080 # oops was asked for, ConfigFiles.verify catches the
        // disagreement one step before the write and throws "this is a bug in ConfigFiles", which
        // reaches the operator as a 500 for their own typo. So the text has to be the scalar, not
        // merely contain one: whatever YAML would take as the value has to be all of it.
        if (!trimmed.equals(lexically(trimmed, where))) {
            throw new IllegalArgumentException(
                    path + " is " + type.name().toLowerCase(java.util.Locale.ROOT) + " in this file"
                            + ", and \"" + value + "\" carries something after the value"
                            + " - a comment, or a second word" + expected(type));
        }
        return trimmed;
    }

    /**
     * What YAML would read as the scalar itself, out of {@code rendered} put where it is going.
     *
     * <p>This is the text of the node, not the value it resolves to: {@code 1.50} comes back
     * {@code 1.50} rather than {@code 1.5}, because canonicalising a number the operator did not
     * ask to have canonicalised is a line they did not ask to have rewritten. What it does drop is
     * everything that is not the scalar - a trailing {@code # comment} above all.</p>
     *
     * @return the node's own text, or {@code null} if that position does not hold one plain scalar
     */
    private static String lexically(final String rendered, final Where where) {
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

    private static String expected(final Type type) {
        return switch (type) {
            case INTEGER -> " - a whole number, such as 8080";
            case DECIMAL -> " - a number, such as 1.5";
            case BOOLEAN -> " - write true or false";
            case STRING -> "";
        };
    }

    /**
     * Quotes {@code value} exactly when leaving it bare would change it.
     *
     * <p>Plain first, because a diff of a config file is read by a person and
     * {@code public-url: https://…} is easier to read than {@code public-url: 'https://…'}. Single
     * quotes next, which is what jcore's own writer uses. Double quotes last, for the values that
     * carry a newline or a control character and cannot be written any other way.</p>
     */
    private static String quote(final String value, final String path, final Where where) {
        // A tab or a newline inside a plain scalar happens to read back correctly, and is still
        // never written that way: a raw tab in a YAML file is a trap for the next person to open
        // it by hand, because YAML forbids one in indentation and most editors show neither.
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
        // Unreachable for any string a browser can send; a bug here must not be a corrupt config.
        throw new IllegalArgumentException(
                path + ": this value cannot be written to a YAML file - " + describe(value));
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
            // Not even valid YAML in that position - `a: b`, a lone `[`. Quote it.
            return false;
        }
        return parsed instanceof String s && s.equals(expected);
    }

    /**
     * Reads {@code rendered} as the value of a key, in isolation.
     *
     * <p>Isolation is a fair test of the real line: a plain scalar ends at a {@code #} or a
     * {@code : } here for the same reason it would end there in the file.</p>
     */
    private static Object parse(final String rendered) {
        return parse(rendered, Where.VALUE);
    }

    private static Object parse(final String rendered, final Where where) {
        final Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        final Object root = yaml.load(where.document(rendered));
        if (!(root instanceof Map<?, ?> map) || map.size() != 1 || !map.containsKey("k")) {
            // `rendered` was something that did not stay inside the value position, e.g. a newline
            // followed by another key. Not a scalar, whatever else it is.
            throw new IllegalArgumentException("not a single scalar");
        }
        final Object value = map.get("k");
        if (where == Where.VALUE) {
            return value;
        }
        // A list entry has to stay ONE entry. `a,b` reads back as the string "a,b" after a colon
        // and as two entries inside brackets, and a renderer that only asked the first question
        // turns one service name into two on the day somebody types a comma.
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

    /**
     * Where a rendered scalar is going, which decides what "reads back correctly" means.
     *
     * <p>The same characters mean different things in the three places a value can sit, and asking
     * the parser the wrong one of these questions is how a correct-looking quote rule corrupts a
     * list.</p>
     */
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
     * @param header the {@code |}, {@code |-}, {@code |+} or {@code |2-} that follows the colon
     * @param lines  the content, each line already indented two columns past the key. An empty
     *               line is empty rather than two spaces, because trailing whitespace in a config
     *               file is noise in every future diff
     */
    record Block(@NotNull String header, @NotNull List<String> lines) {
    }

    /**
     * Writes {@code value} as a literal block, if it can be written as one.
     *
     * <p>The chomping indicator is chosen from how the value ends, which is the only way a block
     * can carry that fact: {@code |-} for a value that ends mid-line, {@code |} for one that ends
     * with a single newline. A value ending in more than one newline is not written as a block at
     * all - {@code |+} is the indicator for that, and it swallows the blank lines that follow the
     * block, which in a jcore-written file is the separator before the next key. A block written
     * that way grows a newline every time somebody saves the page. A first line that begins with a
     * space needs the indentation stated outright ({@code |2-}), because YAML would otherwise
     * measure the indentation from that line and eat the space.</p>
     *
     * <p>Folded blocks ({@code >}) are never written, only read. Folding turns a newline into a
     * space on the way back in, so a value that survives the round trip is a coincidence and one
     * that does not is a config nobody can see the mistake in.</p>
     *
     * @return the block, or empty when the value cannot be one - an empty value, a value that is
     *         nothing but newlines, one ending in several of them, or anything that did not read
     *         back as itself. The caller then
     *         writes a double-quoted single line, which is uglier and always correct
     */
    static @NotNull Optional<Block> block(final @NotNull String value) {
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
            // `|+` would be the indicator for this, and `|+` reads the blank lines AFTER the block
            // as part of the value. jcore separates every key with a blank line, so a block written
            // that way grows a newline every time the file is read. Quoting is the honest answer.
            return Optional.empty();
        }
        final String chomp = trailing == 0 ? "-" : "";
        final char first = core.charAt(0);
        final String header = (first == ' ' || first == '\t' ? "|2" : "|") + chomp;

        final List<String> lines = new ArrayList<>();
        for (final String line : core.split("\n", -1)) {
            lines.add(line.isEmpty() ? "" : "  " + line);
        }

        // The probe has the same geometry as the real thing - key at one column, content two
        // deeper - so an answer here is an answer about the file.
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
