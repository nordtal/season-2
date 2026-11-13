package eu.nordtal.season.proxy.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.time.ManualScheduler;
import eu.nordtal.season.database.update.RunRecorder;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.proxy.NetworkServers;
import eu.nordtal.season.proxy.ProxyRenderer;
import eu.nordtal.season.proxy.gate.LoginRoster;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * A player on a stopped server is asked to move once, counted and told why once the move lands.
 * A failed move is tried again in the other waiting room, and Velocity's own refusal never shows.
 */
class EvacuationMovesTest {

    private static final NetworkServers SERVERS = new NetworkServers("limbo", "limbo-standby", "hunger-games", "smp");

    private final RegisteredServer limbo = server("limbo");
    private final RegisteredServer standby = server("limbo-standby");
    private final RegisteredServer smp = server("smp");

    private final ManualScheduler scheduler = new ManualScheduler();

    /** The waiting room of each move the player asked for. */
    private final List<String> asked = new ArrayList<>();

    /** The one move the player asked for, which completes only when the test says. */
    private final List<CompletableFuture<ConnectionRequestBuilder.Result>> moves = new ArrayList<>();

    private final List<Component> told = new ArrayList<>();
    private final AtomicInteger counted = new AtomicInteger();
    private final Player player = player(UUID.randomUUID());

    /** Whether the run is still under way. */
    private boolean running = true;

    /** Whether the steps hold a name this build does not know, as a newer agent writes. */
    private boolean unreadableSteps;

    private final Evacuation evacuation = evacuation(Instant.EPOCH.minusSeconds(60));

    /** An evacuation in a proxy started at {@code started}; the run's countdown ended at the epoch. */
    private Evacuation evacuation(final Instant started) {
        return new Evacuation(
                proxy(),
                scheduler,
                LoggerFactory.getLogger(EvacuationMovesTest.class),
                updates(),
                SERVERS,
                new Homecoming(
                        LoggerFactory.getLogger(EvacuationMovesTest.class),
                        ProxyRenderer.RENDERER,
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
        assertEquals(0, counted.get(), "a move counts once the player is in the waiting room");
        assertFalse(evacuation.settled(player.getUniqueId()).isDone(), "the proxy swap waits for this move");

        moves.getFirst().complete(result(ConnectionRequestBuilder.Status.SUCCESS));

        assertEquals(1, counted.get(), "the player is counted as moved once");
        assertTrue(evacuation.settled(player.getUniqueId()).isDone());
    }

    @Test
    void aFailedMoveIsTriedAgainInTheOtherWaitingRoom() {
        evacuation.check();
        moves.getFirst().complete(result(ConnectionRequestBuilder.Status.SERVER_DISCONNECTED));

        assertEquals(1, scheduler.pending().size());
        assertEquals(Evacuation.RETRY, scheduler.pending().getFirst().delay());
        assertFalse(evacuation.settled(player.getUniqueId()).isDone(), "the proxy swap waits for the next try too");
        scheduler.runPending();

        assertEquals(List.of("limbo", "limbo-standby"), asked, "the second try goes to the other waiting room");
        moves.getLast().complete(result(ConnectionRequestBuilder.Status.SUCCESS));
        assertEquals(1, counted.get(), "only the move that landed counts");
        assertEquals(1, told.size());
        assertTrue(evacuation.settled(player.getUniqueId()).isDone());
    }

    @Test
    void aRetryAfterTheRunEndedMovesNobody() {
        evacuation.check();
        moves.getFirst().complete(result(ConnectionRequestBuilder.Status.SERVER_DISCONNECTED));
        running = false;
        evacuation.check();

        scheduler.runPending();

        assertEquals(1, moves.size(), "the run no longer stops the player's server");
        assertTrue(evacuation.settled(player.getUniqueId()).isDone(), "no try follows, so the swap may go ahead");
    }

    @Test
    void theRetryRoomIsTheOtherOneWhileTheRunLeavesItRunning() {
        final Map<String, Boolean> registered = Map.of("limbo", true, "limbo-standby", true);
        assertEquals(
                "limbo-standby",
                Evacuation.retryRoom("limbo", Set.of("smp"), "limbo", "limbo-standby", registered::get));
        assertEquals(
                "limbo",
                Evacuation.retryRoom("limbo-standby", Set.of("smp"), "limbo", "limbo-standby", registered::get));
        assertEquals(
                "limbo",
                Evacuation.retryRoom("limbo", Set.of("smp"), "limbo", "limbo-standby", room -> room.equals("limbo")),
                "without a standby the same room is tried again");
        assertEquals(
                "limbo-standby",
                Evacuation.retryRoom("limbo-standby", Set.of("limbo"), "limbo", "limbo-standby", registered::get),
                "a room the run stops is never a target");
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

        assertEquals(0, told.size(), "nothing while the move is under way");
        moves.getFirst().complete(result(ConnectionRequestBuilder.Status.SUCCESS));
        evacuation.check();
        moves.getLast().complete(result(ConnectionRequestBuilder.Status.SUCCESS));

        assertEquals(1, told.size(), "one line, once the move landed");
        final String line = PlainTextComponentSerializer.plainText().serialize(told.getFirst());
        assertTrue(line.contains("SMP") && line.contains("waiting room"), line);
    }

    @Test
    void aMoveTheClientRefusesShowsNothingAndIsAskedForAgain() {
        evacuation.check();
        moves.getFirst().complete(result(ConnectionRequestBuilder.Status.CONNECTION_IN_PROGRESS));

        evacuation.check();
        assertEquals(1, moves.size(), "the pass leaves the next try to the timer");
        scheduler.runPending();

        assertEquals(0, told.size(), "Velocity's 'already connecting' never reaches the player");
        assertEquals(2, moves.size(), "a move that did not happen is asked for again");
        assertEquals(0, counted.get());
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
            case "getServer" ->
                Optional.ofNullable(Map.of("limbo", limbo, "limbo-standby", standby, "smp", smp)
                        .get((String) arguments[0]));
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
            case "running" -> this.running ? Optional.of(running) : Optional.empty();
            case "lastStartedSwitch" -> Optional.empty();
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
        return fake(Player.class, (method, arguments) -> switch (method) {
            case "getUniqueId" -> uuid;
            case "getUsername" -> "somebody";
            case "getCurrentServer" -> Optional.of(on);
            case "isActive" -> true;
            case "createConnectionRequest" -> request((RegisteredServer) arguments[0]);
            case "sendMessage" -> {
                told.add((Component) arguments[0]);
                yield null;
            }
            default -> throw new UnsupportedOperationException(method);
        });
    }

    private ConnectionRequestBuilder request(final RegisteredServer room) {
        asked.add(room.getServerInfo().getName());
        return fake(ConnectionRequestBuilder.class, (method, arguments) -> switch (method) {
            case "connect" -> {
                final CompletableFuture<ConnectionRequestBuilder.Result> move = new CompletableFuture<>();
                moves.add(move);
                yield move;
            }
            // Velocity sends its own text to the player on every failure of these.
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
