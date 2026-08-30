package eu.nordtal.jcore.config.exception;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Thrown when a configuration cannot be loaded, written or trusted.
 * <p>
 * This is a checked exception on purpose. Loading a configuration is a startup step that can
 * fail in ways the operator has to fix, and a caller has to make a conscious decision about it.
 * The season 1 bot caught this and carried on with defaults, which is exactly the outcome the
 * checked type is meant to make the caller think about.
 *
 */
public class ConfigException extends Exception {

    /**
     * Creates a new {@link ConfigException}
     *
     * @param message the message of the error
     * @param cause   the {@link Throwable} that caused this {@link Exception}
     */
    public ConfigException(final @NotNull String message, final @Nullable Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates a new {@link ConfigException} without a cause.
     *
     * @param message the message of the error
     */
    public ConfigException(final @NotNull String message) {
        super(message);
    }
}
