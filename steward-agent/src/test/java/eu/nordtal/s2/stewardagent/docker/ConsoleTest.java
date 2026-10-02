package eu.nordtal.s2.stewardagent.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The console against a stand-in daemon: who has one comes from compose.yml, and the line goes to {@code mc}. */
class ConsoleTest {

    @TempDir
    Path scratch;

    @Test
    void aServiceWithoutAConsoleIsRefusedAndToldWhichOnesHaveOne() throws IOException {
        try (FakeDaemon daemon = new FakeDaemon(scratch)) {
            final IllegalArgumentException refused = assertThrows(
                    IllegalArgumentException.class, () -> console(daemon).send("postgres", "list", "tester"));
            assertEquals("postgres has no console. The services with one are proxy, smp.", refused.getMessage());
            assertTrue(daemon.execs.isEmpty(), "a refused line reached the daemon");
        }
    }

    @Test
    void aLineReachesTheServerThroughMcAsOneArgument() throws IOException {
        try (FakeDaemon daemon = new FakeDaemon(scratch)) {
            console(daemon).send("smp", "say hello; rm -rf /", "tester");
            assertEquals(1, daemon.execs.size());
            assertTrue(
                    daemon.execs.getFirst().contains("[\"mc\",\"say hello; rm -rf /\"]"),
                    "the line should be one argument of mc, never a shell: " + daemon.execs.getFirst());
        }
    }

    @Test
    void anEmptyLineIsNotACommand() throws IOException {
        try (FakeDaemon daemon = new FakeDaemon(scratch)) {
            assertThrows(IllegalArgumentException.class, () -> console(daemon).send("smp", "  ", "tester"));
            assertTrue(daemon.execs.isEmpty());
        }
    }

    private static Console console(final FakeDaemon daemon) {
        return new Console(
                new Docker(new DockerSocket(daemon.socket(), Duration.ofSeconds(5))),
                FakeDaemon.PROJECT,
                () -> Set.of("smp", "proxy"));
    }
}
