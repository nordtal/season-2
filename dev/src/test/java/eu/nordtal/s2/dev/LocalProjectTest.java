package eu.nordtal.s2.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalProjectTest {

    /** Where a second head of a {@code docker compose} line would begin. */
    private static final Pattern COMPOSE_LITERAL = Pattern.compile("\"compose\"");

    @Test
    void aCommandLineNamesTheProjectTheFileAndTheEnvironmentOfThisCheckout(@TempDir final Path root)
            throws IOException {
        Files.writeString(root.resolve("compose.yml"), "name: nordtal-s2\nservices: {}\n", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("deploy"));
        Files.writeString(root.resolve(LocalProject.ENV_FILE), "COMPOSE_PROJECT_NAME=laptop\n", StandardCharsets.UTF_8);
        final Terminal terminal = new Terminal(
                new BufferedReader(new StringReader("")),
                new PrintStream(PrintStream.nullOutputStream(), true, StandardCharsets.UTF_8),
                null);

        // The env file's name wins over compose.yml's, as in Compose itself, and it is passed, not left to Compose.
        assertEquals(
                List.of(
                        "docker",
                        "compose",
                        "--project-name",
                        "laptop",
                        "--project-directory",
                        root.toString(),
                        "--file",
                        root.resolve("compose.yml").toString(),
                        "--env-file",
                        root.resolve(LocalProject.ENV_FILE).toString(),
                        "ps"),
                new LocalProject(root, new Processes(root), terminal).command("ps"));
    }

    @Test
    void devWritesNoComposeCommandLineOfItsOwn() throws IOException {
        // steward-agent's Compose is the one place a line is headed; a second copy here would drift from it.
        final Path sources = Repository.root(Path.of("")).resolve("dev/src/main/java");
        try (Stream<Path> files = Files.walk(sources)) {
            final List<String> heading = files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> COMPOSE_LITERAL.matcher(read(file)).find())
                    .map(file -> sources.relativize(file).toString())
                    .toList();
            assertEquals(
                    List.of(),
                    heading,
                    "these build a docker compose line themselves instead of asking"
                            + " LocalProject, which asks steward-agent's Compose");
        }
    }

    private static String read(final Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
