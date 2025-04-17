package xyz.growaction.javacore.config.exception;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ConfigReadException extends ConfigException {
    public ConfigReadException(final @NotNull String message, final @Nullable Throwable cause) {
        super(
                message,
                cause
        );
    }
}
