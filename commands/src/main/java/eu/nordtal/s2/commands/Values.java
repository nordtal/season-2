package eu.nordtal.s2.commands;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The arguments a command was actually given, already parsed and checked against its
 * {@link Declaration}.
 *
 * <h2>Why the accessors throw rather than return {@link Optional}</h2>
 * A required argument that is missing here is not a user error - the adapter would have refused the
 * input long before, with the platform's own syntax message. It is a declaration and a command that
 * disagree, which is a programming mistake, and the useful behaviour for one of those is a loud
 * failure naming the argument rather than an {@link Optional} that some branch forgets to check and
 * silently treats as "not given".
 *
 * <p>An argument declared {@link Argument#optional()} is the one case where absence is a legitimate
 * answer, and {@link #optionalString} is the accessor that says so out loud.</p>
 *
 * <h2>A player is a UUID by the time it gets here</h2>
 * Both surfaces resolve their own way - a Minecraft name in chat, a member picked from a list in
 * Discord and followed through {@code account_link} - and both end at the same UUID. That is the
 * point of resolving in the adapter: "who does this correct?" is one question with one answer,
 * rather than two questions that happen to look alike.
 */
public final class Values {

    private final Declaration declaration;
    private final Map<String, Object> values;

    public Values(final Declaration declaration, final Map<String, Object> values) {
        this.declaration = Objects.requireNonNull(declaration, "declaration");
        this.values = normalise(declaration, Objects.requireNonNull(values, "values"));
    }

    /**
     * Every {@link Argument.Kind#CHOICE} in its declared spelling, whatever case it was typed in.
     *
     * <h2>One place, because the alternative is three</h2>
     * Discord's dropdown only sends the declared form; both chat adapters type a choice as a plain
     * word and hand on whatever was typed. Normalising here is what lets a command compare against
     * its own constants without every one of them remembering to be lenient - and it is what keeps
     * {@code /phase set maintenance}, which has worked in chat since the proxy's hand-written
     * adapter, from being refused by the generic check that replaced it.
     *
     * <p>A value that is not one of the choices at all is left exactly as it was typed:
     * {@link NordtalCommand#check} is what refuses it, and it has to be able to quote it back.</p>
     */
    private static Map<String, Object> normalise(final Declaration declaration,
                                                 final Map<String, Object> values) {
        final Map<String, Object> normalised = new java.util.LinkedHashMap<>(values);
        for (final Argument argument : declaration.arguments()) {
            final Object supplied = normalised.get(argument.name());
            if (argument.kind() != Argument.Kind.CHOICE || supplied == null) {
                continue;
            }
            argument.match(String.valueOf(supplied))
                    .ifPresent(declared -> normalised.put(argument.name(), declared));
        }
        return Map.copyOf(normalised);
    }

    /** No arguments at all - {@code /smp reload}, {@code /hg start}. */
    public static Values none(final Declaration declaration) {
        return new Values(declaration, Map.of());
    }

    /** A {@link Argument.Kind#WORD}, {@link Argument.Kind#GREEDY_STRING} or {@link Argument.Kind#CHOICE}. */
    public String string(final String name) {
        return get(name, String.class);
    }

    /** An optional string argument, absent if it was not given. */
    public Optional<String> optionalString(final String name) {
        return Optional.ofNullable(values.get(name)).map(String.class::cast);
    }

    /** An {@link Argument.Kind#INTEGER}, already inside the bounds the declaration set. */
    public int integer(final String name) {
        return get(name, Integer.class);
    }

    /** A {@link Argument.Kind#PLAYER}, resolved to a UUID by the adapter. */
    public UUID player(final String name) {
        return get(name, UUID.class);
    }

    /**
     * An {@link Argument.Kind#ACCOUNT}, as a Discord id.
     *
     * <p>A string and not a {@code long}: Discord ids are snowflakes that exceed what a JSON number
     * can hold safely, they are compared and stored as text everywhere in this repository, and
     * {@code discord_user.discord_id} is a {@code varchar}.</p>
     */
    public String account(final String name) {
        return get(name, String.class);
    }

    /**
     * Whatever was supplied for this argument, untyped - or empty if nothing was.
     *
     * <p>For the one caller that has to walk a command's arguments without knowing what they are:
     * {@link eu.nordtal.s2.commands.remote.RequestArguments}, writing them onto a request row. Every
     * other reader knows which argument it wants and what kind it is, and should keep using the
     * typed accessors, which fail loudly rather than handing back an {@code Object}.</p>
     */
    public Optional<Object> raw(final String name) {
        return Optional.ofNullable(values.get(name));
    }

    private <T> T get(final String name, final Class<T> type) {
        final Object value = values.get(name);
        if (value == null) {
            throw new IllegalStateException(declaration.name() + " asked for argument '" + name
                    + "', which its declaration does not carry as a supplied value - the command and"
                    + " its declaration disagree");
        }
        if (!type.isInstance(value)) {
            throw new IllegalStateException(declaration.name() + ": argument '" + name + "' is a "
                    + value.getClass().getSimpleName() + " and was read as a " + type.getSimpleName()
                    + " - the adapter parsed it as a different kind than the command expects");
        }
        return type.cast(value);
    }
}
