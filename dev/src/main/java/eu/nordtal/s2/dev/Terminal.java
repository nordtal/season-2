package eu.nordtal.s2.dev;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Where this program talks to a person: a real terminal, or IntelliJ's run console, which is a pipe.
 *
 * A secret is read without echo only on a real terminal; a run console has no way to hide what is typed.
 */
class Terminal {

    private final BufferedReader in;
    private final PrintStream out;
    private final @Nullable Console console;

    Terminal(final BufferedReader in, final PrintStream out, final @Nullable Console console) {
        this.in = in;
        this.out = out;
        this.console = console;
    }

    /** @return the process's own standard input and output */
    static Terminal system() {
        // Never null since JDK 22; isTerminal() is what tells a terminal from a pipe.
        final Console console = java.util.Objects.requireNonNull(System.console(), "System.console()");
        return new Terminal(
                new BufferedReader(new InputStreamReader(System.in, console.charset())),
                System.out,
                console.isTerminal() ? console : null);
    }

    /** @return whether there is a real terminal, which is what {@code docker compose exec} needs for a TTY */
    boolean isTerminal() {
        return console != null;
    }

    void log(final String message) {
        out.println("\u001b[36m[dev]\u001b[0m " + message);
    }

    void warn(final String message) {
        out.println("\u001b[33m[dev]\u001b[0m " + message);
    }

    void print(final String text) {
        out.print(text);
        out.flush();
    }

    /** @return the next line, or empty at the end of the input */
    Optional<String> readLine() {
        try {
            return Optional.ofNullable(in.readLine());
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read the answer", e);
        }
    }

    /** @return the next line without echo where that is possible, or empty at the end of the input */
    Optional<String> readSecret() {
        if (console != null) {
            final char[] typed = console.readPassword();
            return typed == null ? Optional.empty() : Optional.of(new String(typed));
        }
        return readLine();
    }
}
