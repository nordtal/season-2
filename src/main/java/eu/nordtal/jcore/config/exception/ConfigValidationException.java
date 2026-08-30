package eu.nordtal.jcore.config.exception;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * Thrown when a config file parses but the values in it do not make sense - a negative interval,
 * an empty channel id. Catching this at load time is the point: the alternative is finding out
 * during operation, at whatever hour the bad value first matters.
 */
public class ConfigValidationException extends ConfigException {

    public ConfigValidationException(final @NotNull Path file, final @NotNull String reason,
                                     final @Nullable Throwable cause) {
        super(file + " is not valid: " + reason, cause);
    }
}
