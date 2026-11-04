package eu.nordtal.season.smp.rail;

import static eu.nordtal.season.smp.rail.Track.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Reads a large world of rails tick by tick, as the server does, and measures the longest tick spent on rails.
 * The world is in memory, so this measures the networks' own work, not the reading of chunks. A tick's time is
 * the processor time the ticking thread spent: a pause of the garbage collector stops the whole server, and another
 * process on the machine can hold the processor, so neither is the rails' work. The time by the clock is reported
 * beside it.
 */
class RailLoadTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T12:00:00Z"), ZoneOffset.UTC);

    /** The most a tick may spend on rails, in nanoseconds. */
    private static final long MOST_PER_TICK = 5_000_000;

    /** The side of the square the track covers: 55 by 55 chunks. */
    private static final int SIDE = 55 * 16;

    /** Lines run east to west every few blocks, and lines north to south cross them every chunk or four. */
    private static final int LINES_EVERY = 4;

    private static final int CROSSINGS_EVERY = 64;

    private final Track track = new Track();
    private final List<StationRow> stations = new ArrayList<>();

    /** The world as the networks read it: the rails laid, without counting the reads. */
    private final Map<RailPos, RailShape> world = new HashMap<>();

    private final RailView view = view(world);

    private static RailView view(final Map<RailPos, RailShape> world) {
        return new RailView() {
            @Override
            public boolean readable(final RailPos pos) {
                return true;
            }

            @Override
            public @Nullable RailShape shape(final RailPos pos) {
                return world.get(pos);
            }
        };
    }

    private static StationRow station(final int x, final int z) {
        return new StationRow(
                new UUID(0, z), "nordtal", x, 64, z, RailShape.EAST_WEST.name(), new UUID(1, 0), null, null);
    }

    /**
     * Reads a small world once, so the first tick measured is not the one that loads the classes of the rails.
     */
    private static void warmUp() {
        final Track small = new Track();
        small.eastWest(0, 63, 64, 0);
        small.northSouth(32, 64, 1, 16);
        final Map<RailPos, RailShape> world = new HashMap<>(small.rails());
        final RailView view = view(world);
        final List<StationRow> stations = List.of(station(63, 0));
        final WorldNetworks first = new WorldNetworks();
        stations.forEach(first::addStation);
        ticks(first, () -> first.retrace(view, CLOCK));
        final RailGraph graph = first.networks().iterator().next().graph();
        final WorldNetworks restarted = WorldNetworks.restored(stations, graph.junctions(), stored(first));
        ticks(restarted, () -> restarted.retrace(view, CLOCK));
        final RailPos pos = at(10, 64, 0);
        final RailShape shape = Objects.requireNonNull(world.remove(pos));
        ticks(restarted, () -> restarted.recheck(List.of(pos), view, CLOCK));
        world.put(pos, shape);
        ticks(restarted, () -> restarted.recheck(List.of(pos), view, CLOCK));
    }

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    /** What one run of ticks took: how many, and the longest by the thread's processor time and by the clock. */
    private record Run(int ticks, long longest, long longestByClock) {
        double longestMillis() {
            return longest / 1e6;
        }
    }

    private void layTrack() {
        for (int z = 0; z < SIDE; z += LINES_EVERY) {
            track.eastWest(0, SIDE - 1, 64, z);
            stations.add(station(SIDE - 1, z));
        }
        // A spine on the west joins every line; the crossings make a junction wherever they meet one.
        track.northSouth(-1, 64, -1, SIDE);
        for (int x = CROSSINGS_EVERY; x < SIDE - 1; x += CROSSINGS_EVERY) {
            for (int z = 0; z < SIDE; z++) {
                if (z % LINES_EVERY != 0) {
                    track.rail(x, 64, z, RailShape.NORTH_SOUTH);
                }
            }
        }
    }

    /** Runs {@code job} to its end as the server's ticks do, each with its share of time, and tidies after. */
    private static Run ticks(final WorldNetworks networks, final Supplier<@Nullable RailRecheck> starting) {
        long longest = 0;
        long longestByClock = 0;
        int ticks = 0;
        long spent = THREADS.getCurrentThreadCpuTime();
        long started = System.nanoTime();
        final @Nullable RailRecheck job = starting.get();
        assertNotNull(job);
        while (true) {
            final long deadline = started + RailNetworks.NANOS_PER_TICK;
            final boolean done = job.step(RailNetworks.RAILS_PER_TICK, deadline);
            networks.tidy(RailNetworks.RAILS_PER_TICK);
            longest = Math.max(longest, THREADS.getCurrentThreadCpuTime() - spent);
            longestByClock = Math.max(longestByClock, System.nanoTime() - started);
            ticks++;
            if (done) {
                job.change();
                break;
            }
            spent = THREADS.getCurrentThreadCpuTime();
            started = System.nanoTime();
        }
        // The rails left behind go a tick at a time too.
        for (int tidying = 0; tidying < 10_000; tidying++) {
            spent = THREADS.getCurrentThreadCpuTime();
            started = System.nanoTime();
            networks.tidy(RailNetworks.RAILS_PER_TICK);
            longest = Math.max(longest, THREADS.getCurrentThreadCpuTime() - spent);
            longestByClock = Math.max(longestByClock, System.nanoTime() - started);
        }
        return new Run(ticks, longest, longestByClock);
    }

    private static Map<UUID, RailEdge> stored(final WorldNetworks networks) {
        final Map<UUID, RailEdge> edges = new LinkedHashMap<>();
        for (final RailLeg leg : networks.legs()) {
            final @Nullable RailEdge edge = leg.edge();
            if (edge != null) {
                edges.put(leg.id(), edge);
            }
        }
        return edges;
    }

    @Test
    void noTickSpendsMoreThanFiveMillisecondsOnALargeWorld() {
        warmUp();
        layTrack();
        world.putAll(track.rails());
        final int rails = world.size();
        final Set<Long> chunks = new HashSet<>();
        world.keySet().forEach(pos -> chunks.add(((long) (pos.z() >> 4) << 32) ^ (pos.x() >> 4)));
        assertTrue(rails > 200_000, rails + " rails");
        assertTrue(chunks.size() > 3_000, chunks.size() + " chunks");

        // The first read of a world, with only the stations known.
        final WorldNetworks first = new WorldNetworks();
        stations.forEach(first::addStation);
        final Run traced = ticks(first, () -> first.retrace(view, CLOCK));
        assertEquals(1, first.networks().size(), "the spine joins every line");
        final RailGraph graph = first.networks().iterator().next().graph();
        assertTrue(graph.junctions().size() > 2_000, graph.junctions().size() + " junctions");

        // A restart: the stored graph comes back, and the whole world is read once more.
        final WorldNetworks restarted = WorldNetworks.restored(stations, graph.junctions(), stored(first));
        final Run restart = ticks(restarted, () -> restarted.retrace(view, CLOCK));

        // Block changes: a rail broken in a long line and laid again, and a junction broken and laid again.
        final List<Run> changes = new ArrayList<>();
        for (final RailPos pos : List.of(at(400, 64, 400), at(CROSSINGS_EVERY * 5, 64, 400))) {
            final RailShape shape = Objects.requireNonNull(world.remove(pos));
            changes.add(ticks(restarted, () -> restarted.recheck(List.of(pos), view, CLOCK)));
            world.put(pos, shape);
            changes.add(ticks(restarted, () -> restarted.recheck(List.of(pos), view, CLOCK)));
        }
        final long changed = changes.stream().mapToLong(Run::longest).max().orElseThrow();
        final long byClock = Stream.concat(Stream.of(traced, restart), changes.stream())
                .mapToLong(Run::longestByClock)
                .max()
                .orElseThrow();

        System.out.printf(
                "rail load: %d rails over %d chunks, %d junctions; longest tick: first read %.2f ms over %d ticks,"
                        + " restart %.2f ms over %d ticks, block changes %.2f ms over %s ticks;"
                        + " by the clock at most %.2f ms%n",
                rails,
                chunks.size(),
                graph.junctions().size(),
                traced.longestMillis(),
                traced.ticks(),
                restart.longestMillis(),
                restart.ticks(),
                changed / 1e6,
                changes.stream().map(run -> String.valueOf(run.ticks())).toList(),
                byClock / 1e6);
        assertTrue(traced.longest() < MOST_PER_TICK, "first read: " + traced.longestMillis() + " ms");
        assertTrue(restart.longest() < MOST_PER_TICK, "restart: " + restart.longestMillis() + " ms");
        assertTrue(changed < MOST_PER_TICK, "block changes: " + changed / 1e6 + " ms");
    }
}
