package eu.nordtal.jcore.config.exception;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import eu.nordtal.jcore.config.JsonConfig;

public class ConfigInitializationException extends ConfigException {
    public ConfigInitializationException(final @NotNull Class<? extends JsonConfig> configClass, final @Nullable Throwable cause) {
        super(
                String.format("Error initializing default instance of config class [%s]", configClass.getName()),
                cause
        );
    }
}
