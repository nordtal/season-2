package eu.nordtal.season.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SetupTest {

    @TempDir
    Path root;

    private final ByteArrayOutputStream printed = new ByteArrayOutputStream();

    private Setup setup(final String typed) {
        final Terminal terminal = new Terminal(
                new BufferedReader(new StringReader(typed)),
                new PrintStream(printed, true, StandardCharsets.UTF_8),
                null);
        return new Setup(root, new LocalProject(root, new Processes(root), terminal), terminal);
    }

    private EnvFile env() throws IOException {
        final Path file = root.resolve(LocalProject.ENV_FILE);
        Files.createDirectories(file.getParent());
        Files.writeString(
                file,
                "NORDTAL_ACCESS_GUILD_ID=0\nNORDTAL_SECRETS_DIR=./deploy/local/secrets\n",
                StandardCharsets.UTF_8);
        return new EnvFile(file);
    }

    private EnvFile secrets(final String service) {
        return new EnvFile(root.resolve("deploy/local/secrets").resolve(service).resolve("secrets.env"));
    }

    private static LocalQuestions.Question question(final String name) {
        return LocalQuestions.ALL.stream()
                .filter(question -> question.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void aShapeThatCannotBeRightIsAskedAgainAndNeverWritten() throws IOException {
        final EnvFile env = env();
        setup("<#123>\n123456789012345678\n").ask(env, question("NORDTAL_ACCESS_GUILD_ID"));
        assertEquals(Optional.of("123456789012345678"), env.value("NORDTAL_ACCESS_GUILD_ID"));
    }

    @Test
    void enterSkipsADiscordAnswerAndKeepsTheExamplesPlaceholder() throws IOException {
        final EnvFile env = env();
        setup("\n").ask(env, question("NORDTAL_ACCESS_GUILD_ID"));
        assertEquals(Optional.of("0"), env.value("NORDTAL_ACCESS_GUILD_ID"));
    }

    @Test
    void aSecretIsNeverPrintedBack() throws IOException {
        final EnvFile env = env();
        setup("very-secret-token\n").ask(env, question("NORDTAL_BOT_TOKEN"));
        assertEquals(Optional.of("very-secret-token"), secrets("discord-bot").value("NORDTAL_BOT_TOKEN"));
        assertFalse(printed.toString(StandardCharsets.UTF_8).contains("very-secret-token"));
    }

    @Test
    void aSecretOnlyOneServiceReadsGoesIntoThatServicesOwnFileUnderTheNameItReads() throws IOException {
        final EnvFile env = env();
        setup("client-secret\n").ask(env, question("STEWARD_DISCORD_CLIENT_SECRET"));
        assertEquals(Optional.empty(), env.value("STEWARD_DISCORD_CLIENT_SECRET"));
        final EnvFile own = secrets("steward");
        assertEquals(Optional.of("client-secret"), own.value("NORDTAL_STEWARD_WEB_DISCORD_CLIENT_SECRET"));
        if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            assertEquals(
                    "rw-r--r--",
                    PosixFilePermissions.toString(Files.getPosixFilePermissions(own.path())),
                    "the container runs as a uid of its own and could not read an owner-only file");
        }
    }

    @Test
    void anythingButAYesToTheLicenceStopsAndWritesNothing() throws IOException {
        final EnvFile env = env();
        assertThrows(Processes.Failure.class, () -> setup("ja\n").ask(env, question("EULA")));
        assertEquals(Optional.empty(), env.value("EULA"));
        setup("yes\n").ask(env, question("EULA"));
        assertEquals(Optional.of("true"), env.value("EULA"));
    }

    @Test
    void theAddressIsRequiredAndAnEndedInputSaysSo() throws IOException {
        final EnvFile env = env();
        assertThrows(Processes.Failure.class, () -> setup("").ask(env, question("STEWARD_PUBLIC_URL")));
        setup("\nhttp://localhost:5173\n").ask(env, question("STEWARD_PUBLIC_URL"));
        assertEquals(Optional.of("http://localhost:5173"), env.value("STEWARD_PUBLIC_URL"));
    }
}
