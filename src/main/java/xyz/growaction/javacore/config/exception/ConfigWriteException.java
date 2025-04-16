package xyz.growaction.javacore.config.exception;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.growaction.javacore.config.JsonConfig;

public class ConfigWriteException extends ConfigException{
    public ConfigWriteException(final @Nullable Class<? extends JsonConfig> configClass, final @Nullable Throwable cause) {
        super("", cause);
    }
}
