package eu.nordtal.s2.steward.ui.configfile;

import eu.nordtal.s2.steward.ui.configfile.ConfigEntry.Type;
import org.jetbrains.annotations.NotNull;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.util.Map;

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
        if (type == Type.STRING) {
            return quote(value, path);
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
        return trimmed;
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
    private static String quote(final String value, final String path) {
        // A tab or a newline inside a plain scalar happens to read back correctly, and is still
        // never written that way: a raw tab in a YAML file is a trap for the next person to open
        // it by hand, because YAML forbids one in indentation and most editors show neither.
        if (!hasControlCharacter(value) && readsBackAs(value, value)) {
            return value;
        }
        final String single = "'" + value.replace("'", "''") + "'";
        if (!hasControlCharacter(value) && readsBackAs(single, value)) {
            return single;
        }
        final String doubled = doubleQuoted(value);
        if (readsBackAs(doubled, value)) {
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

    /** Whether {@code rendered}, put after a colon, reads back as exactly the string {@code expected}. */
    private static boolean readsBackAs(final String rendered, final String expected) {
        final Object parsed;
        try {
            parsed = parse(rendered);
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
        final Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        final Object root = yaml.load("k: " + rendered);
        if (!(root instanceof Map<?, ?> map) || map.size() != 1 || !map.containsKey("k")) {
            // `rendered` was something that did not stay inside the value position, e.g. a newline
            // followed by another key. Not a scalar, whatever else it is.
            throw new IllegalArgumentException("not a single scalar");
        }
        return map.get("k");
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
}
