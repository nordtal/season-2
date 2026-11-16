package eu.nordtal.season.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.season.SeasonMode;
import eu.nordtal.season.common.season.SeasonState;
import eu.nordtal.season.common.time.ManualScheduler;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.access.AccessReader;
import eu.nordtal.season.database.access.AccessState;
import eu.nordtal.season.database.access.MemberState;
import eu.nordtal.season.database.season.SeasonDirectory;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.proxy.config.GateSpec;
import eu.nordtal.season.proxy.config.PackSpec;
import eu.nordtal.season.proxy.gate.BackendHealth;
import eu.nordtal.season.proxy.gate.FallbackCache;
import eu.nordtal.season.proxy.gate.GateMessages;
import eu.nordtal.season.proxy.gate.LoginRoster;
import eu.nordtal.season.proxy.pack.AccessQueue;
import eu.nordtal.season.proxy.pack.PackMessages;
import eu.nordtal.season.proxy.pack.PackStation;
import eu.nordtal.season.proxy.pack.WaitingBook;
import eu.nordtal.season.proxy.routing.RouteIntents;
import eu.nordtal.season.proxy.routing.SeasonRouting;
import eu.nordtal.season.proxy.season.SeasonWatch;
import eu.nordtal.season.proxy.update.Homecoming;
import eu.nordtal.season.proxy.update.ParkedSeats;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

/** A backend confirms a return before the run counts the player back. */
class ReleaseReturnIntegrationTest {
    private final UUID uuid = UUID.randomUUID();
    private final Clock clock = Clock.systemUTC();
    private final CompletableFuture<ConnectionRequestBuilder.Result> connection = new CompletableFuture<>();
    private final UpdateDirectory updates =
            UpdateDirectory.using(TestDatabase.fresh().dataSource());
    private final long run =
            updates.submit(UpdateKind.RESTART, Actor.HOST, Duration.ZERO).id();
    private boolean active = true;

    @Test
    void aFailedReleaseFollowedByAPermanentDisconnectLeavesThePlayerLost() {
        final PlayerRouter router = router();
        router.releaseFromLimbo(player());
        connection.complete(result(ConnectionRequestBuilder.Status.CONNECTION_CANCELLED));
        active = false;
        assertEquals(0, updates.recorder().moves(run).getFirst().returned());
    }

    @Test
    void aPendingConnectionHasNotReturnedYet() {
        router().releaseFromLimbo(player());
        assertEquals(0, updates.recorder().moves(run).getFirst().returned());
    }

    @Test
    void aSuccessfulConnectionCountsThePlayerBack() {
        router().releaseFromLimbo(player());
        connection.complete(result(ConnectionRequestBuilder.Status.SUCCESS));
        assertEquals(1, updates.recorder().moves(run).getFirst().returned());
    }

    @Test
    void anAlreadyConnectedResultCountsThePlayerBack() {
        router().releaseFromLimbo(player());
        connection.complete(result(ConnectionRequestBuilder.Status.ALREADY_CONNECTED));
        assertEquals(1, updates.recorder().moves(run).getFirst().returned());
    }

    private PlayerRouter router() {
        updates.recorder().begin(run, RunStep.Kind.PLAYERS_MOVED, null);
        updates.recorder().moved(run, "smp", "limbo-standby", 1);
        updates.recorder().rememberMove(run, uuid, "smp", "limbo-standby", false);
        final var servers = new NetworkServers("limbo", "limbo-standby", "hunger-games", "smp");
        final var routing = new SeasonRouting(servers);
        final var roster = new LoginRoster();
        final var logger = NOPLogger.NOP_LOGGER;
        final var scheduler = new ManualScheduler();
        final var state = new SeasonState(Instant.EPOCH, false, SeasonMode.SMP);
        final var seasons = new SeasonWatch(
                fake(SeasonDirectory.class, method -> state), scheduler, clock, logger, (previous, current) -> {});
        final var health = new BackendHealth(clock);
        final RegisteredServer target = fake(RegisteredServer.class, method -> info("smp"));
        final ProxyServer proxy = fake(ProxyServer.class, method -> switch (method) {
            case "getServer" -> Optional.of(target);
            case "getAllServers" -> List.of(target);
            default -> null;
        });
        final var book = new WaitingBook(
                false,
                Duration.ofSeconds(30),
                Duration.ZERO,
                ProxyRole.LIVE,
                new AccessQueue("smp", () -> Duration.ZERO, clock),
                clock);
        final var packs = new PackStation(
                proxy,
                logger,
                routing,
                seasons,
                roster,
                new PackMessages(ProxyRenderer.RENDERER),
                () -> new PackSpec() {},
                book,
                health);
        return new PlayerRouter(
                scheduler,
                proxy,
                logger,
                access(state),
                routing,
                seasons,
                roster,
                new FallbackCache(Duration.ofMinutes(1), clock),
                new GateMessages(ProxyRenderer.RENDERER, new GateSpec() {}),
                packs,
                new RouteIntents(roster, servers, logger),
                health,
                new ParkedSeats(List.of(), clock.instant()),
                new Homecoming(logger, ProxyRenderer.RENDERER, roster, servers, updates.recorder()),
                clock);
    }

    private AccessReader access(final SeasonState state) {
        return fake(
                AccessReader.class,
                method -> new AccessState(
                        uuid,
                        DiscordId.of("123"),
                        MemberState.MEMBER,
                        true,
                        null,
                        false,
                        true,
                        true,
                        0,
                        Locale.ENGLISH,
                        null,
                        state));
    }

    private Player player() {
        final ServerConnection current = fake(ServerConnection.class, method -> info("limbo"));
        final ConnectionRequestBuilder request = fake(ConnectionRequestBuilder.class, method -> connection);
        return fake(Player.class, method -> switch (method) {
            case "getUniqueId" -> uuid;
            case "getUsername" -> "somebody";
            case "isActive" -> active;
            case "getCurrentServer" -> Optional.of(current);
            case "createConnectionRequest" -> request;
            default -> null;
        });
    }

    private static ServerInfo info(final String name) {
        return new ServerInfo(name, InetSocketAddress.createUnresolved(name, 25565));
    }

    private static ConnectionRequestBuilder.Result result(final ConnectionRequestBuilder.Status status) {
        return fake(
                ConnectionRequestBuilder.Result.class,
                method -> method.equals("getStatus") ? status : Optional.empty());
    }

    private static <T> T fake(final Class<T> type, final Function<String, Object> answer) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, (self, method, args) -> answer.apply(method.getName())));
    }
}
