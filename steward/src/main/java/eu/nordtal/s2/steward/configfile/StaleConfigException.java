package eu.nordtal.s2.steward.configfile;

import java.nio.file.Path;

/**
 * The file was written by somebody else since this save's form was drawn.
 *
 * Its own type, since nobody made a mistake: the answer is to show the file as it now stands.
 */
public final class StaleConfigException extends RuntimeException {

    private final transient Path file;
    private final String expected;
    private final String actual;

    StaleConfigException(final Path file, final String expected, final String actual) {
        super(file + " has been written since it was read (" + expected + " -> " + actual + ")");
        this.file = file;
        this.expected = expected;
        this.actual = actual;
    }

    public Path file() {
        return file;
    }

    /** What the caller thought the file said. */
    public String expected() {
        return expected;
    }

    /** What it actually says now. */
    public String actual() {
        return actual;
    }
}
