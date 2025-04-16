package xyz.growaction.javacore.config.exception;

import org.jetbrains.annotations.Nullable;
import xyz.growaction.javacore.config.JsonConfig;

import java.util.Optional;

public class ConfigWriteException extends ConfigException {
    public ConfigWriteException(final @Nullable Class<? extends JsonConfig> configClass, final @Nullable Throwable cause) {
        super(
                String.format(
                        "Error writing instance of config class [%s] to file",
                        Optional.ofNullable(configClass)
                                .map(Class::getName)
                                .orElse("null")
                ),
                cause
        );
    }
}
