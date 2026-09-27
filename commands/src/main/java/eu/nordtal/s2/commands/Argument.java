package eu.nordtal.s2.commands;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One argument of a command, described rather than parsed; each adapter builds its own form from it.
 *
 * @param name     the argument's name, as it appears in both the chat syntax and the Discord option
 * @param kind     what it accepts
 * @param required whether the command can run without it
 * @param min      lower bound, {@link Kind#INTEGER} only
 * @param max      upper bound, {@link Kind#INTEGER} only
 * @param choices  the permitted values, {@link Kind#CHOICE} only
 */
public record Argument(String name, Kind kind, boolean required, int min, int max, List<String> choices) {

    /** What an argument accepts. */
    public enum Kind {

        /** A single unquoted word. */
        WORD,

        /** The rest of the line, spaces included; {@link Declaration} refuses one that is not last. */
        GREEDY_STRING,

        /** A whole number between {@link #min} and {@link #max}, both inclusive. */
        INTEGER,

        /** A player: a Minecraft name in chat, a member resolved through {@code account_link} in Discord. */
        PLAYER,

        /** One of {@link #choices}. Suggested in chat, a real choice list in Discord. */
        CHOICE,

        /**
         * A person by Discord account, who may not have linked a Minecraft account yet.
         *
         * In chat it is a Minecraft name resolved through {@code account_link}, refused without a link.
         */
        ACCOUNT,

        /** An open payment reference, a word that every adapter offers a list of. */
        REFERENCE
    }

    public Argument {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        choices = List.copyOf(Objects.requireNonNull(choices, "choices"));
        if (name.isBlank()) {
            throw new IllegalArgumentException("an argument needs a name");
        }
        if (kind == Kind.INTEGER && min > max) {
            throw new IllegalArgumentException("argument '" + name + "' has min " + min + " above max " + max);
        }
        if (kind == Kind.CHOICE && choices.isEmpty()) {
            throw new IllegalArgumentException("argument '" + name + "' is a CHOICE with nothing to choose from");
        }
        if (kind != Kind.CHOICE && !choices.isEmpty()) {
            throw new IllegalArgumentException("argument '" + name + "' is a " + kind + " and carries choices anyway");
        }
    }

    /**
     * Returns the declared choice a typed value means, matched ignoring case but spelled as declared.
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

    /** A required Discord account, of a person who may not have linked a Minecraft one. */
    public static Argument account(final String name) {
        return new Argument(name, Kind.ACCOUNT, true, 0, 0, List.of());
    }

    /** A required open payment reference, which every adapter offers a list of. */
    public static Argument reference(final String name) {
        return new Argument(name, Kind.REFERENCE, true, 0, 0, List.of());
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
