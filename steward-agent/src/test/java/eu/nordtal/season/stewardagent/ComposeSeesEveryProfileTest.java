package eu.nordtal.season.stewardagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.ComposeFile;
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
    void theStandbysAreBehindAProfile() {
        // The tests above only matter while the standbys stay behind a profile.
        for (final String standby : List.of("proxy-standby", "limbo-standby")) {
            assertEquals(
                    List.of("standby"),
                    ComposeFile.get().service(standby).profiles(),
                    standby + " is no longer in the standby profile alone");
        }
    }
}
