package eu.nordtal.season.proxy.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import eu.nordtal.season.common.time.Waiting;
import eu.nordtal.season.proxy.MutableClock;
import eu.nordtal.season.proxy.PhaseServers;
import eu.nordtal.season.proxy.ProxyRenderer;
import eu.nordtal.season.proxy.config.GateSpec;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** A player sent back from the standby whom limbo refuses waits for limbo rather than seeing Velocity's error. */
class BackendKickArrivalTest {

    private static final PhaseServers SERVERS = new PhaseServers("limbo", "limbo-standby", "hunger-games", "smp");

    private final Driven clock = new Driven();
    private @Nullable Instant limboUpAt;
    private final RegisteredServer limbo = server("limbo");

    private final BackendKick kick = new BackendKick(
            proxy(),
            SERVERS,
            new BackendHealth(new MutableClock(Instant.EPOCH)),
            new GateMessages(ProxyRenderer.RENDERER, (GateSpec) Proxy.newProxyInstance(
                    GateSpec.class.getClassLoader(), new Class<?>[] {GateSpec.class}, (InvocationHandler)
                            (self, method, arguments) -> {
                                throw new UnsupportedOperationException(method.getName());
                            })),
            new LoginRoster(),
            LoggerFactory.getLogger(BackendKickArrivalTest.class),
            clock);

    @Test
    void limboThatComesUpTakesThePlayer() {
        limboUpAt = clock.now.plusSeconds(4);
        final KickedFromServerEvent event = refused(null);

        run(kick.onKickedFromServer(event));

        final var redirect = assertInstanceOf(KickedFromServerEvent.RedirectPlayer.class, event.getResult());
        assertEquals("limbo", redirect.getServer().getServerInfo().getName());
        assertEquals(Duration.ofSeconds(4), clock.slept);
    }

    @Test
    void limboThatStaysDownEndsInOurOwnText() {
        final KickedFromServerEvent event = refused(null);

        run(kick.onKickedFromServer(event));

        final var disconnect = assertInstanceOf(KickedFromServerEvent.DisconnectPlayer.class, event.getResult());
        final String said = PlainTextComponentSerializer.plainText().serialize(disconnect.getReasonComponent());
        assertTrue(said.contains("restarting"), said);
        assertEquals(WaitingRooms.ON_ARRIVAL, clock.slept);
    }

    @Test
    void aPlayerAlreadyOnAServerIsLeftToVelocity() {
        final ServerConnection on = (ServerConnection) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {ServerConnection.class}, (self, method, arguments) -> {
                    throw new UnsupportedOperationException(method.getName());
                });

        assertNull(kick.onKickedFromServer(refused(on)));
    }

    private KickedFromServerEvent refused(final @Nullable ServerConnection current) {
        final Player player = (Player) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {Player.class},
                (self, method, arguments) -> switch (method.getName()) {
                    case "getUsername" -> "somebody";
                    case "getUniqueId" -> new UUID(0, 1);
                    case "getCurrentServer" -> Optional.ofNullable(current);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        // What Velocity fires when the connection to limbo is refused: no reason from the server.
        return new KickedFromServerEvent(
                player, limbo, null, true, KickedFromServerEvent.DisconnectPlayer.create(Component.text("refused")));
    }

    private static void run(final @Nullable EventTask task) {
        assertNotNull(task, "the arriving player is held while limbo is retried");
        task.execute(new Continuation() {
            @Override
            public void resume() {}

            @Override
            public void resumeWithException(final Throwable failure) {
                throw new AssertionError(failure);
            }
        });
    }

    private ProxyServer proxy() {
        return (ProxyServer) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {ProxyServer.class}, (self, method, arguments) -> {
                    if (method.getName().equals("getServer")) {
                        return Optional.ofNullable(Map.of("limbo", limbo).get((String) arguments[0]));
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
                    case "ping" ->
                        limboUpAt != null && !clock.now.isBefore(limboUpAt)
                                ? CompletableFuture.completedFuture(null)
                                : CompletableFuture.failedFuture(new ConnectException("Connection refused"));
                    case "toString" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final class Driven implements Waiting {

        private Instant now = Instant.parse("2026-10-18T19:00:45Z");
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
