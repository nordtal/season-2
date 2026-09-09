package eu.nordtal.s2.commands;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One argument of a command, described rather than parsed.
 *
 * <p>The kinds are a closed set: each adapter (Brigadier, JDA, the remote request row) builds its
 * own representation from this declaration and never re-decides what the argument is.</p>
 *
 * @param name     the argument's name, as it appears in both the chat syntax and the Discord option
 * @param kind     what it accepts
 * @param required whether the command can run without it
 * @param min      lower bound, {@link Kind#INTEGER} only
 * @param max      upper bound, {@link Kind#INTEGER} only
 * @param choices  the permitted values, {@link Kind#CHOICE} only
 */
public record Argument(String name, Kind kind, boolean required, int min, int max,
                       List<String> choices) {

    /** What an argument accepts. */
    public enum Kind {

        /** A single unquoted word. Keys, names of things. */
        WORD,

        /**
         * The rest of the line, spaces included.
         *
         * <p>Must be last in a command, and {@link Declaration} refuses one that is not: Brigadier
         * would otherwise hand the whole remainder to it and call the next argument unexpected.</p>
         */
        GREEDY_STRING,

        /** A whole number between {@link #min} and {@link #max}, both inclusive. */
        INTEGER,

        /**
         * A player.
         *
         * <p>In chat that is a Minecraft name; in Discord it is a member picked from the list and
         * resolved through {@code account_link}. The adapter resolves, so a command sees a player
         * either way.</p>
         */
        PLAYER,

        /** One of {@link #choices}. Suggested in chat, a real choice list in Discord. */
        CHOICE,

        /**
         * A person, identified by their <b>Discord account</b>.
         *
         * <p>Not {@link #PLAYER}: the commands taking this one act on people who may not have
         * linked a Minecraft account yet ({@code /access grant} on a payment that arrived outside
         * the normal flow). In Discord it is the member's id and nothing else; in chat it is a
         * Minecraft name resolved through {@code account_link}, refused when there is no link.</p>
         */
        ACCOUNT
    }

    public Argument {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        choices = List.copyOf(Objects.requireNonNull(choices, "choices"));
        if (name.isBlank()) {
            throw new IllegalArgumentException("an argument needs a name");
        }
        if (kind == Kind.INTEGER && min > max) {
            throw new IllegalArgumentException(
                    "argument '" + name + "' has min " + min + " above max " + max);
        }
        if (kind == Kind.CHOICE && choices.isEmpty()) {
            throw new IllegalArgumentException(
                    "argument '" + name + "' is a CHOICE with nothing to choose from");
        }
        if (kind != Kind.CHOICE && !choices.isEmpty()) {
            throw new IllegalArgumentException(
                    "argument '" + name + "' is a " + kind + " and carries choices anyway");
        }
    }

    /**
     * The declared choice a typed value means, in the case the declaration wrote it.
     *
     * <p>Matching ignores case (chat is lenient) but the answer is always the declared spelling, so
     * that everything downstream - a comparison against {@code SeasonPhase.name()}, a request row,
     * an audit entry - sees one normalised form.</p>
     *
     * @return the declared choice, or empty when this is not one of them
     */
    public Optional<String> match(final String typed) {
        if (kind != Kind.CHOICE || typed == null) {
            return Optional.empty();
        }
        return choices.stream().filter(choice -> choice.equalsIgnoreCase(typed)).findFirst();
    }

    /** A required single word. */
    public static Argument word(final String name) {
        return new Argument(name, Kind.WORD, true, 0, 0, List.of());
    }

    /** A required greedy string. Must be the last argument of its command. */
    public static Argument greedy(final String name) {
        return new Argument(name, Kind.GREEDY_STRING, true, 0, 0, List.of());
    }

    /** A required whole number, bounds inclusive. */
    public static Argument integer(final String name, final int min, final int max) {
        return new Argument(name, Kind.INTEGER, true, min, max, List.of());
    }

    /** A required player. */
    public static Argument player(final String name) {
        return new Argument(name, Kind.PLAYER, true, 0, 0, List.of());
    }

    /** A required Discord account - a person who may not have linked a Minecraft one. */
    public static Argument account(final String name) {
        return new Argument(name, Kind.ACCOUNT, true, 0, 0, List.of());
    }

    /** A required choice from a fixed set. */
    public static Argument choice(final String name, final List<String> choices) {
        return new Argument(name, Kind.CHOICE, true, 0, 0, choices);
    }

    /** The same argument, optional. */
    public Argument optional() {
        return new Argument(name, kind, false, min, max, choices);
    }
}
