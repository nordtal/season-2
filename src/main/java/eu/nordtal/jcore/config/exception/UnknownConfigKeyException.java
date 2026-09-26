package eu.nordtal.jcore.config.exception;

import eu.nordtal.jcore.config.internal.UnknownKeyDetector.UnknownKey;
import java.nio.file.Path;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Thrown when a config file contains a setting that reads as a <i>mistyped</i> declared key -
 * one close enough to a declared key that the loader can name what was probably meant.
 * <p>
 * The old loader deleted such keys silently on the next save. A mistyped key therefore cost the
 * operator both the setting and any trace of it, and the application ran on a default for as
 * long as nobody noticed. Refusing to start costs one restart instead.
 * <p>
 * A key that resembles nothing declared does <b>not</b> land here. That is a setting the software
 * has removed, and there is nothing the operator meant by it any more: the loader drops it from
 * the file, says so in the log and leaves the old content in the {@code .bak}. Stopping a process
 * over a line that is already dead helps nobody.
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
                .append(unknownKeys.size() == 1 ? "a misspelled setting" : "misspelled settings")
                .append(':');
        for (UnknownKey key : unknownKeys) {
            message.append(System.lineSeparator()).append("  - ").append(key.describe());
        }
        message.append(System.lineSeparator())
                .append("Your file was left untouched. Fix or remove the line(s) above and start again.");
        return message.toString();
    }
}
