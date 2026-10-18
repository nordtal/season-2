package eu.nordtal.season.proxy.online;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import eu.nordtal.season.database.online.OnlineDirectory;
import eu.nordtal.season.database.online.OnlineRoster;
import eu.nordtal.season.proxy.PhaseServers;
import eu.nordtal.season.proxy.ProxyRole;
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
 * Writes the player counts and the players into {@code online_count} and {@code online_player}.
 * A failed write is logged.
 */
public final class OnlineWriter {

    private final ProxyServer proxy;
    private final PhaseServers servers;
    private final OnlineDirectory online;
    private final OnlineRoster roster;
    private final ProxyRole role;
    private final Logger logger;
    private final Clock clock;

    /** The interval between two calls of {@link #tick()}. */
    public static final Duration TICK = Duration.ofSeconds(1);

    /** Whether an update run is about to stop a server. */
    private volatile BooleanSupplier hurry = () -> false;

    /** The time of the last write. */
    private volatile @Nullable Instant lastWrite;

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

    /** Sets the check whether an update run is about to stop a server. */
    public void whenHurrying(final BooleanSupplier hurry) {
        this.hurry = Objects.requireNonNull(hurry, "hurry");
    }

    /**
     * Writes every {@link OnlineDirectory#WRITE_INTERVAL}.
     * While an update run stops a server it writes every second.
     */
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
            logger.debug("Could not tell whether a run is imminent; writing on the usual cadence", failure);
            return false;
        }
    }

    /** Returns whether this tick writes. The first tick writes. */
    static boolean isDue(final @Nullable Instant lastWrite, final Instant now, final boolean hurrying) {
        if (lastWrite == null || hurrying) {
            return true;
        }
        return !Duration.between(lastWrite, now)
                .minus(OnlineDirectory.WRITE_INTERVAL)
                .isNegative();
    }

    /** Writes both tables. The standby writes its count into {@code proxy_standby_state}. */
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
            logger.warn(
                    "Could not write online player counts; the service list keeps showing the " + "last numbers it saw",
                    failure);
        }
    }

    private void writeRoster() {
        try {
            roster.replace(present());
        } catch (final RuntimeException failure) {
            logger.warn(
                    "Could not write the online player list; the service list keeps showing " + "the last names it saw",
                    failure);
        }
    }

    /** Returns the connected players. A player without a server has a {@code null} subject. */
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
        for (final String name :
                List.of(servers.smp(), servers.hungerGames(), servers.limbo(), servers.limboStandby())) {
            proxy.getServer(name)
                    .ifPresent(server ->
                            byServer.put(name, server.getPlayersConnected().size()));
        }
        return byServer;
    }
}
