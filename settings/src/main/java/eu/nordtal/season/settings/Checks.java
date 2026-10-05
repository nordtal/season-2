package eu.nordtal.season.settings;

import org.jspecify.annotations.Nullable;

/** The rules every group of settings shares, each naming the key it refuses. */
public final class Checks {

    private Checks() {}

    /**
     * Refuses an empty or blank value.
     *
     * @throws IllegalArgumentException if {@code value} is null or blank
     */
    public static void requireText(final String key, final @Nullable String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " must not be empty");
        }
    }

    /**
     * Refuses zero and anything below it.
     *
     * @throws IllegalArgumentException if {@code value} is not greater than zero
     */
    public static void requirePositive(final String key, final long value) {
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be greater than zero, was " + value);
        }
    }

    /**
     * Refuses zero and anything below it, for a value with a fraction.
     *
     * @throws IllegalArgumentException if {@code value} is not greater than zero
     */
    public static void requirePositive(final String key, final double value) {
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be greater than zero, was " + value);
        }
    }

    /**
     * Refuses an empty secret, naming the environment variable it belongs in.
     *
     * @throws IllegalArgumentException if {@code value} is null or blank
     */
    public static void requireSecret(final String key, final String variable, final @Nullable String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is empty. Set " + variable + " in the environment.");
        }
    }
}
