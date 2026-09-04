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
 * <h2>Why a line, and why that is not a shortcut</h2>
 * The obvious column for a map of arguments is JSON, and {@code :common} has no JSON parser on
 * purpose - jackson is what jcore dropped, gson is a platform library that must never be shaded into
 * a Paper plugin, and adding either for a handful of short strings is the trade docs/architecture.md
 * already refused twice for command frameworks.
 *
 * <p>A line works because {@link Declaration} makes it unambiguous, and does so by construction
 * rather than by convention: at most one argument is {@link Argument.Kind#GREEDY_STRING}, a
 * declaration with one anywhere but last is refused outright, and none of the other four kinds can
 * contain a space. So the split is decided entirely by the declaration, and
 * {@code RequestArgumentsTest} round-trips every declaration in the repository rather than a handful
 * of examples.</p>
 *
 * <h2>What it refuses, and where that lands</h2>
 * Encoding refuses a value that would not survive the trip - a word with a space in it, which is
 * only reachable if an adapter parsed something as the wrong kind. Decoding refuses a line that does
 * not match the declaration: a missing required argument, an integer out of its bounds, a choice
 * that is not one of them, a UUID that is not one, or tokens left over at the end. Every one of
 * those is a disagreement between two adapters and not a user error, so it throws rather than
 * quietly running the command with something plausible.
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
                // Optionals are trailing - Declaration refuses a required argument after an
                // optional one - so the first absent value ends the line and nothing after it can
                // have been supplied.
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
            case CHOICE -> {
                if (!argument.choices().contains(token)) {
                    throw new IllegalArgumentException(declaration.name() + ": '" + token
                            + "' is not one of " + argument.choices() + " for argument '"
                            + argument.name() + "'");
                }
                yield token;
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
