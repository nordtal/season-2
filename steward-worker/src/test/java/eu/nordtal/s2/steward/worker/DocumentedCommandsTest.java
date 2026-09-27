package eu.nordtal.s2.steward.worker;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * That every {@code docker compose run --rm steward-worker} written down anywhere names what it does.
 *
 * Guards the documented command against reading as a harmless, no-op report when it is not.
 *
 * {@code compose.yml}, {@code steward-worker/Dockerfile}, {@code steward-worker/README.md} and
 * {@code deploy/README.md} must never document the bare command as printing a report and changing nothing. Compose
 * hands a {@code run} that names no command the service's own {@code command}, {@code serve}: a second
 * long-running daemon that migrates, bootstraps, listens on {@code nordtal_update} and never returns the terminal.
 * Removing {@code command} does not help either, since a bare {@code run} then inherits the image's {@code CMD}.
 *
 * The test reads the documents rather than the dispatch: {@link StewardWorker}'s own dispatch is correct -
 * {@code report} is what an argument-less run does - and a test of that dispatch alone cannot see five files
 * telling a person to type something that does the opposite of what they say.
 */
class DocumentedCommandsTest {

    /** Everywhere this command is written down for a person to copy. */
    private static final List<String> DOCUMENTS = List.of(
            "compose.yml", ".env.example", "steward-worker/Dockerfile", "steward-worker/README.md", "deploy/README.md");

    /** What {@link StewardWorker} actually dispatches on. Anything else reads as the default. */
    // "apply" is deliberately absent, with the button that did the same: it would tell somebody to swap jars live.
    private static final List<String> SUBCOMMANDS = List.of("report", "migrate", "bootstrap", "serve");

    private static final Pattern INVOCATION =
            Pattern.compile("docker compose run (?:--rm )?steward-worker(?<rest>[^\\n`]*)");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    @Test
    void noDocumentTellsAnybodyToRunTheWorkerWithoutNamingASubcommand() throws IOException {
        final List<String> bare = new ArrayList<>();

        for (final String relative : DOCUMENTS) {
            final Path document = repositoryRoot().resolve(relative);
            assertTrue(Files.isRegularFile(document), document + " is not where this test expects it");

            final String text = Files.readString(document, StandardCharsets.UTF_8);
            final Matcher matcher = INVOCATION.matcher(text);
            while (matcher.find()) {
                final String rest = matcher.group("rest").strip();
                final String first = rest.isEmpty()
                        ? ""
                        : WHITESPACE.splitAsStream(rest).findFirst().orElse("");
                if (!SUBCOMMANDS.contains(first)) {
                    bare.add(relative + ": \"" + matcher.group().strip() + "\"");
                }
            }
        }

        if (!bare.isEmpty()) {
            fail("a `docker compose run` with no subcommand does NOT reach the read-only default -"
                    + " Compose hands it the service's `command` (serve), or the image's CMD when"
                    + " the service names none. Write `steward-worker report`:\n" + String.join("\n", bare));
        }
    }

    @Test
    void theWorkerServiceStillRunsServeWhichIsTheWholeReasonARunInheritsIt() throws IOException {
        final String compose = Files.readString(repositoryRoot().resolve("compose.yml"), StandardCharsets.UTF_8);
        assertTrue(
                compose.contains("command: [\"serve\"]"),
                "compose.yml no longer starts the steward-worker service with `serve`. `docker compose up"
                        + " -d` would then run whatever the image defaults to, and nothing would be"
                        + " listening for update requests.");
    }

    /** The directory holding {@code settings.gradle.kts}, not the nearest file by name. */
    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                "no settings.gradle.kts above " + Path.of("").toAbsolutePath());
    }
}
