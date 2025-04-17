package xyz.growaction.javacore.config.exception;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.growaction.javacore.config.JsonConfig;

public class ConfigInitializationException extends ConfigException {
    public ConfigInitializationException(final @NotNull Class<? extends JsonConfig> configClass, final @Nullable Throwable cause) {
        super(
                String.format("Error initializing default instance of config class [%s]", configClass.getName()),
                cause
        );
    }
}
