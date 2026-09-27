package eu.nordtal.s2.discordbot;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Both ways this bot cannot start are handled as settings, not crashes.
 *
 * This only checks the source, since the real path needs Discord to reject a real token.
 */
class StartupFailuresTest {

    @Test
    void aTokenDiscordRejectsIsCaughtExplainedAndBackedOff() throws IOException {
        final String main = Files.readString(
                repositoryRoot().resolve("discord-bot/src/main/java/eu/nordtal/s2/discordbot/AccessBot.java"),
                StandardCharsets.UTF_8);

        assertTrue(
                main.contains("catch (final net.dv8tion.jda.api.exceptions.InvalidTokenException"),
                "AccessBot.main no longer catches InvalidTokenException, so a wrong token is a raw"
                        + " stack trace in a loop again");
        assertTrue(
                main.contains("backOffThenExit()"),
                "nothing slows the restart loop down. Retrying a login Discord has already refused"
                        + " every eight seconds is what its rate limiter is for.");
        assertTrue(
                main.contains("Thread.currentThread().interrupt()"),
                "the back-off has to stay interruptible: a container that ignores SIGTERM for a"
                        + " minute is a worse problem than the one being solved");
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
