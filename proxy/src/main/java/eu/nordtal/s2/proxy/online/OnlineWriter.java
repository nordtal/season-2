package eu.nordtal.s2.proxy.online;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import eu.nordtal.s2.common.online.OnlineDirectory;
import eu.nordtal.s2.common.online.OnlineRoster;
import eu.nordtal.s2.proxy.PhaseServers;
import eu.nordtal.s2.proxy.ProxyRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Writes the proxy's counts and roster into {@code online_count} and {@code online_player}.
 *
 * Every tick writes both from one pass; a failed write is logged and the next due tick is the retry.
 */
public final class OnlineWriter {

    private final ProxyServer proxy;
    private final PhaseServers servers;
    private final OnlineDirectory online;
    private final OnlineRoster roster;
    private final ProxyRole role;
    private final Logger logger;
    private final Clock clock;

    /** How often {@link #tick()} is called, which is not how often it writes. */
    public static final Duration TICK = Duration.ofSeconds(1);

    /** Whether a run is close enough to a stop that a ten-second-old number is no use. */
    private volatile BooleanSupplier hurry = () -> false;

    /** When this last wrote, so the ordinary cadence survives being ticked every second. */
    private volatile @Nullable Instant lastWrite;

    public OnlineWriter(
            final ProxyServer proxy,
            final PhaseServers servers,
            final OnlineDirectory online,
            final OnlineRoster roster,
            final ProxyRole role,
            final Logger logger) {
        this(proxy, servers, online, roster, role, logger, Clock.systemUTC());
    }

    public OnlineWriter(
            final ProxyServer proxy,
            final PhaseServers servers,
            final OnlineDirectory online,
            final OnlineRoster roster,
            final ProxyRole role,
            final Logger logger,
            final Clock clock) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.online = Objects.requireNonNull(online, "online");
        this.roster = Objects.requireNonNull(roster, "roster");
        this.role = Objects.requireNonNull(role, "role");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Sets the question whether a run is about to stop something, normally {@code Evacuation::isAnyMoving}. */
    public void whenHurrying(final BooleanSupplier hurry) {
        this.hurry = Objects.requireNonNull(hurry, "hurry");
    }

    /** Writes on {@link OnlineDirectory#WRITE_INTERVAL}, or every second while a run is about to stop something. */
    public void tick() {
        if (role.isStandby()) {
            return;
        }
        final Instant now = clock.instant();
        if (!isDue(lastWrite, now, hurrying())) {
            return;
        }
        lastWrite = now;
        writeCounts();
        writeRoster();
    }

    private boolean hurrying() {
        try {
            return hurry.getAsBoolean();
        } catch (final RuntimeException failure) {
            // A pass that cannot answer falls back to the ordinary cadence.
            logger.debug("Could not tell whether a run is imminent; writing on the usual cadence", failure);
            return false;
        }
    }

    /**
     * Whether this tick writes; the first tick always does.
     *
     * @param lastWrite when this last wrote, or {@code null} on the very first tick
     */
    static boolean isDue(final @Nullable Instant lastWrite, final Instant now, final boolean hurrying) {
        if (lastWrite == null || hurrying) {
            return true;
        }
        return !Duration.between(lastWrite, now)
                .minus(OnlineDirectory.WRITE_INTERVAL)
                .isNegative();
    }

    /** Writes both tables now, unless this is the standby, whose count goes into {@code proxy_standby_state}. */
    public void write() {
        if (role.isStandby()) {
            return;
        }
        lastWrite = clock.instant();
        writeCounts();
        writeRoster();
    }

    private void writeCounts() {
        try {
            online.write(OnlineCounts.of(proxy.getPlayerCount(), playersByServer()));
        } catch (final RuntimeException failure) {
            // The next tick is the retry, and a stale row beats a blank dashboard.
            logger.warn(
                    "Could not write online player counts; the service list keeps showing the " + "last numbers it saw",
                    failure);
        }
    }

    private void writeRoster() {
        try {
            roster.replace(present());
        } catch (final RuntimeException failure) {
            // A roster one tick behind shows a face too many; the count next to it is unaffected.
            logger.warn(
                    "Could not write the online player list; the service list keeps showing " + "the last names it saw",
                    failure);
        }
    }

    /** Everyone the proxy currently has, with a {@code null} subject for a player on no backend yet. */
    private List<OnlineRoster.Presence> present() {
        final List<OnlineRoster.Presence> connected = new ArrayList<>();
        for (final Player player : proxy.getAllPlayers()) {
            connected.add(new OnlineRoster.Presence(
                    player.getUniqueId(),
                    player.getUsername(),
                    player.getCurrentServer()
                            .map(server -> server.getServerInfo().getName())
                            .orElse(null)));
        }
        return connected;
    }

    private Map<String, Integer> playersByServer() {
        final Map<String, Integer> byServer = new LinkedHashMap<>();
        // The standby is usually absent; during a swap it holds everybody who was parked.
        for (final String name :
                List.of(servers.smp(), servers.hungerGames(), servers.limbo(), servers.limboStandby())) {
            proxy.getServer(name)
                    .ifPresent(server ->
                            byServer.put(name, server.getPlayersConnected().size()));
        }
        return byServer;
    }
}
