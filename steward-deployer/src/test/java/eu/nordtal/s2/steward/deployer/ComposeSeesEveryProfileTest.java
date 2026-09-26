package eu.nordtal.s2.steward.deployer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Asking what image a service runs must not depend on which profiles happen to be enabled.
 *
 * <p>A recreate that reads {@code docker compose config} under only the active
 * {@code COMPOSE_PROFILES} sees "not in this selection" for a service such as {@code proxy-standby}
 * that sits behind a profile nobody enabled, which reads the same as "no image on this host" and
 * aborts for a reason that is not true.</p>
 *
 * <p>The tests need no docker daemon: the fix is one argument in a command line, and the command
 * line is assembled before anything runs.</p>
 */
class ComposeSeesEveryProfileTest {

    private final Compose compose =
            new Compose(Path.of("/app/compose.yml"), Path.of("/does/not/exist/.env"), Path.of("/app"), "nordtal-s2");

    @Test
    void everyProfileIsEnabledForTheRead() {
        final List<String> command = compose.configCommand(true);

        final int profile = command.indexOf("--profile");
        assertTrue(profile >= 0, "no --profile in " + command);
        assertEquals("*", command.get(profile + 1), "--profile has to name every one of them");
        // A top-level flag after the subcommand is an argument to the subcommand, and compose
        // rejects it. The position is the whole of whether this works.
        assertTrue(
                profile < command.indexOf("config"),
                "--profile is a top-level flag and belongs before `config`: " + command);
    }

    @Test
    void theOrdinaryReadIsUnchanged() {
        // services() is what a deployment naming nothing touches, and what the interface lists.
        // Enabling every profile here would put a second proxy and a second limbo into both.
        assertFalse(
                compose.configCommand(false).contains("--profile"),
                "the ordinary read must not enable the standby profile");
    }

    @Test
    void theStandbysAreBehindAProfile() throws IOException {
        // Without this the two tests above guard a problem that no longer exists: put the standbys
        // into `mc` and the missing --profile would stop being a defect, silently.
        final String composeFile = Files.readString(repositoryRoot().resolve("compose.yml"), StandardCharsets.UTF_8);

        for (final String standby : List.of("proxy-standby", "limbo-standby")) {
            final int at = composeFile.indexOf("\n  " + standby + ":");
            assertTrue(at > 0, "compose.yml no longer declares " + standby);
            assertTrue(
                    composeFile.indexOf("profiles: [\"standby\"]", at) > 0,
                    standby + " is no longer in the standby profile");
        }
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        assertTrue(candidate != null, "no settings.gradle.kts above the working directory");
        return candidate;
    }
}
