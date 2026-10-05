package eu.nordtal.jcore.config.exception;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Thrown when a config file parses but the values in it do not make sense, such as a negative interval.
 */
public class ConfigValidationException extends ConfigException {

    public ConfigValidationException(final Path file, final String reason, final @Nullable Throwable cause) {
        super(file + " is not valid: " + reason, cause);
    }
}
