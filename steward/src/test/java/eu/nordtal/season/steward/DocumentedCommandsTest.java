package eu.nordtal.season.steward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.nordtal.season.common.ComposeFile;
import eu.nordtal.season.common.RepositoryRoot;
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
 * Every documented {@code docker compose run --rm steward} names what it does.
 *
 * A bare {@code run} starts a second {@code serve} daemon, so no document may present it as a harmless report.
 */
class DocumentedCommandsTest {

    /** Everywhere this command is written down for a person to copy. */
    private static final List<String> DOCUMENTS = List.of(
            "compose.yml",
            ".env.example",
            "deploy/jvm/Dockerfile",
            "deploy/jvm/entrypoint.sh",
            "steward/README.md",
            "deploy/README.md");

    /** What {@link Steward} actually dispatches on. Anything else is refused. */
    private static final List<String> SUBCOMMANDS = List.of("serve", "forget-factors", "generate-vapid-keys");

    private static final Pattern INVOCATION = Pattern.compile("docker compose run (?:--rm )?steward(?<rest>[^\\n`]*)");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    @Test
    void noDocumentTellsAnybodyToRunStewardWithoutNamingASubcommand() throws IOException {
        final List<String> bare = new ArrayList<>();

        for (final String relative : DOCUMENTS) {
            final Path document = RepositoryRoot.path().resolve(relative);
            assertTrue(Files.isRegularFile(document), document + " is not where this test expects it");

            final String text = Files.readString(document, StandardCharsets.UTF_8);
            final Matcher matcher = INVOCATION.matcher(text);
            while (matcher.find()) {
                final String rest = matcher.group("rest").strip();
                // A shell script closes its quote right after the word: `... steward bootstrap" >&2`.
                final String first = rest.isEmpty()
                        ? ""
                        : WHITESPACE.splitAsStream(rest).findFirst().orElse("").replaceAll("[\"']+$", "");
                if (!SUBCOMMANDS.contains(first)) {
                    bare.add(relative + ": \"" + matcher.group().strip() + "\"");
                }
            }
        }

        if (!bare.isEmpty()) {
            fail("a `docker compose run` with no subcommand does NOT reach the read-only default -"
                    + " Compose hands it the service's `command` (serve), or the image's CMD when"
                    + " the service names none. Name one of " + SUBCOMMANDS + ":\n" + String.join("\n", bare));
        }
    }

    @Test
    void theStewardServiceStillRunsServeWhichIsTheWholeReasonARunInheritsIt() {
        assertEquals(
                List.of("serve"),
                ComposeFile.get().service("steward").list("command"),
                "compose.yml no longer starts the steward service with `serve`. `docker compose up"
                        + " -d` would then run whatever the image defaults to, and nothing would be"
                        + " listening for update requests.");
    }
}
