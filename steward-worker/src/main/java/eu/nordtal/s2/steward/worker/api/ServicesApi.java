package eu.nordtal.s2.steward.worker.api;

import eu.nordtal.s2.common.online.OnlineCount;
import eu.nordtal.s2.common.online.OnlineDirectory;

import org.jetbrains.annotations.NotNull;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Feeds {@code /api/services} the player counts steward/86 asked for: {@code smp},
 * {@code hunger-games} and {@code limbo} each their own, {@code network-control} the network's
 * total.
 *
 * <h2>The contract with whoever assembles a row</h2>
 * {@link #players()} returns exactly the subjects it currently trusts, keyed by compose service
 * name. <b>A subject missing from this map means "unknown", never {@code 0}</b> - whoever builds the
 * JSON row for {@code /api/services} must omit the field entirely for a missing key rather than
 * default it to zero. This is the same rule {@link eu.nordtal.s2.steward.worker.ops.ImageResult.State#UNKNOWN}
 * already enforces for image drift and {@code steward-ui/frontend/src/lib/health.ts} enforces for
 * the traffic light, applied here to a player count instead.
 *
 * <h2>Why the whole map can go missing at once, not just one entry</h2>
 * network-control is the <em>only</em> writer of every row in {@code online_count} - not just its
 * own. A stopped {@code smp} container legitimately reads {@code 0} (nobody can be connected to it,
 * and the proxy is still there to say so); network-control itself being down or still starting is a
 * different fact, and it takes every subject's freshness with it at once, {@code network-control}'s
 * own included. {@link #STALE_AFTER} is what tells the two apart - a row older than that is treated
 * exactly like no row at all.
 *
 * <p>Not wired into the route yet: this class only prepares the answer. Whichever code builds each
 * {@code /api/services} row decides how to fold {@link #players()} into it.
 */
public final class ServicesApi {

    /**
     * How old a row may be before it is no longer trusted and is treated as absent.
     *
     * <p>Three times {@link OnlineDirectory#WRITE_INTERVAL}: one missed write is noise - a slow GC
     * pause on the proxy, one tick that raced a database hiccup, exactly the case
     * {@code OnlineWriter} already logs and moves past without retrying out of turn. Three in a row
     * is network-control no longer writing at all, which is the state this cutoff exists to catch
     * before a stale number sits on the dashboard looking like a live one.
     */
    static final Duration STALE_AFTER = OnlineDirectory.WRITE_INTERVAL.multipliedBy(3);

    private final OnlineDirectory online;
    private final Clock clock;

    public ServicesApi(final @NotNull OnlineDirectory online) {
        this(online, Clock.systemUTC());
    }

    /** Package-visible so a test can hold time still instead of racing {@link #STALE_AFTER}. */
    ServicesApi(final @NotNull OnlineDirectory online, final @NotNull Clock clock) {
        this.online = Objects.requireNonNull(online, "online");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @return player counts for every subject with a fresh enough row - {@code smp},
     *         {@code hunger-games}, {@code limbo} and {@code network-control} when network-control
     *         has written for them recently, fewer than that otherwise. Never contains a key for a
     *         subject it does not trust; see the class documentation for what a caller must do with
     *         that absence
     */
    public @NotNull Map<String, Integer> players() {
        final Instant now = clock.instant();
        final Map<String, Integer> fresh = new LinkedHashMap<>();
        for (final Map.Entry<String, OnlineCount> entry : online.current().entrySet()) {
            final OnlineCount count = entry.getValue();
            if (Duration.between(count.updated(), now).compareTo(STALE_AFTER) <= 0) {
                fresh.put(entry.getKey(), count.players());
            }
        }
        return fresh;
    }
}
