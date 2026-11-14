package eu.nordtal.season.proxy.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.player.ResourcePackInfo;
import com.velocitypowered.api.proxy.server.ServerInfo;
import eu.nordtal.season.common.season.SeasonState;
import eu.nordtal.season.common.time.ManualScheduler;
import eu.nordtal.season.database.access.AccessState;
import eu.nordtal.season.database.season.SeasonDirectory;
import eu.nordtal.season.proxy.MutableClock;
import eu.nordtal.season.proxy.NetworkServers;
import eu.nordtal.season.proxy.ProxyRenderer;
import eu.nordtal.season.proxy.ProxyRole;
import eu.nordtal.season.proxy.config.PackSpec;
import eu.nordtal.season.proxy.gate.BackendHealth;
import eu.nordtal.season.proxy.gate.LoginRoster;
import eu.nordtal.season.proxy.routing.SeasonRouting;
import eu.nordtal.season.proxy.season.SeasonWatch;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

/** A change of the active pack is offered at once to every player already online, as limbo offers it. */
class PackChangeOfferTest {

    private static final String OLD = "a".repeat(40);
    private static final String NEW = "b".repeat(40);
    private static final NetworkServers SERVERS = new NetworkServers("limbo", "limbo-standby", "hunger-games", "smp");

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-14T03:29:08Z"));
    private final AtomicReference<PackSpec> spec = new AtomicReference<>(pack(OLD, true));
    private final WaitingBook book = new WaitingBook(
            () -> spec.get().enabled(),
            () -> Duration.ofSeconds(180),
            Duration.ofSeconds(5),
            ProxyRole.LIVE,
            new AccessQueue("smp", () -> Duration.ofSeconds(3), clock),
            clock);
    private final LoginRoster roster = new LoginRoster();
    private final List<FakePlayer> online = new ArrayList<>();
    private final PackStation station = new PackStation(
            fake(ProxyServer.class, (method, arguments) -> switch (method) {
                case "getAllPlayers" -> online.stream().map(FakePlayer::player).toList();
                case "createResourcePackBuilder" -> new FakeBuilder((String) arguments[0]).builder();
                default -> throw new UnsupportedOperationException(method);
            }),
            NOPLogger.NOP_LOGGER,
            new SeasonRouting(SERVERS),
            new SeasonWatch(
                    fake(SeasonDirectory.class, (method, arguments) -> SeasonState.UNREAD),
                    new ManualScheduler(),
                    clock,
                    NOPLogger.NOP_LOGGER,
                    (previous, current) -> {}),
            roster,
            new PackMessages(ProxyRenderer.RENDERER),
            spec::get,
            book,
            new BackendHealth(clock));

    @Test
    void aPlayerOnSmpIsOfferedTheNewPackAndLosesOnlyTheOldOneOnceApplied() {
        final FakePlayer alex = online("Alex", "smp", false);
        applied(alex, OLD);

        spec.set(pack(NEW, true));
        assertEquals(1, station.packChanged());

        assertEquals(1, alex.offers.size());
        final ResourcePackInfo offer = alex.offers.getFirst();
        assertEquals(PackOffer.idOf(NEW), offer.getId());
        assertEquals("https://pack.nordtal.eu/" + NEW + ".zip", offer.getUrl());
        assertEquals(List.of(), alex.removed, "nothing is removed before the client answers");

        station.onPackStatus(status(alex, NEW, PlayerResourcePackStatusEvent.Status.SUCCESSFUL));
        assertEquals(List.of(PackOffer.idOf(OLD)), alex.removed);
        assertEquals(0, alex.disconnects);
    }

    @Test
    void exemptPlayersAndPlayersWithoutAServerAreLeftAlone() {
        final FakePlayer exempt = online("Steve", "smp", true);
        final FakePlayer joining = online("Kai", null, false);

        spec.set(pack(NEW, true));
        assertEquals(0, station.packChanged());

        assertEquals(List.of(), exempt.offers);
        assertEquals(List.of(), joining.offers);
    }

    @Test
    void aPlayerWhoHasTheNewPackIsNotOfferedItAgain() {
        final FakePlayer alex = online("Alex", "smp", false);
        applied(alex, NEW);

        spec.set(pack(NEW, true));
        assertEquals(0, station.packChanged());
        assertEquals(List.of(), alex.offers);
    }

    @Test
    void aPlayerOnSmpWhoDeclinesTheNewPackIsDisconnectedAsOnLimbo() {
        final FakePlayer alex = online("Alex", "smp", false);
        applied(alex, OLD);

        spec.set(pack(NEW, true));
        station.packChanged();
        station.onPackStatus(status(alex, NEW, PlayerResourcePackStatusEvent.Status.DECLINED));

        assertEquals(1, alex.disconnects);
        assertEquals(List.of(), alex.removed);
    }

    @Test
    void aDisabledPackIsOfferedToNobody() {
        final FakePlayer alex = online("Alex", "smp", false);
        applied(alex, OLD);

        spec.set(pack(NEW, false));
        assertEquals(0, station.packChanged());
        assertTrue(alex.offers.isEmpty());
    }

    private void applied(final FakePlayer player, final String sha1) {
        assertTrue(book.claimOffer(player.uuid, sha1));
        book.packApplied(player.uuid, sha1);
    }

    private FakePlayer online(final String name, final String server, final boolean exempt) {
        final FakePlayer player = new FakePlayer(name, server);
        roster.remember(player.uuid, state(player.uuid, exempt));
        online.add(player);
        return player;
    }

    private static AccessState state(final UUID uuid, final boolean exempt) {
        return new AccessState(
                uuid,
                new eu.nordtal.season.common.id.DiscordId("1"),
                null,
                true,
                null,
                false,
                false,
                exempt,
                0,
                Locale.ENGLISH,
                SeasonState.UNREAD);
    }

    private static PlayerResourcePackStatusEvent status(
            final FakePlayer player, final String sha1, final PlayerResourcePackStatusEvent.Status status) {
        return new PlayerResourcePackStatusEvent(player.player(), PackOffer.idOf(sha1), status, null);
    }

    private static PackSpec pack(final String sha1, final boolean enabled) {
        return new PackSpec() {
            @Override
            public boolean enabled() {
                return enabled;
            }

            @Override
            public String url() {
                return "https://pack.nordtal.eu/" + sha1 + ".zip";
            }

            @Override
            public String sha1() {
                return sha1;
            }
        };
    }

    private static final class FakePlayer {

        private final String name;
        private final UUID uuid = UUID.randomUUID();
        private Optional<ServerConnection> server = Optional.empty();
        private final List<ResourcePackInfo> offers = new ArrayList<>();
        private final List<UUID> removed = new ArrayList<>();
        private int disconnects;
        private final Player player;

        FakePlayer(final String name, final String server) {
            this.name = name;
            this.player = fake(Player.class, (method, arguments) -> switch (method) {
                case "getUniqueId" -> uuid;
                case "getUsername" -> this.name;
                case "getCurrentServer" -> this.server;
                case "sendResourcePackOffer" -> {
                    offers.add((ResourcePackInfo) arguments[0]);
                    yield null;
                }
                case "removeResourcePacks" -> {
                    removed.add((UUID) arguments[0]);
                    removed.addAll(Arrays.asList((UUID[]) arguments[1]));
                    yield null;
                }
                case "disconnect" -> {
                    disconnects++;
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method);
            });
            this.server = server == null ? Optional.empty() : Optional.of(connection(server, player));
        }

        Player player() {
            return player;
        }
    }

    private static ServerConnection connection(final String name, final Player player) {
        final ServerInfo info = new ServerInfo(name, InetSocketAddress.createUnresolved(name, 25565));
        return fake(ServerConnection.class, (method, arguments) -> switch (method) {
            case "getServerInfo" -> info;
            case "getPlayer" -> player;
            default -> throw new UnsupportedOperationException(method);
        });
    }

    /** Builds a pack offer that keeps what the station set. */
    private static final class FakeBuilder {

        private final String url;
        private UUID id;
        private byte[] hash;
        private ResourcePackInfo.Builder builder;

        FakeBuilder(final String url) {
            this.url = url;
            this.builder = fake(ResourcePackInfo.Builder.class, (method, arguments) -> {
                switch (method) {
                    case "setId" -> id = (UUID) arguments[0];
                    case "setHash" -> hash = (byte[]) arguments[0];
                    case "setShouldForce", "setPrompt" -> {}
                    case "build" -> {
                        return info();
                    }
                    default -> throw new UnsupportedOperationException(method);
                }
                return this.builder;
            });
        }

        ResourcePackInfo.Builder builder() {
            return builder;
        }

        private ResourcePackInfo info() {
            final UUID packId = id;
            final byte[] packHash = hash;
            return fake(ResourcePackInfo.class, (method, arguments) -> switch (method) {
                case "getId" -> packId;
                case "getUrl" -> url;
                case "getHash" -> packHash;
                default -> throw new UnsupportedOperationException(method);
            });
        }
    }

    @FunctionalInterface
    private interface Answer {
        Object answer(String method, Object[] arguments);
    }

    private static <T> T fake(final Class<T> type, final Answer answer) {
        return type.cast(Proxy.newProxyInstance(
                PackChangeOfferTest.class.getClassLoader(),
                new Class<?>[] {type},
                (self, method, arguments) -> switch (method.getName()) {
                    case "toString" -> type.getSimpleName();
                    default -> answer.answer(method.getName(), arguments == null ? new Object[0] : arguments);
                }));
    }
}
