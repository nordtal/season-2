package eu.nordtal.s2.updater;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * That every {@code docker compose run --rm updater} written down anywhere names what it does.
 *
 * <h2>The bug this exists for</h2>
 * {@code compose.yml}, {@code updater/Dockerfile}, {@code updater/README.md}, {@code docs/updater.md}
 * and {@code deploy/README.md} all documented the bare command as the harmless read-only report -
 * <em>"prints what is installed and changes nothing"</em>. It is not. Compose hands a {@code run}
 * that names no command the service's own {@code command}, which is {@code serve}: the operator got
 * a second long-running daemon that migrated, ran the bootstrap, started listening on
 * {@code nordtal_update} and never returned the terminal. Removing {@code command} from the service
 * does not fix it either - a {@code run} then inherits the image's {@code CMD}. Both measured
 * 2026-09-02.
 *
 * <h2>Why the test is on the documents</h2>
 * Because the defect was in the documents. The dispatch in {@link UpdaterMain} was correct the whole
 * time - {@code report} is what an argument-less run does - and no test of that dispatch would have
 * noticed, because nothing that ran it ever went through Compose. What was wrong was five files
 * telling a person to type something that did the opposite of what they said, and the only way to
 * catch that again is to read them.
 */
class DocumentedCommandsTest {

    /** Everywhere this command is written down for a person to copy. */
    private static final List<String> DOCUMENTS = List.of(
            "compose.yml",
            ".env.example",
            "updater/Dockerfile",
            "updater/README.md",
            "docs/updater.md",
            "deploy/README.md");

    /** What {@link UpdaterMain} actually dispatches on. Anything else reads as the default. */
    private static final List<String> SUBCOMMANDS = List.of("report", "migrate", "apply", "serve");

    private static final Pattern INVOCATION =
            Pattern.compile("docker compose run (?:--rm )?updater(?<rest>[^\\n`]*)");

    @Test
    @DisplayName("no document tells anybody to run the updater without naming a subcommand")
    void everyDocumentedRunNamesItsSubcommand() throws IOException {
        final List<String> bare = new ArrayList<>();

        for (final String relative : DOCUMENTS) {
            final Path document = repositoryRoot().resolve(relative);
            assertTrue(Files.isRegularFile(document), document + " is not where this test expects it");

            final String text = Files.readString(document, StandardCharsets.UTF_8);
            final Matcher matcher = INVOCATION.matcher(text);
            while (matcher.find()) {
                final String rest = matcher.group("rest").strip();
                final String first = rest.isEmpty() ? "" : rest.split("\\s+")[0];
                if (!SUBCOMMANDS.contains(first)) {
                    bare.add(relative + ": \"" + matcher.group().strip() + "\"");
                }
            }
        }

        if (!bare.isEmpty()) {
            fail("a `docker compose run` with no subcommand does NOT reach the read-only default -"
                    + " Compose hands it the service's `command` (serve), or the image's CMD when"
                    + " the service names none. Write `updater report`:\n" + String.join("\n", bare));
        }
    }

    @Test
    @DisplayName("the updater service still runs serve, which is the whole reason a run inherits it")
    void theServiceItselfStillServes() throws IOException {
        final String compose = Files.readString(repositoryRoot().resolve("compose.yml"),
                StandardCharsets.UTF_8);
        assertTrue(compose.contains("command: [\"serve\"]"),
                "compose.yml no longer starts the updater service with `serve`. `docker compose up"
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
        throw new IllegalStateException("no settings.gradle.kts above " + Path.of("").toAbsolutePath());
    }
}
