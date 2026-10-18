package eu.nordtal.season.settings;

import org.jspecify.annotations.Nullable;

public final class Checks {

    private Checks() {}

    public static void requireText(final String key, final @Nullable String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " must not be empty");
        }
    }

    public static void requirePositive(final String key, final long value) {
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be greater than zero, was " + value);
        }
    }

    public static void requirePositive(final String key, final double value) {
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be greater than zero, was " + value);
        }
    }

    public static void requireSecret(final String key, final String variable, final @Nullable String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is empty. Set " + variable + " in the environment.");
        }
    }
}
