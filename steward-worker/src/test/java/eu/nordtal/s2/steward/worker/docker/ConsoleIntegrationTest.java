package eu.nordtal.s2.steward.worker.docker;

import eu.nordtal.s2.steward.worker.plan.Topology;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The console, end to end, against the running stack.
 *
 * <p>The whole point of §10a.2 is a round trip nobody has to take on trust: a line typed here comes
 * out of the server's own log. That is what this asserts, by typing {@code list} into the live SMP
 * - a command that changes nothing, names who is online, and is the smallest thing that proves the
 * path.</p>
 */
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
    @DisplayName("a service without a console is refused, and told what it has instead")
    void refusesTheSix() {
        for (final String service : List.of(Topology.DISCORD_BOT, "postgres", "caddy",
                Topology.STEWARD_WORKER)) {
            final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> console.send(service, "list"));
            assertTrue(refused.getMessage().startsWith(service),
                    "the refusal should name the service first: " + refused.getMessage());
            assertTrue(refused.getMessage().length() > service.length() + 20,
                    "a refusal with no reason in it is a wall: " + refused.getMessage());
        }
    }

    @Test
    @DisplayName("`list` typed into the SMP comes back out of the SMP's log")
    void theAnswerArrivesInTheLog() throws Exception {
        final Docker.Container smp = docker.containers(PROJECT).stream()
                .filter(container -> Topology.SMP.equals(container.service()) && container.isRunning())
                .findFirst()
                .orElse(null);
        assumeTrue(smp != null, "the SMP is not running on this host - skipping");

        final boolean multiplexed = !docker.inspect(smp.id()).tty();

        // WHY THE CLOCK IS IN THIS TEST. Written without it, it passed with the console path
        // deliberately broken: `list` had been typed a few minutes earlier, its answer was still
        // among the last forty lines, and the test found that one. A round trip has to be proved
        // by a line that did not exist before the line was sent - so everything older than this
        // instant is ignored. One second of slack for the clock difference between this JVM and
        // the daemon's timestamps.
        final Instant sentAt = Instant.now().minusSeconds(1);
        console.send(Topology.SMP, "list");

        // The server answers on its own console, so the answer is read where every other line is
        // read. Polling rather than sleeping once: a healthy server answers in well under a second,
        // and a loaded one should not fail the test for being slow.
        final Instant giveUp = Instant.now().plus(Duration.ofSeconds(15));
        String found = null;
        while (found == null && Instant.now().isBefore(giveUp)) {
            for (final String line : docker.recentLines(smp.id(), 40, multiplexed)) {
                if (line.contains("There are") && line.contains("players online")
                        && isAfter(line, sentAt)) {
                    found = line;
                    break;
                }
            }
            if (found == null) {
                Thread.sleep(500);
            }
        }
        assertTrue(found != null,
                "typed `list` into the SMP and its answer never appeared in the log");
    }

    /**
     * Whether a log line was written after a given instant.
     *
     * <p>The stream is asked for timestamps, so every line begins with one in RFC 3339. A line
     * whose timestamp cannot be read is treated as OLD rather than new: the question this answers
     * is "did my command cause this", and an unreadable timestamp is not evidence that it did.</p>
     */
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
    @DisplayName("an empty line is not a command")
    void refusesNothing() {
        assertThrows(IllegalArgumentException.class, () -> console.send(Topology.SMP, "  "));
    }
}
