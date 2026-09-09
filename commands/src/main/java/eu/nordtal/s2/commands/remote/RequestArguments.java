package eu.nordtal.s2.commands.remote;

import eu.nordtal.s2.commands.Argument;
import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.Values;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The arguments of a travelling command, as the line that would have been typed after its path.
 *
 * <p>A line rather than JSON: {@code :common} has no JSON parser on purpose (jackson is gone, gson
 * must never be shaded into a Paper plugin). The line is unambiguous by construction, because
 * {@link Declaration} allows at most one {@link Argument.Kind#GREEDY_STRING} and only in last
 * position, and no other kind can contain a space.</p>
 *
 * <p>Both directions throw rather than run the command with something plausible: a value that would
 * not survive the trip, or a line that does not match the declaration, means two adapters disagree
 * - not that a user typed something wrong.</p>
 */
public final class RequestArguments {

    private RequestArguments() {
    }

    /**
     * The arguments of one invocation, as a line.
     *
     * @throws IllegalArgumentException if a value cannot survive the round trip, or if a required
     *                                  argument is missing from {@code values}
     */
    public static String encode(final Declaration declaration, final Values values) {
        Objects.requireNonNull(declaration, "declaration");
        Objects.requireNonNull(values, "values");

        final List<String> parts = new ArrayList<>();
        for (final Argument argument : declaration.arguments()) {
            final Optional<Object> raw = values.raw(argument.name());
            if (raw.isEmpty()) {
                if (argument.required()) {
                    throw new IllegalArgumentException(declaration.name()
                            + " is missing required argument '" + argument.name() + "'");
                }
                // Optionals are trailing, so the first absent value ends the line. A later value
                // supplied after an absent optional cannot be written back and must not be dropped
                // silently.
                for (final Argument later : declaration.arguments()
                        .subList(declaration.arguments().indexOf(argument) + 1,
                                declaration.arguments().size())) {
                    if (values.raw(later.name()).isPresent()) {
                        throw new IllegalArgumentException(declaration.name() + ": argument '"
                                + later.name() + "' was supplied after absent optional '"
                                + argument.name() + "', and a line cannot carry the gap between"
                                + " them");
                    }
                }
                break;
            }

            final String text = String.valueOf(raw.get());
            if (argument.kind() != Argument.Kind.GREEDY_STRING && text.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException(declaration.name() + ": argument '"
                        + argument.name() + "' is a " + argument.kind() + " and contains whitespace"
                        + " (\"" + text + "\"), which would split into two arguments on the way out");
            }
            if (text.isEmpty()) {
                throw new IllegalArgumentException(declaration.name() + ": argument '"
                        + argument.name() + "' is empty, which is indistinguishable from absent"
                        + " once it is a line");
            }
            // decode() skips the spaces between arguments before reading the greedy one, so a
            // leading or trailing space would not survive the round trip.
            if (argument.kind() == Argument.Kind.GREEDY_STRING && !text.equals(text.strip())) {
                throw new IllegalArgumentException(declaration.name() + ": argument '"
                        + argument.name() + "' begins or ends with whitespace (\"" + text
                        + "\"), which a line cannot carry back unchanged");
            }
            parts.add(text);
        }
        return String.join(" ", parts);
    }

    /**
     * The arguments of one invocation, read back against the declaration that wrote them.
     *
     * @throws IllegalArgumentException if the line does not match the declaration
     */
    public static Values decode(final Declaration declaration, final String line) {
        Objects.requireNonNull(declaration, "declaration");
        Objects.requireNonNull(line, "line");

        final Map<String, Object> values = new LinkedHashMap<>();
        int cursor = 0;

        for (final Argument argument : declaration.arguments()) {
            while (cursor < line.length() && line.charAt(cursor) == ' ') {
                cursor++;
            }
            if (cursor >= line.length()) {
                if (argument.required()) {
                    throw new IllegalArgumentException(declaration.name()
                            + " was sent without required argument '" + argument.name()
                            + "' - the two adapters disagree about what this command takes");
                }
                break;
            }

            final String token;
            if (argument.kind() == Argument.Kind.GREEDY_STRING) {
                token = line.substring(cursor);
                cursor = line.length();
            } else {
                int end = line.indexOf(' ', cursor);
                if (end < 0) {
                    end = line.length();
                }
                token = line.substring(cursor, end);
                cursor = end;
            }
            values.put(argument.name(), parse(declaration, argument, token));
        }

        while (cursor < line.length() && line.charAt(cursor) == ' ') {
            cursor++;
        }
        if (cursor < line.length()) {
            throw new IllegalArgumentException(declaration.name() + " was sent \""
                    + line.substring(cursor) + "\" after its last declared argument");
        }
        return new Values(declaration, values);
    }

    private static Object parse(final Declaration declaration, final Argument argument,
                                final String token) {
        return switch (argument.kind()) {
            case WORD, GREEDY_STRING -> token;
            case ACCOUNT -> {
                // A Discord snowflake is ASCII '0'..'9'; Character.isDigit would also accept
                // Devanagari and Arabic-Indic digits.
                if (!token.chars().allMatch(digit -> digit >= '0' && digit <= '9')) {
                    throw new IllegalArgumentException(declaration.name() + ": argument '"
                            + argument.name() + "' is a Discord account and was sent \"" + token
                            + "\", which is not an id - the asking adapter is meant to resolve it"
                            + " before the request is written");
                }
                yield token;
            }
            case CHOICE -> {
                // Case-insensitive in, declared spelling out: the far side compares against its own
                // constants.
                yield argument.match(token).orElseThrow(() -> new IllegalArgumentException(
                        declaration.name() + ": '" + token + "' is not one of "
                                + argument.choices() + " for argument '" + argument.name() + "'"));
            }
            case INTEGER -> {
                final int value;
                try {
                    value = Integer.parseInt(token);
                } catch (final NumberFormatException notANumber) {
                    throw new IllegalArgumentException(declaration.name() + ": argument '"
                            + argument.name() + "' is a number and was sent \"" + token + "\"",
                            notANumber);
                }
                if (value < argument.min() || value > argument.max()) {
                    throw new IllegalArgumentException(declaration.name() + ": argument '"
                            + argument.name() + "' is " + value + ", outside " + argument.min()
                            + ".." + argument.max());
                }
                yield value;
            }
            case PLAYER -> {
                try {
                    yield UUID.fromString(token);
                } catch (final IllegalArgumentException notAUuid) {
                    throw new IllegalArgumentException(declaration.name() + ": argument '"
                            + argument.name() + "' is a player and was sent \"" + token
                            + "\" rather than a UUID - the asking adapter is meant to resolve the"
                            + " name before the request is written", notAUuid);
                }
            }
        };
    }
}
