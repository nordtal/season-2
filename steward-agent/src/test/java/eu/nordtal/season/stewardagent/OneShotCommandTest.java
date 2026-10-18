package eu.nordtal.season.stewardagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests the two compose command lines of a run of another release: the one-shot and the migration it waits for.
 */
class OneShotCommandTest {

    private final Compose compose =
            new Compose(Path.of("/app/compose.yml"), Path.of("/does/not/exist/.env"), Path.of("/app"), "nordtal-s2");

    /** Named, so the row can say who carries it out; removed once it exits, so a second run can take the name. */
    @Test
    void theOneShotIsANamedOneOffOfTheAgentThatRemovesItself() {
        final List<String> command = compose.oneShotCommand(42);

        assertEquals(
                List.of(
                        "run",
                        "--detach",
                        "--rm",
                        "--no-deps",
                        "--name",
                        "nordtal-s2-steward-agent-run",
                        "steward-agent",
                        "run",
                        "42"),
                command.subList(command.indexOf("run"), command.size()));
    }

    /**
     * The command waits for the migration, returns its exit status and runs through {@code up}, so it is current.
     */
    @Test
    void theMigrationIsWaitedForAndItsExitStatusIsComposes() {
        final List<String> command = compose.migrateCommand();

        assertEquals(
                List.of("up", "--no-deps", "--abort-on-container-exit", "--exit-code-from", "migrate", "migrate"),
                command.subList(command.indexOf("up"), command.size()));
        assertFalse(command.contains("--detach"), command.toString());
    }
}
