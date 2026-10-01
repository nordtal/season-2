package eu.nordtal.s2.steward.deployer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Recreate uses the image that is here; that the route never pulls is {@code :architecture}'s rule. */
class RecreateDoesNotPullTest {

    private final Compose compose =
            new Compose(Path.of("/app/compose.yml"), Path.of("/does/not/exist/.env"), Path.of("/app"), "nordtal-s2");

    @Test
    void theRecreateCommandLineFetchesNothingByAnyOfComposesSpellings() {
        final List<String> command = compose.recreateCommand("steward-ui");

        assertTrue(command.containsAll(List.of("up", "--detach", "--no-deps", "--force-recreate")), command.toString());
        assertTrue(command.contains("steward-ui"), command.toString());
        // `docker compose up` fetches through `--pull always` too, so every token is checked.
        for (final String token : command) {
            assertFalse(token.contains("pull"), "recreate must not fetch, and this does: " + command);
        }
    }
}
