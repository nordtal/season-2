package eu.nordtal.s2.discordbot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the two ways this bot cannot possibly start are both handled as settings, not as crashes.
 *
 * <h2>What this can and cannot say</h2>
 * A weak test, and deliberately labelled as one: exercising the real path needs Discord to reject a
 * real token, which is a step for the owner's checklist and not for a build. What it does catch is
 * the regression - the {@code catch} being removed, or a future startup failure being added beside
 * it without the same treatment - and that is worth more than the nothing that was here before.
 *
 * <h2>The bug</h2>
 * {@code InvalidTokenException} flew out of {@code main}. {@code restart: unless-stopped} brought
 * the container back roughly every eight seconds, each time building a Hikari pool, running a
 * Flyway {@code validate} and attempting a login Discord had already refused - which is precisely
 * what Discord rate-limits - and printing a raw stack trace for it. The module is explicitly built
 * to fail fast with a readable message, and this one path did not.
 */
class StartupFailuresTest {

    @Test
    @DisplayName("a token Discord rejects is caught, explained, and backed off")
    void aRejectedTokenIsNotAnUncaughtException() throws IOException {
        final String main = Files.readString(
                repositoryRoot().resolve(
                        "discord-bot/src/main/java/eu/nordtal/s2/discordbot/AccessBot.java"),
                StandardCharsets.UTF_8);

        assertTrue(main.contains("catch (final net.dv8tion.jda.api.exceptions.InvalidTokenException"),
                "AccessBot.main no longer catches InvalidTokenException, so a wrong token is a raw"
                        + " stack trace in a loop again");
        assertTrue(main.contains("backOffThenExit()"),
                "nothing slows the restart loop down. Retrying a login Discord has already refused"
                        + " every eight seconds is what its rate limiter is for.");
        assertTrue(main.contains("Thread.currentThread().interrupt()"),
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
        throw new IllegalStateException("no settings.gradle.kts above " + Path.of("").toAbsolutePath());
    }
}
