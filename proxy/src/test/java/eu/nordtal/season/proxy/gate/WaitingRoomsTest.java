package eu.nordtal.season.proxy.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import eu.nordtal.season.common.time.Waiting;
import eu.nordtal.season.proxy.PhaseServers;
import java.lang.reflect.Proxy;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** The live proxy answers only once its waiting room does, so the standby keeps its players until then. */
class WaitingRoomsTest {

    private static final PhaseServers SERVERS = new PhaseServers("limbo", "limbo-standby", "hunger-games", "smp");

    private final Driven clock = new Driven();

    /** When each server starts answering pings; a server not in here never does. */
    private final Map<String, Instant> upAt = new HashMap<>();

    @Test
    void theProxyWaitsWhileLimboRefuses() {
        upAt.put("limbo", clock.now.plusSeconds(20));

        final Optional<RegisteredServer> room = WaitingRooms.answering(proxy(), SERVERS, clock, WaitingRooms.AT_START);

        assertEquals("limbo", room.orElseThrow().getServerInfo().getName());
        assertEquals(Duration.ofSeconds(20), clock.slept, "the port opens only once limbo answers");
    }

    @Test
    void theStandbyWaitingRoomCountsToo() {
        upAt.put("limbo-standby", clock.now);

        assertEquals(
                "limbo-standby",
                WaitingRooms.answering(proxy(), SERVERS, clock, WaitingRooms.AT_START)
                        .orElseThrow()
                        .getServerInfo()
                        .getName());
    }

    @Test
    void thePatienceEnds() {
        assertEquals(Optional.empty(), WaitingRooms.answering(proxy(), SERVERS, clock, WaitingRooms.AT_START));
        assertEquals(
                WaitingRooms.AT_START, clock.slept, "a waiting room that never comes keeps the proxy shut no longer");
    }

    private ProxyServer proxy() {
        final Map<String, RegisteredServer> registered =
                Map.of("limbo", server("limbo"), "limbo-standby", server("limbo-standby"));
        return (ProxyServer) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {ProxyServer.class}, (self, method, arguments) -> {
                    if (method.getName().equals("getServer")) {
                        return Optional.ofNullable(registered.get((String) arguments[0]));
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private RegisteredServer server(final String name) {
        final ServerInfo info = new ServerInfo(name, InetSocketAddress.createUnresolved(name, 25565));
        return (RegisteredServer) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {RegisteredServer.class},
                (self, method, arguments) -> switch (method.getName()) {
                    case "getServerInfo" -> info;
                    case "ping" -> {
                        final Instant up = upAt.get(name);
                        yield up != null && !clock.now.isBefore(up)
                                ? CompletableFuture.completedFuture(null)
                                : CompletableFuture.failedFuture(new ConnectException("Connection refused"));
                    }
                    case "toString" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A clock that never sleeps. */
    private static final class Driven implements Waiting {

        private Instant now = Instant.parse("2026-10-18T19:00:31Z");
        private Duration slept = Duration.ZERO;

        @Override
        public Instant now() {
            return now;
        }

        @Override
        public boolean sleep(final Duration duration) {
            now = now.plus(duration);
            slept = slept.plus(duration);
            return true;
        }
    }
}
