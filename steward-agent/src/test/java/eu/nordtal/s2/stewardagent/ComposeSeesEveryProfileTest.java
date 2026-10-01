package eu.nordtal.s2.stewardagent;

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
 * Asking what image a service runs does not depend on which profiles are enabled.
 *
 * Otherwise a standby behind a disabled profile reads as "no image on this host".
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
        // A top-level flag after the subcommand is rejected by compose.
        assertTrue(
                profile < command.indexOf("config"),
                "--profile is a top-level flag and belongs before `config`: " + command);
    }

    @Test
    void theOrdinaryReadIsUnchanged() {
        // Every profile here would start a second proxy and limbo in a plain deployment.
        assertFalse(
                compose.configCommand(false).contains("--profile"),
                "the ordinary read must not enable the standby profile");
    }

    @Test
    void theStandbysAreBehindAProfile() throws IOException {
        // The tests above only matter while the standbys stay behind a profile.
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
