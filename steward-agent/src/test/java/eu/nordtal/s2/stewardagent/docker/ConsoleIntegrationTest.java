package eu.nordtal.s2.stewardagent.docker;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.nordtal.s2.stewardagent.TestProject;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The console, end to end: {@code list} typed into a running SMP comes out of the server's own log.
 *
 * It types into a server, so it runs only against the scratch project {@code NORDTAL_TEST_PROJECT} names.
 */
class ConsoleIntegrationTest {

    private static final String SMP = "smp";

    private static String project;
    private static Docker docker;
    private static Console console;

    @BeforeAll
    static void connect() {
        assumeTrue(TestProject.acting().isPresent(), TestProject.VARIABLE + " names no scratch project - skipping");
        project = TestProject.acting().orElseThrow();
        final DockerSocket socket = new DockerSocket();
        assumeTrue(socket.isReachable(), "no docker socket - skipping");
        docker = new Docker(socket);
        console = new Console(docker, project, () -> Set.of(SMP));
    }

    @Test
    void listTypedIntoTheSmpComesBackOutOfTheSmpsLog() throws Exception {
        final Docker.Container smp = docker.containers(project).stream()
                .filter(container -> SMP.equals(container.service()) && container.isRunning())
                .findFirst()
                .orElse(null);
        assumeTrue(smp != null, "the SMP is not running on this host - skipping");

        final boolean multiplexed = !docker.inspect(smp.id()).tty();

        // A round trip must be proved by a line absent before it was sent, or a stale answer passes wrongly.
        final Instant sentAt = Instant.now().minusSeconds(1);
        console.send(SMP, "list", "ConsoleIntegrationTest");

        // Polling, not sleeping once: a healthy server answers under a second, a loaded one should not fail the test.
        final Instant giveUp = Instant.now().plus(Duration.ofSeconds(15));
        String found = null;
        while (found == null && Instant.now().isBefore(giveUp)) {
            for (final String line : docker.recentLines(smp.id(), 40, multiplexed)) {
                if (line.contains("There are") && line.contains("players online") && isAfter(line, sentAt)) {
                    found = line;
                    break;
                }
            }
            if (found == null) {
                Thread.sleep(500);
            }
        }
        assertTrue(found != null, "typed `list` into the SMP and its answer never appeared in the log");
    }

    /** Whether a log line was written after {@code instant}; an unreadable timestamp counts as old. */
    private static boolean isAfter(final String line, final Instant instant) {
        final int space = line.indexOf(' ');
        if (space <= 0) {
            return false;
        }
        try {
            return Instant.parse(line.substring(0, space)).isAfter(instant);
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }
}
