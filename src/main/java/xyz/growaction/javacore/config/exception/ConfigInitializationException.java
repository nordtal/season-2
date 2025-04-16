package xyz.growaction.javacore.config.exception;

import org.jetbrains.annotations.Nullable;
import xyz.growaction.javacore.config.JsonConfig;

import java.util.Optional;

public class ConfigInitializationException extends ConfigException {
    public ConfigInitializationException(final @Nullable Class<? extends JsonConfig> configClass, final @Nullable Throwable cause) {
        super(
                String.format(
                        "Error initializing default instance of config class [%s]",
                        Optional.ofNullable(configClass)
                                .map(Class::getName)
                                .orElse("null")
                ),
                cause
        );
    }
}
