package eu.nordtal.jcore.config.exception;

import org.jspecify.annotations.Nullable;

/** Thrown when reading a config file from disk fails. */
public class ConfigReadException extends ConfigException {

    /**
     * @param message what went wrong
     * @param cause the underlying failure, if any
     */
    public ConfigReadException(final String message, final @Nullable Throwable cause) {
        super(message, cause);
    }
}
