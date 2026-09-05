package eu.nordtal.s2.commands;

import java.util.List;
import java.util.Objects;

/**
 * One argument of a command, described rather than parsed.
 *
 * <h2>A closed set of five kinds, not a type hierarchy</h2>
 * Every argument in this network is one of five things, and the list is not a guess - it is what the
 * commands that exist actually take: a word ({@code /smp objective complete <key>}), a greedy string
 * ({@code /phase launch 2026-10-01 18:00}, where a date carries a space), a bounded integer
 * ({@code /smp aura <player> <delta>}), a player, or one of a fixed set of choices
 * ({@code /phase set <phase>}).
 *
 * <p>A generic {@code Argument<T>} with parsers and codecs would cover more, and the more it would
 * cover is nothing anybody has asked for. docs/architecture.md rejected two command frameworks on
 * exactly that trade - "the surface is small and shallow" - and a home-grown one gets no exemption
 * from its own reasoning. When a sixth kind is genuinely needed, adding it here is a line; unpicking
 * a type hierarchy nothing used would not be.</p>
 *
 * <h2>What each adapter does with this</h2>
 * Brigadier builds a real argument node and gets suggestions and client-side syntax highlighting for
 * free; JDA builds an {@code OptionData}; the remote channel writes the value into the request row.
 * None of them re-decides what the argument <em>is</em>, which is the whole point of declaring it
 * once.
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

    /** What an argument accepts. Five, because five is what the network's commands take. */
    public enum Kind {

        /** A single unquoted word. Keys, names of things. */
        WORD,

        /**
         * The rest of the line, spaces included.
         *
         * <p>Must be last in a command, and {@link Declaration} refuses one that is not: Brigadier
         * would otherwise hand the whole remainder to it and call the next argument unexpected.
         * That is not hypothetical - {@code /phase launch} takes a date and a time, and it is
         * greedy for exactly that reason.</p>
         */
        GREEDY_STRING,

        /** A whole number between {@link #min} and {@link #max}, both inclusive. */
        INTEGER,

        /**
         * A player.
         *
         * <p>In chat that is a Minecraft name; in Discord it is a member picked from the list and
         * resolved through {@code account_link}. The adapter does the resolving, so a command sees a
         * player either way - which is what stops "who does this correct?" from being two different
         * questions on two surfaces.</p>
         */
        PLAYER,

        /** One of {@link #choices}. Suggested in chat, a real choice list in Discord. */
        CHOICE,

        /**
         * A person, identified by their <b>Discord account</b>.
         *
         * <h2>Why this is not {@link #PLAYER}</h2>
         * Because {@code PLAYER} resolves through {@code account_link} on both surfaces, and the
         * commands that need this one act on people who may not have linked yet:
         * {@code /access grant} is exactly what an admin runs for a member whose payment arrived
         * outside the normal flow, and requiring a link would mean the command could not be used on
         * the person it exists for. It was written as {@code PLAYER} for half an afternoon and that
         * is what it would have cost.
         *
         * <p>In Discord it is a member picked from the list, taken as their id and nothing else. In
         * chat it is a Minecraft name resolved through {@code account_link} - which is the one
         * direction that <em>does</em> need a link, and is refused with its own sentence when there
         * is none, because an admin in game has no other way to name a Discord account.</p>
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
