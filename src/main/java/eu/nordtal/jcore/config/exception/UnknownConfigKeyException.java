package eu.nordtal.jcore.config.exception;

import eu.nordtal.jcore.config.internal.UnknownKeyDetector.UnknownKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Path;
import java.util.List;

/**
 * Thrown when a config file contains a setting the spec does not declare.
 * <p>
 * The old loader deleted such keys silently on the next save. A mistyped key therefore cost the
 * operator both the setting and any trace of it, and the application ran on a default for as
 * long as nobody noticed. Refusing to start costs one restart instead.
 */
public class UnknownConfigKeyException extends ConfigException {

    private final @Unmodifiable List<UnknownKey> unknownKeys;

    public UnknownConfigKeyException(final @NotNull Path file, final @NotNull List<UnknownKey> unknownKeys) {
        super(buildMessage(file, unknownKeys));
        this.unknownKeys = List.copyOf(unknownKeys);
    }

    /** The offending keys, with their full paths and suggestions. */
    public @NotNull @Unmodifiable List<UnknownKey> unknownKeys() {
        return unknownKeys;
    }

    private static String buildMessage(final Path file, final List<UnknownKey> unknownKeys) {
        final StringBuilder message = new StringBuilder(file.toString())
                .append(" contains ")
                .append(unknownKeys.size() == 1 ? "a setting that does not exist" : "settings that do not exist")
                .append(':');
        for (UnknownKey key : unknownKeys) {
            message.append(System.lineSeparator()).append("  - ").append(key.describe());
        }
        message.append(System.lineSeparator())
                .append("Your file was left untouched. Fix or remove the line(s) above and start again.");
        return message.toString();
    }
}
