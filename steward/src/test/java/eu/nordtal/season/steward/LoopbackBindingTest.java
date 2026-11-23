package eu.nordtal.season.steward;

import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.nordtal.season.stewardagent.AgentStandIn;
import io.javalin.util.JavalinBindException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks that local HTTP stand-ins bind loopback explicitly rather than wildcard.
 *
 * On macOS, a wildcard Javalin bind with SO_REUSEADDR succeeds on a port already held on 127.0.0.1,
 * and connections to 127.0.0.1 then route to the other listener instead of the test server.
 */
class LoopbackBindingTest {

    @TempDir
    Path scratch;

    @Test
    void standInDiscordRefusesAPortAlreadyHeldOnLoopback() throws IOException {
        try (ServerSocket occupant = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
                StandInDiscord discord = new StandInDiscord()) {
            final int occupied = occupant.getLocalPort();
            assertThrows(JavalinBindException.class, () -> discord.start(occupied));
        }
    }

    @Test
    void agentStandInRefusesAPortAlreadyHeldOnLoopback() throws IOException {
        try (ServerSocket occupant = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            final int occupied = occupant.getLocalPort();
            assertThrows(JavalinBindException.class, () -> {
                try (AgentStandIn agent = new AgentStandIn(scratch, occupied, cfg -> {})) {
                    throw new AssertionError("expected bind failure on occupied loopback port " + agent.base());
                }
            });
        }
    }
}
