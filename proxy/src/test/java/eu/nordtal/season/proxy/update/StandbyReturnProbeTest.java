package eu.nordtal.season.proxy.update;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.proxy.PhaseServers;
import eu.nordtal.season.proxy.ProxyRole;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whom the standby asks whether the network is back: the live proxy, never the public address.
 *
 * Caddy answers the public address all the time and hands it to the standby while the live proxy is down.
 */
class StandbyReturnProbeTest {

    private static final InetSocketAddress PUBLIC = InetSocketAddress.createUnresolved("play.nordtal.eu", 25565);
    private static final InetSocketAddress LIVE = InetSocketAddress.createUnresolved("proxy", 25565);
    private static final Logger LOG = LoggerFactory.getLogger(StandbyReturnProbeTest.class);

    private final List<InetSocketAddress> transfers = new ArrayList<>();
    private final MovingClock clock = new MovingClock();
    private boolean liveAnswers;

    @Test
    void aParkedPlayerGoesHomeAsSoonAsTheLiveProxyAnswersThoughCaddyAnsweredThroughout() {
        final StandbyReturn standby = new StandbyReturn(
                new Immediately(),
                proxyHolding(player()),
                LOG,
                new NoSeats(),
                ProxyRole.of(true),
                PUBLIC,
                LIVE,
                clock,
                homecoming(),
                (address, timeout) -> PUBLIC.equals(address) || (LIVE.equals(address) && liveAnswers));

        standby.check();
        clock.pass(StandbyReturn.INTERVAL);
        liveAnswers = true;
        standby.check();
        clock.pass(StandbyReturn.SETTLE);
        standby.check();

        assertEquals(List.of(PUBLIC), transfers, "the transfer names the address players type");
    }

    private Player player() {
        final UUID id = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Player.class}, (self, method, arguments) -> {
                    return switch (method.getName()) {
                        case "getUniqueId" -> id;
                        case "getUsername" -> "parked";
                        case "transferToHost" -> {
                            transfers.add((InetSocketAddress) arguments[0]);
                            yield null;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }

    private static ProxyServer proxyHolding(final Player player) {
        return (ProxyServer) Proxy.newProxyInstance(
                StandbyReturnProbeTest.class.getClassLoader(),
                new Class<?>[] {ProxyServer.class},
                (self, method, arguments) -> {
                    if ("getAllPlayers".equals(method.getName())) {
                        return List.of(player);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Homecoming homecoming() {
        return new Homecoming(
                LOG,
                eu.nordtal.season.proxy.ProxyRenderer.RENDERER,
                new eu.nordtal.season.proxy.gate.LoginRoster(),
                new PhaseServers("limbo", "limbo-standby", "hunger-games", "smp"),
                eu.nordtal.season.database.update.RunRecorder.NONE);
    }

    /** Runs every beat at once, so the ten seconds of notice take none. */
    private static final class Immediately implements Scheduler {

        @Override
        public void execute(final Runnable work) {
            work.run();
        }

        @Override
        public Task after(final Duration delay, final Runnable work) {
            work.run();
            return () -> {};
        }
    }

    private static final class NoSeats implements SwapStore {

        @Override
        public void seat(final UUID player, final String server, final Instant now) {}

        @Override
        public List<Seat> takeAllSeats() {
            return List.of();
        }

        @Override
        public void reportStandby(final int players, final Instant now) {}
    }

    private static final class MovingClock extends Clock {

        private Instant now = Instant.parse("2026-10-17T00:00:00Z");

        void pass(final Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
