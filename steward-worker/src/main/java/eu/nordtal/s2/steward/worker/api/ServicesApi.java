package eu.nordtal.s2.steward.worker.api;

import eu.nordtal.s2.common.online.OnlineCount;
import eu.nordtal.s2.common.online.OnlineDirectory;
import eu.nordtal.s2.common.online.OnlinePlayer;
import eu.nordtal.s2.common.online.OnlineRoster;

import org.jetbrains.annotations.NotNull;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Feeds {@code /api/services} the player counts steward/86 asked for and the names steward/111
 * added: {@code smp}, {@code hunger-games} and {@code limbo} each their own, {@code network-control}
 * the network's total and the network's whole list.
 *
 * <h2>The contract with whoever assembles a row</h2>
 * {@link #read()} returns exactly the subjects it currently trusts, keyed by compose service name.
 * <b>A subject missing from those maps means "unknown", never {@code 0} and never an empty list</b> -
 * whoever builds the JSON row for {@code /api/services} must omit the field entirely for a missing
 * key rather than default it. This is the same rule {@link eu.nordtal.s2.steward.worker.ops.ImageResult.State#UNKNOWN}
 * already enforces for image drift and {@code steward-ui/frontend/src/lib/health.ts} enforces for
 * the traffic light, applied here to a player count and a player list instead.
 *
 * <h2>Why the whole answer can go missing at once, not just one entry</h2>
 * network-control is the <em>only</em> writer of every row in {@code online_count} and
 * {@code online_player} - not just its own. A stopped {@code smp} container legitimately reads
 * {@code 0} (nobody can be connected to it, and the proxy is still there to say so);
 * network-control itself being down or still starting is a different fact, and it takes every
 * subject's freshness with it at once, {@code network-control}'s own included. {@link #STALE_AFTER}
 * is what tells the two apart - a row older than that is treated exactly like no row at all, for a
 * count and for a player alike.
 *
 * <h2>Both halves are read at one moment, and in one place</h2>
 * {@link #read()} takes the clock once and applies it to both tables, and {@code WorkerApi} calls it
 * once per response rather than once per row. Two rows of one answer must not be able to disagree
 * about the same instant - which is the same promise {@code OnlineWriter} makes at the other end by
 * writing both tables from one pass over the proxy.
 *
 * <h2>An empty roster is not the same claim as a missing count</h2>
 * Nobody online is the normal case on a dev host: {@code online_player} is then simply empty, no
 * subject has a list, and no row carries a {@code roster} field. That is not a claim that nobody is
 * playing - the <em>count</em> is what says that, and it says it with a real {@code 0}. The list
 * only ever enriches a number that is already there, which is why its absence needs no marker of
 * its own (steward/64: the {@code +N} is the general case, a face is the enrichment).
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

    /**
     * The proxy's own subject - the network total in {@code online_count}, and here also the key
     * under which the network's whole player list is offered.
     *
     * <p>Written out rather than imported: {@code OnlineCounts.NETWORK_CONTROL} lives in
     * network-control, which steward-worker neither depends on nor should. The string is the compose
     * service name, and the service list is keyed by compose service names throughout.
     */
    static final String NETWORK_CONTROL = "network-control";

    /**
     * Two people called {@code Ada} and {@code ada} still have to come back in the same order twice
     * running, which is why the uuid is the tie-break rather than nothing at all.
     */
    private static final Comparator<OnlinePlayer> BY_NAME =
            Comparator.comparing((OnlinePlayer player) -> player.name().toLowerCase(Locale.ROOT))
                    .thenComparing(player -> player.uuid().toString());

    private final OnlineDirectory online;
    private final OnlineRoster roster;
    private final Clock clock;

    public ServicesApi(final @NotNull OnlineDirectory online, final @NotNull OnlineRoster roster) {
        this(online, roster, Clock.systemUTC());
    }

    /** Package-visible so a test can hold time still instead of racing {@link #STALE_AFTER}. */
    ServicesApi(final @NotNull OnlineDirectory online, final @NotNull OnlineRoster roster,
                final @NotNull Clock clock) {
        this.online = Objects.requireNonNull(online, "online");
        this.roster = Objects.requireNonNull(roster, "roster");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Both tables, judged against one instant.
     *
     * @return the counts and the lists for every subject with a fresh enough row, and nothing for
     *         any subject without one. Never contains a key it does not vouch for; see the class
     *         documentation for what a caller must do with that absence
     */
    public @NotNull Online read() {
        final Instant now = clock.instant();
        return new Online(freshCounts(now), freshRoster(now));
    }

    private Map<String, Integer> freshCounts(final Instant now) {
        final Map<String, Integer> fresh = new LinkedHashMap<>();
        for (final Map.Entry<String, OnlineCount> entry : online.current().entrySet()) {
            final OnlineCount count = entry.getValue();
            if (isFresh(count.updated(), now)) {
                fresh.put(entry.getKey(), count.players());
            }
        }
        return fresh;
    }

    /**
     * The fresh players, grouped the way the service table is keyed.
     *
     * <p>Every fresh player appears under {@value #NETWORK_CONTROL}, because that row <em>is</em>
     * the network, and additionally under the backend they are on when the writer knew one. A player
     * the proxy has and no backend does yet (mid-transfer, one step past login) therefore counts
     * towards the network's list and towards no server's - the same way the proxy's own count
     * already has them and no backend's count does.
     *
     * <p>Sorted by name within each subject, and not left in whatever order the query returned:
     * steward/64 draws the first three faces and collapses the rest into a {@code +N}, so an
     * arbitrary order would reshuffle which three people are shown on every refresh for no reason
     * anybody could see.
     */
    private Map<String, List<OnlinePlayer>> freshRoster(final Instant now) {
        final Map<String, List<OnlinePlayer>> bySubject = new LinkedHashMap<>();
        final List<OnlinePlayer> network = new ArrayList<>();
        for (final OnlinePlayer player : roster.current()) {
            if (!isFresh(player.updated(), now)) {
                continue;
            }
            network.add(player);
            player.on().ifPresent(subject ->
                    bySubject.computeIfAbsent(subject, key -> new ArrayList<>()).add(player));
        }
        if (!network.isEmpty()) {
            bySubject.put(NETWORK_CONTROL, network);
        }
        bySubject.values().forEach(players -> players.sort(BY_NAME));
        return bySubject;
    }

    private static boolean isFresh(final Instant updated, final Instant now) {
        return Duration.between(updated, now).compareTo(STALE_AFTER) <= 0;
    }

    /**
     * One reading of both tables - what {@code /api/services} folds into its rows.
     *
     * @param counts  subject to player count, for the subjects with a fresh row. A key that is not
     *                here is unknown, not zero
     * @param roster  subject to the players on it, for the subjects that have any. A key that is not
     *                here has no list - which is not a claim that nobody is on it
     */
    public record Online(Map<String, Integer> counts, Map<String, List<OnlinePlayer>> roster) {

        /** What a deployment with no database behind the API answers - absence, not zeroes. */
        public static final Online NONE = new Online(Map.of(), Map.of());

        public Online {
            counts = Map.copyOf(counts);
            final Map<String, List<OnlinePlayer>> frozen = new LinkedHashMap<>();
            roster.forEach((subject, players) -> frozen.put(subject, List.copyOf(players)));
            roster = Map.copyOf(frozen);
        }
    }
}
