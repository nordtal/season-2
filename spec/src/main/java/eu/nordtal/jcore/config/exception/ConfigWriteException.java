package eu.nordtal.jcore.config.exception;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/** Thrown when writing a config file back to disk fails. */
public class ConfigWriteException extends ConfigException {

    /**
     * @param configFile the config file that failed to write
     * @param specType the spec interface it was written for
     * @param cause the underlying failure, if any
     */
    public ConfigWriteException(final Path configFile, final Class<?> specType, final @Nullable Throwable cause) {
        super(String.format("Error writing config '%s' (%s)", configFile, specType.getName()), cause);
    }
}
