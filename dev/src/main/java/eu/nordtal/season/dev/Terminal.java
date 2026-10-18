package eu.nordtal.season.dev;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The place where this program talks to a person: a real terminal, or the run console of IntelliJ, which is a pipe.
 *
 * Only a real terminal reads a secret without echo.
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

    // JDK 25 returns null when stdin is a pipe, as in the Run window of IntelliJ.
    @SuppressWarnings("SystemConsoleNull")
    static Terminal system() {
        final Console console = System.console();
        final Charset charset = console == null ? Charset.defaultCharset() : console.charset();
        return new Terminal(
                new BufferedReader(new InputStreamReader(System.in, charset)),
                System.out,
                console != null && console.isTerminal() ? console : null);
    }

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

    Optional<String> readLine() {
        try {
            return Optional.ofNullable(in.readLine());
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read the answer", e);
        }
    }

    Optional<String> readSecret() {
        if (console != null) {
            final char[] typed = console.readPassword();
            return typed == null ? Optional.empty() : Optional.of(new String(typed));
        }
        return readLine();
    }
}
