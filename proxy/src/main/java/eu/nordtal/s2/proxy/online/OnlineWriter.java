package eu.nordtal.s2.proxy.online;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import eu.nordtal.s2.common.online.OnlineDirectory;
import eu.nordtal.s2.common.online.OnlineRoster;
import eu.nordtal.s2.proxy.routing.PhaseServers;
import eu.nordtal.s2.proxy.routing.ProxyRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;

/**
 * Writes what the proxy currently sees into {@code online_count}, on a timer (steward/86).
 *
 * <h2>Why the proxy, and not each backend</h2>
 * proxy is the one process that already knows every connection and which backend it is
 * on, without adding anything up itself - {@code ProxyServer.getPlayerCount()} is the network total
 * {@link eu.nordtal.s2.proxy.gate.LoginGate} already enforces against, and
 * {@code RegisteredServer.getPlayersConnected().size()} is what
 * {@link eu.nordtal.s2.proxy.ping.Placeholders} already puts in the MOTD. This class writes
 * the same two calls out to a table instead of a ping response, so steward-worker - which has no
 * Velocity API and no reason to have one - can read them.
 *
 * <h2>A failed write is logged and dropped, never retried out of turn</h2>
 * The next tick is the retry, the same rule {@code SnapshotStore#refresh} and
 * {@code PlaytimeWriter#flush} both already follow: a database hiccup costs one row's freshness, and
 * nothing here should try harder than the schedule that calls it.
 *
 * <p>The arithmetic itself is {@link OnlineCounts#of}, kept static and free of every Velocity type
 * so it can be tested without a fake {@code ProxyServer}; this class is the thin, unavoidably
 * Velocity-shaped layer around it.
 *
 * <h2>How many, and who - one tick, two tables (steward/111)</h2>
 * {@code online_count} is numbers and nothing else, by its own migration's decision, so the names
 * live in {@code online_player} next to it and are written from this same method: one pass over the
 * proxy, both tables, on {@link OnlineDirectory#WRITE_INTERVAL}. A second timer would let the count
 * and the list describe two different moments for no gain at all.
 *
 * <p>The two writes are guarded separately, which is the one place they are not treated as one
 * thing: a roster write that fails must not cost the counts their tick, since a number with no
 * faces is most of what the dashboard shows and faces with no number is none of it.
 *
 * <h2>Ten seconds is a dashboard's cadence and not a run's (season-2-ops/122)</h2>
 * steward-worker waits, after the countdown, for a service to be free of players before it stops
 * it - and gives up after ten. A number that is itself up to ten seconds old cannot answer that
 * question at all: it would still be describing the moment before the players were moved, so the
 * run would wait the whole cap every time and then report a count that was never true.
 *
 * <p>So {@link #tick()} is called every second and decides for itself. It writes on
 * {@link OnlineDirectory#WRITE_INTERVAL} as it always did, <b>unless</b> a run is about to stop
 * something, in which case it writes every second for the ten or twenty seconds that lasts. Still
 * one timer and still one pass over the proxy, which is what keeps the count and the roster
 * describing the same moment - the thing a second timer would have cost.</p>
 */
public final class OnlineWriter {

    private final ProxyServer proxy;
    private final PhaseServers servers;
    private final OnlineDirectory online;
    private final OnlineRoster roster;
    private final ProxyRole role;
    private final Logger logger;
    private final Clock clock;

    /**
     * How often {@link #tick()} is called, which is not how often it writes.
     *
     * <p>One second. The scheduled task is cheap by construction: on all but the ten seconds of a
     * run it does two comparisons and returns.</p>
     */
    public static final Duration TICK = Duration.ofSeconds(1);

    /** Whether a run is close enough to a stop that a ten-second-old number is no use. */
    private volatile BooleanSupplier hurry = () -> false;

    /** When this last wrote, so the ordinary cadence survives being ticked ten times as often. */
    private volatile Instant lastWrite;

    /**
     * @param role which of the two proxies this process is. A standby writes nothing at all - see
     *             {@link #write()}
     */
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

    /**
     * @param hurry asked every tick whether a run is about to stop something; in production this is
     *              {@code Evacuation::isAnyMoving}. Set after construction because the watch that
     *              answers it is built from a pool this class never sees - the same reason
     *              {@code PackStation#whenUpdating} is set the same way
     */
    public void whenHurrying(final BooleanSupplier hurry) {
        this.hurry = Objects.requireNonNull(hurry, "hurry");
    }

    /**
     * One tick of the one-second timer: writes, or decides it is not due yet.
     *
     * <p>The decision itself is {@link #isDue}, which is where the two cadences are and is the part
     * worth asserting. Everything here is the clock and the two writes.</p>
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
            // The watch behind this reads a database row. A pass that cannot answer is a pass that
            // falls back to the ordinary cadence, never one that stops writing counts altogether.
            logger.debug("Could not tell whether a run is imminent; writing on the usual cadence", failure);
            return false;
        }
    }

    /**
     * Whether this tick writes.
     *
     * <p>Static and free of every Velocity type, for the reason {@link OnlineCounts#of} is: this is
     * the whole of season-2-ops/122's half of this class, and it is two comparisons that a test can
     * hold without a proxy, a pool or ten real seconds.</p>
     *
     * @param lastWrite when this last wrote, or {@code null} on the very first tick - which always
     *                  writes, because a deployment whose first row appears ten seconds after start
     *                  is one where every dashboard says "nothing known" for ten seconds
     * @param hurrying  whether a run is close enough to a stop that the ordinary cadence is no use
     */
    static boolean isDue(final Instant lastWrite, final Instant now, final boolean hurrying) {
        if (lastWrite == null || hurrying) {
            return true;
        }
        return !Duration.between(lastWrite, now)
                .minus(OnlineDirectory.WRITE_INTERVAL)
                .isNegative();
    }

    /**
     * Called from the proxy's scheduler on {@link OnlineDirectory#WRITE_INTERVAL}.
     *
     * <h2>The standby writes nothing, and that is not an optimisation (season-2-ops/121)</h2>
     * These two tables answer "who is on the network". There is one network and, for a minute
     * during a proxy swap, two proxies - and the second one would be answering the same question
     * with a different number, every ten seconds, overwriting the first. For most of the standby's
     * life its honest answer is zero, so the dashboard would flicker between the truth and nothing,
     * and {@code steward-worker} asking whether the standby is empty could be handed the live
     * proxy's row.
     *
     * <p>What the standby writes instead is {@code proxy_standby_state}, which is its own count in
     * its own row and cannot be confused with anybody's - see {@link
     * eu.nordtal.s2.proxy.update.StandbyReturn}. During the window when the live proxy is actually
     * <em>down</em>, these tables go stale rather than to zero; that is the right direction, since
     * the players are still on the network and the {@code updated} column says how old the answer
     * is.</p>
     */
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
            // Nothing is retried here and nothing is cleared: the next tick is the retry, and a
            // slightly stale row beats a dashboard that goes blank over one missed write.
            logger.warn(
                    "Could not write online player counts; the service list keeps showing the " + "last numbers it saw",
                    failure);
        }
    }

    private void writeRoster() {
        try {
            roster.replace(present());
        } catch (final RuntimeException failure) {
            // Same rule as above, and the same retry: the next tick. A roster that is one tick
            // behind shows a face too many for ten seconds; the count next to it is unaffected.
            logger.warn(
                    "Could not write the online player list; the service list keeps showing " + "the last names it saw",
                    failure);
        }
    }

    /**
     * Everyone the proxy currently has, with the backend they are on where there is one.
     *
     * <p>A player with no current server is included with a {@code null} subject rather than left
     * out: {@code getPlayerCount()} counts them, so dropping them here would make the list one
     * shorter than the number beside it for no reason a reader could see. They are mid-transfer or
     * one step past login, and where they are is the only thing nobody knows yet.
     */
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
        // The standby is in the list and is usually absent, which is the point: getServer returns
        // empty while it is not running, so it contributes no row - and during a swap, when it is
        // where the players actually are, it is the only row that would not have been zero
        // (season-2-ops/120). A count that says nobody is online while everybody is parked is
        // worse than no count.
        for (final String name :
                List.of(servers.smp(), servers.hungerGames(), servers.limbo(), servers.limboStandby())) {
            proxy.getServer(name)
                    .ifPresent(server ->
                            byServer.put(name, server.getPlayersConnected().size()));
        }
        return byServer;
    }
}
