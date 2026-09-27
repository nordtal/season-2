package eu.nordtal.s2.steward.worker.docker;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.nordtal.s2.steward.worker.plan.Topology;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The console, end to end: {@code list} typed into the live SMP comes out of the server's own log. */
class ConsoleIntegrationTest {

    private static final String PROJECT = "nordtal-s2";

    private static Docker docker;
    private static Console console;

    @BeforeAll
    static void connect() {
        final DockerSocket socket = new DockerSocket();
        assumeTrue(socket.isReachable(), "no docker socket - skipping");
        docker = new Docker(socket);
        console = new Console(docker, PROJECT);
    }

    @Test
    void aServiceWithoutAConsoleIsRefusedAndToldWhatItHasInstead() {
        for (final String service : List.of(Topology.DISCORD_BOT, "postgres", "caddy", Topology.STEWARD_WORKER)) {
            final IllegalArgumentException refused =
                    assertThrows(IllegalArgumentException.class, () -> console.send(service, "list"));
            assertTrue(
                    refused.getMessage().startsWith(service),
                    "the refusal should name the service first: " + refused.getMessage());
            assertTrue(
                    refused.getMessage().length() > service.length() + 20,
                    "a refusal with no reason in it is a wall: " + refused.getMessage());
        }
    }

    @Test
    void listTypedIntoTheSmpComesBackOutOfTheSmpsLog() throws Exception {
        final Docker.Container smp = docker.containers(PROJECT).stream()
                .filter(container -> Topology.SMP.equals(container.service()) && container.isRunning())
                .findFirst()
                .orElse(null);
        assumeTrue(smp != null, "the SMP is not running on this host - skipping");

        final boolean multiplexed = !docker.inspect(smp.id()).tty();

        // A round trip must be proved by a line absent before it was sent, or a stale answer passes wrongly.
        final Instant sentAt = Instant.now().minusSeconds(1);
        console.send(Topology.SMP, "list");

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

    @Test
    void anEmptyLineIsNotACommand() {
        assertThrows(IllegalArgumentException.class, () -> console.send(Topology.SMP, "  "));
    }
}
