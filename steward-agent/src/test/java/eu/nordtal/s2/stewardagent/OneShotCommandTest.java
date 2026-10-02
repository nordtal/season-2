package eu.nordtal.s2.stewardagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The two compose command lines a run of another release rests on: the one-shot, and the migration it waits for. */
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

    /** Waited for, with its exit status as compose's own, and made through {@code up} so its container is current. */
    @Test
    void theMigrationIsWaitedForAndItsExitStatusIsComposes() {
        final List<String> command = compose.migrateCommand();

        assertEquals(
                List.of("up", "--no-deps", "--abort-on-container-exit", "--exit-code-from", "migrate", "migrate"),
                command.subList(command.indexOf("up"), command.size()));
        assertFalse(command.contains("--detach"), command.toString());
    }
}
