package eu.nordtal.jcore.config.exception;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

public class ConfigWriteException extends ConfigException {

    public ConfigWriteException(final @NotNull Path configFile, final @NotNull Class<?> specType,
                                final @Nullable Throwable cause) {
        super(String.format("Error writing config '%s' (%s)", configFile, specType.getName()), cause);
    }
}
