package eu.nordtal.s2.steward.worker.configfile;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * The file was written by somebody else since this save's form was drawn.
 *
 * <p>Its own type rather than an {@link IllegalArgumentException} because the answer it deserves is
 * a different one: nobody made a mistake, and nothing about the change needs correcting. Somebody
 * was simply faster, and the only useful thing to do is show the file as it now stands and let the
 * operator decide whether their change is still the one they want.</p>
 */
public final class StaleConfigException extends RuntimeException {

    private final transient Path file;
    private final String expected;
    private final String actual;

    StaleConfigException(final @NotNull Path file, final @NotNull String expected,
                         final @NotNull String actual) {
        super(file + " has been written since it was read (" + expected + " -> " + actual + ")");
        this.file = file;
        this.expected = expected;
        this.actual = actual;
    }

    public @NotNull Path file() {
        return file;
    }

    /** What the caller thought the file said. */
    public @NotNull String expected() {
        return expected;
    }

    /** What it actually says now. */
    public @NotNull String actual() {
        return actual;
    }
}
