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
import eu.nordtal.season.common.season.SeasonMode;
import eu.nordtal.season.common.time.ManualScheduler;
import eu.nordtal.season.database.inbox.StewardRequest;
import eu.nordtal.season.database.update.RunProgress;
import eu.nordtal.season.database.update.RunRecorder;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.proxy.MutableClock;
import eu.nordtal.season.proxy.NetworkServers;
import eu.nordtal.season.proxy.ProxyRenderer;
import eu.nordtal.season.proxy.gate.LoginRoster;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** A mode switch moves everybody into the waiting room, holds them there, and lets them go once. */
class EvacuationSwitchTest {

    private static final NetworkServers SERVERS = new NetworkServers("limbo", "limbo-standby", "hunger-games", "smp");
    private static final long RUN = 11L;

    private final RegisteredServer limbo = server("limbo");
    private final RegisteredServer smp = server("smp");
    private final List<CompletableFuture<ConnectionRequestBuilder.Result>> moves = new ArrayList<>();
    private final List<Component> told = new ArrayList<>();
    private final List<String> recorded = new ArrayList<>();
    private final AtomicInteger released = new AtomicInteger();
    private final Player player = player(UUID.randomUUID());

    private UpdateStatus status = UpdateStatus.RUNNING;
    private List<RunProgress.Step> steps = List.of();

    private final Evacuation evacuation = evacuation();

    /** An evacuation in a proxy the run started, which follows the hold but moves nobody itself. */
    private Evacuation evacuation() {
        final Evacuation built = new Evacuation(
                proxy(),
                new ManualScheduler(),
                LoggerFactory.getLogger(EvacuationSwitchTest.class),
                updates(),
                SERVERS,
                new Homecoming(
                        LoggerFactory.getLogger(EvacuationSwitchTest.class),
                        ProxyRenderer.RENDERER,
                        new LoginRoster(),
                        SERVERS,
                        RunRecorder.NONE),
                Clock.fixed(Instant.EPOCH.plusSeconds(60), ZoneOffset.UTC));
        built.whenReleased(released::incrementAndGet);
        return built;
    }

    @Test
    void aRunningSwitchHoldsEverybodyAndMovesThemOnTheSwitchRun() {
        // This proxy started before the countdown ended, so the run did not start it and it moves the players.
        final MutableClock clock = new MutableClock(Instant.EPOCH.minusSeconds(60));
        final Evacuation early = new Evacuation(
                proxy(),
                new ManualScheduler(),
                LoggerFactory.getLogger(EvacuationSwitchTest.class),
                updates(),
                SERVERS,
                new Homecoming(
                        LoggerFactory.getLogger(EvacuationSwitchTest.class),
                        ProxyRenderer.RENDERER,
                        new LoginRoster(),
                        SERVERS,
                        RunRecorder.NONE),
                clock);
        clock.advance(Duration.ofSeconds(90));
        early.check();
        assertEquals(List.of(), recorded, "a move counts once the player is in the waiting room");
        moves.getFirst().complete(landed());

        assertTrue(early.holdsEveryone());
        assertEquals(List.of("11: smp -> limbo x1"), recorded, "the move is counted on the switch run");
        assertEquals(1, moves.size());
        final String line = PlainTextComponentSerializer.plainText().serialize(told.getFirst());
        assertTrue(line.contains("Hunger Games"), "the player is told where the switch leads: " + line);
    }

    @Test
    void thePassedCheckOfTheTargetReleasesTheWaitingRoomOnce() {
        evacuation.check();
        assertTrue(evacuation.holdsEveryone());

        steps = List.of(new RunProgress.Step(RunStep.Kind.VERIFY, "hunger-games", RunStep.Outcome.DONE));
        evacuation.check();
        evacuation.check();

        assertFalse(evacuation.holdsEveryone());
        assertEquals(1, released.get(), "the release runs on the change, not on every pass");
    }

    @Test
    void aSwitchThatFailedOnItsTargetReleasesTheWaitingRoomOnce() {
        evacuation.check();
        assertTrue(evacuation.holdsEveryone());
        status = UpdateStatus.FAILED;
        steps = List.of(new RunProgress.Step(RunStep.Kind.START, "hunger-games", RunStep.Outcome.FAILED));

        evacuation.check();
        evacuation.check();

        assertFalse(
                evacuation.holdsEveryone(), "the waiting room's own rules keep the players while the target is down");
        assertEquals(1, released.get());
    }

    @Test
    void aSwitchThatFailedBeforeItsTargetReleasesTheWaitingRoom() {
        evacuation.check();
        status = UpdateStatus.FAILED;
        steps = List.of(new RunProgress.Step(RunStep.Kind.STOP, "smp", RunStep.Outcome.FAILED));

        evacuation.check();

        assertFalse(evacuation.holdsEveryone(), "the old mode's server is back, so nobody waits for the target");
        assertEquals(1, released.get());
    }

    @Test
    void theEndOfTheRunReleasesTheWaitingRoom() {
        evacuation.check();
        status = UpdateStatus.DONE;

        evacuation.check();

        assertFalse(evacuation.holdsEveryone());
        assertEquals(1, released.get());
    }

    private ProxyServer proxy() {
        return fake(ProxyServer.class, (method, arguments) -> switch (method) {
            case "getServer" -> Optional.of("limbo".equals(arguments[0]) ? limbo : smp);
            case "getAllPlayers" -> List.of(player);
            default -> throw new UnsupportedOperationException(method);
        });
    }

    private UpdateRequest run() {
        return new UpdateRequest(
                RUN,
                UpdateKind.SWITCH_MODE,
                status,
                Actor.HOST,
                Instant.EPOCH,
                Instant.EPOCH,
                Instant.EPOCH,
                List.of("smp", "hunger-games"),
                Instant.EPOCH,
                null,
                null);
    }

    private UpdateDirectory updates() {
        final RunRecorder recorder = new RunRecorder() {
            @Override
            public void moved(final long run, final String from, final String to, final int players) {
                recorded.add(run + ": " + from + " -> " + to + " x" + players);
            }
        };
        return fake(UpdateDirectory.class, (method, arguments) -> switch (method) {
            case "holds" -> List.of();
            case "running" -> status == UpdateStatus.RUNNING ? Optional.of(run()) : Optional.empty();
            case "lastStartedSwitch" -> Optional.of(run());
            case "requestOf" -> Optional.of(new StewardRequest.SwitchMode(SeasonMode.HUNGER_GAMES, null));
            case "recorder" -> recorder;
            case "stepsOf" -> steps;
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

    /** What Velocity answers once the player is in the waiting room. */
    private ConnectionRequestBuilder.Result landed() {
        return new ConnectionRequestBuilder.Result() {
            @Override
            public ConnectionRequestBuilder.Status getStatus() {
                return ConnectionRequestBuilder.Status.SUCCESS;
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

    @FunctionalInterface
    private interface Answer {
        Object answer(String method, Object[] arguments);
    }

    private static <T> T fake(final Class<T> type, final Answer answer) {
        return type.cast(Proxy.newProxyInstance(
                EvacuationSwitchTest.class.getClassLoader(),
                new Class<?>[] {type},
                (self, method, arguments) -> switch (method.getName()) {
                    case "toString" -> type.getSimpleName();
                    default -> answer.answer(method.getName(), arguments == null ? new Object[0] : arguments);
                }));
    }
}
