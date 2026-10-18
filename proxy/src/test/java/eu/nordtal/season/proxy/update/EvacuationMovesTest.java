package eu.nordtal.season.proxy.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.update.RunRecorder;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.proxy.PhaseServers;
import eu.nordtal.season.proxy.gate.LoginRoster;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** A player on a stopped server is asked to move once, told why once, and never shown Velocity's own refusal. */
class EvacuationMovesTest {

    private static final PhaseServers SERVERS = new PhaseServers("limbo", "limbo-standby", "hunger-games", "smp");

    private final RegisteredServer limbo = server("limbo");
    private final RegisteredServer smp = server("smp");

    /** The one move the player asked for, which completes only when the test says. */
    private final List<CompletableFuture<ConnectionRequestBuilder.Result>> moves = new ArrayList<>();

    private final List<Component> told = new ArrayList<>();
    private final AtomicInteger counted = new AtomicInteger();
    private final Player player = player(UUID.randomUUID());

    /** Whether the steps hold a name this build does not know, as a newer agent writes. */
    private boolean unreadableSteps;

    private final Evacuation evacuation = evacuation(Instant.EPOCH.minusSeconds(60));

    /** An evacuation in a proxy started at {@code started}; the run's countdown ended at the epoch. */
    private Evacuation evacuation(final Instant started) {
        return new Evacuation(
                proxy(),
                LoggerFactory.getLogger(EvacuationMovesTest.class),
                updates(),
                SERVERS,
                new Homecoming(
                        LoggerFactory.getLogger(EvacuationMovesTest.class),
                        MessageRenderer.of(Messages.load(
                                EvacuationMovesTest.class.getClassLoader(), "messages/proxy", Locale.ENGLISH)),
                        new LoginRoster(),
                        SERVERS,
                        RunRecorder.NONE),
                Clock.fixed(started, ZoneOffset.UTC));
    }

    @Test
    void aSlowMoveIsAskedForOnce() {
        evacuation.check();
        // Every step of the run notifies the proxy, so the pass repeats while the client still loads limbo.
        evacuation.check();
        evacuation.check();

        assertEquals(1, moves.size(), "a second request makes Velocity answer 'already connecting'");
        assertEquals(1, counted.get(), "the player is counted as moved once");
    }

    @Test
    void aProxyTheRunStartedMovesNobody() {
        evacuation(Instant.EPOCH.plusSeconds(120)).check();

        assertEquals(0, moves.size(), "the proxy it replaced moved the players before the run stopped anything");
        assertEquals(0, told.size());
    }

    @Test
    void unreadableStepsStillMoveThePlayers() {
        unreadableSteps = true;

        evacuation.check();

        assertEquals(1, moves.size(), "evacuation never depends on the steps being readable");
    }

    @Test
    void thePlayerIsToldWhyOnce() {
        evacuation.check();
        evacuation.check();

        assertEquals(1, told.size(), "one line, at the move");
        final String line = PlainTextComponentSerializer.plainText().serialize(told.getFirst());
        assertTrue(line.contains("SMP") && line.contains("waiting room"), line);
    }

    @Test
    void aMoveTheClientRefusesShowsNothingAndIsAskedForAgain() {
        evacuation.check();
        moves.getFirst().complete(result(ConnectionRequestBuilder.Status.CONNECTION_IN_PROGRESS));

        evacuation.check();

        assertEquals(1, told.size(), "Velocity's 'already connecting' never reaches the player");
        assertEquals(2, moves.size(), "a move that did not happen is asked for again on the next pass");
        assertEquals(1, counted.get());
    }

    private ConnectionRequestBuilder.Result result(final ConnectionRequestBuilder.Status status) {
        return new ConnectionRequestBuilder.Result() {
            @Override
            public ConnectionRequestBuilder.Status getStatus() {
                return status;
            }

            @Override
            public Optional<Component> getReasonComponent() {
                return Optional.empty();
            }

            @Override
            public RegisteredServer getAttemptedConnection() {
                return limbo;
            }
        };
    }

    private ProxyServer proxy() {
        return fake(ProxyServer.class, (method, arguments) -> switch (method) {
            case "getServer" -> Optional.of("limbo".equals(arguments[0]) ? limbo : smp);
            case "getAllPlayers" -> List.of(player);
            default -> throw new UnsupportedOperationException(method);
        });
    }

    private UpdateDirectory updates() {
        final UpdateRequest running = new UpdateRequest(
                7L,
                UpdateKind.RECREATE,
                UpdateStatus.RUNNING,
                Actor.HOST,
                Instant.EPOCH,
                Instant.EPOCH,
                Instant.EPOCH,
                List.of("smp"),
                Instant.EPOCH,
                null,
                null);
        final RunRecorder recorder = new RunRecorder() {
            @Override
            public void moved(final long run, final String from, final String to, final int players) {
                counted.addAndGet(players);
            }
        };
        return fake(UpdateDirectory.class, (method, arguments) -> switch (method) {
            case "holds" -> List.of();
            case "running" -> Optional.of(running);
            case "recorder" -> recorder;
            case "stepsOf" -> {
                if (unreadableSteps) {
                    throw new IllegalArgumentException("No enum constant RunStep.Kind.NEWER");
                }
                yield List.of();
            }
            default -> throw new UnsupportedOperationException(method);
        });
    }

    private Player player(final UUID uuid) {
        final ServerConnection on = fake(ServerConnection.class, (method, arguments) -> switch (method) {
            case "getServerInfo" -> smp.getServerInfo();
            default -> throw new UnsupportedOperationException(method);
        });
        final ConnectionRequestBuilder request =
                fake(ConnectionRequestBuilder.class, (method, arguments) -> switch (method) {
                    case "connect" -> {
                        final CompletableFuture<ConnectionRequestBuilder.Result> move = new CompletableFuture<>();
                        moves.add(move);
                        yield move;
                    }
                    // Velocity sends its own text to the player on every failure of these.
                    default -> throw new UnsupportedOperationException(method);
                });
        return fake(Player.class, (method, arguments) -> switch (method) {
            case "getUniqueId" -> uuid;
            case "getUsername" -> "somebody";
            case "getCurrentServer" -> Optional.of(on);
            case "createConnectionRequest" -> request;
            case "sendMessage" -> {
                told.add((Component) arguments[0]);
                yield null;
            }
            default -> throw new UnsupportedOperationException(method);
        });
    }

    private static RegisteredServer server(final String name) {
        final ServerInfo info = new ServerInfo(name, InetSocketAddress.createUnresolved(name, 25565));
        return fake(RegisteredServer.class, (method, arguments) -> switch (method) {
            case "getServerInfo" -> info;
            default -> throw new UnsupportedOperationException(method);
        });
    }

    @FunctionalInterface
    private interface Answer {
        Object answer(String method, Object[] arguments);
    }

    private static <T> T fake(final Class<T> type, final Answer answer) {
        return type.cast(Proxy.newProxyInstance(
                EvacuationMovesTest.class.getClassLoader(),
                new Class<?>[] {type},
                (self, method, arguments) -> switch (method.getName()) {
                    case "toString" -> type.getSimpleName();
                    default -> answer.answer(method.getName(), arguments == null ? new Object[0] : arguments);
                }));
    }
}
