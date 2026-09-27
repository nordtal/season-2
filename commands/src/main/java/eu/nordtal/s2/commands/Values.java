package eu.nordtal.s2.commands;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The arguments a command was actually given, already parsed and checked against its {@link Declaration}.
 *
 * A missing required argument is a programming mistake, so the accessors throw; a player is already a UUID.
 */
public final class Values {

    private final Declaration declaration;
    private final Map<String, Object> values;

    public Values(final Declaration declaration, final Map<String, Object> values) {
        this.declaration = Objects.requireNonNull(declaration, "declaration");
        this.values = normalise(declaration, Objects.requireNonNull(values, "values"));
    }

    /** Returns the values with every {@link Argument.Kind#CHOICE} in its declared spelling; others stay as typed. */
    private static Map<String, Object> normalise(final Declaration declaration, final Map<String, Object> values) {
        final Map<String, Object> normalised = new java.util.LinkedHashMap<>(values);
        for (final Argument argument : declaration.arguments()) {
            final Object supplied = normalised.get(argument.name());
            if (argument.kind() != Argument.Kind.CHOICE || supplied == null) {
                continue;
            }
            argument.match(String.valueOf(supplied)).ifPresent(declared -> normalised.put(argument.name(), declared));
        }
        return Map.copyOf(normalised);
    }

    /** No arguments at all, as for {@code /smp reload}. */
    public static Values none(final Declaration declaration) {
        return new Values(declaration, Map.of());
    }

    /** A {@link Argument.Kind#WORD}, {@link Argument.Kind#GREEDY_STRING} or {@link Argument.Kind#CHOICE}. */
    public String string(final String name) {
        return get(name, String.class);
    }

    /** Returns an optional string argument, absent if it was not given. */
    public Optional<String> optionalString(final String name) {
        return Optional.ofNullable(values.get(name)).map(String.class::cast);
    }

    /** Returns an {@link Argument.Kind#INTEGER}, already inside the declared bounds. */
    public int integer(final String name) {
        return get(name, Integer.class);
    }

    /** Returns a {@link Argument.Kind#PLAYER}, resolved to a UUID by the adapter. */
    public UUID player(final String name) {
        return get(name, UUID.class);
    }

    /**
     * Returns an {@link Argument.Kind#ACCOUNT} as a Discord id, text because a snowflake exceeds a safe JSON number.
     */
    public String account(final String name) {
        return get(name, String.class);
    }

    /** Returns whatever was supplied for this argument, untyped, for {@code RequestArguments} alone. */
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
