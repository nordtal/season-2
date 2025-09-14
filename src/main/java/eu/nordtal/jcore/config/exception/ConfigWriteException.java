package eu.nordtal.jcore.config.exception;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import eu.nordtal.jcore.config.JsonConfig;

import java.io.File;

public class ConfigWriteException extends ConfigException {
    public ConfigWriteException(final @NotNull File configFile, final @NotNull Class<? extends JsonConfig> configClass, final @Nullable Throwable cause) {
        super(
                String.format("Error writing instance of config class [%s] to file '%s'", configClass.getName(), configFile.getName()),
                cause
        );
    }
}
