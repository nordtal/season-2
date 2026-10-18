package eu.nordtal.season.proxy.gate;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import eu.nordtal.season.common.time.Backoff;
import eu.nordtal.season.common.time.Waiting;
import eu.nordtal.season.proxy.PhaseServers;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/** Finds a waiting room that accepts connections, which a player needs before the proxy may take them. */
public final class WaitingRooms {

    /** How long the proxy holds its port closed at start until a waiting room answers. */
    public static final Duration AT_START = Duration.ofSeconds(90);

    /** How long an arriving player whom the waiting room refused waits for it, within the client's patience. */
    static final Duration ON_ARRIVAL = Duration.ofSeconds(15);

    private static final Duration PING_WITHIN = Duration.ofSeconds(2);

    private static final Backoff EVERY_SECOND = Backoff.fixed(Duration.ofSeconds(1));

    private WaitingRooms() {}

    /** Returns limbo, otherwise the standby, as soon as one answers a ping, or empty once the patience is spent. */
    public static Optional<RegisteredServer> answering(
            final ProxyServer proxy, final PhaseServers servers, final Waiting clock, final Duration patience) {
        return clock.until(
                () -> Stream.of(servers.limbo(), servers.limboStandby())
                        .flatMap(name -> proxy.getServer(name).stream())
                        .filter(WaitingRooms::answers)
                        .findFirst(),
                patience,
                EVERY_SECOND);
    }

    private static boolean answers(final RegisteredServer room) {
        try {
            final var _ = room.ping().get(PING_WITHIN.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (final ExecutionException | TimeoutException refused) {
            return false;
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
