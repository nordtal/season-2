package eu.nordtal.season.smp.rail;

import static eu.nordtal.season.smp.rail.Track.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class RailTraceTest {

    /** Tells the trace which rails are station rails, which are settled, and what {@code same} finds. */
    private static RailTrace.Known known(
            final Track track,
            final Set<RailPos> stations,
            final Predicate<RailPos> settled,
            final Function<RailLeg, @Nullable RailLeg> same) {
        return new RailTrace.Known() {
            @Override
            public @Nullable RailShape station(final RailPos pos) {
                return stations.contains(pos) ? track.rails().getOrDefault(pos, RailShape.EAST_WEST) : null;
            }

            @Override
            public boolean settled(final RailPos pos) {
                return settled.test(pos);
            }

            @Override
            public @Nullable RailLeg same(final RailLeg leg) {
                return same.apply(leg);
            }
        };
    }

    private static RailTrace traced(final Track track, final RailPos... stations) {
        final RailTrace trace = new RailTrace(track, known(track, Set.of(stations), _ -> false, _ -> null));
        for (final RailPos station : stations) {
            trace.look(station);
        }
        for (int steps = 0; steps < 1_000; steps++) {
            if (trace.step(100, Long.MAX_VALUE)) {
                return trace;
            }
        }
        throw new AssertionError("the trace did not end");
    }

    private static Set<RailLeg.Kind> kinds(final RailTrace trace) {
        return trace.traced().stream().map(RailLeg::kind).collect(Collectors.toSet());
    }

    @Test
    void parallelTracksSideBySideStayApart() {
        final Track track = new Track().eastWest(0, 9, 64, 0).eastWest(0, 9, 64, 1);

        final RailTrace trace = traced(track, at(0, 64, 0), at(9, 64, 0), at(0, 64, 1), at(9, 64, 1));

        assertEquals(2, trace.traced().size());
        for (final RailLeg leg : trace.traced()) {
            assertEquals(
                    leg.from().z(), java.util.Objects.requireNonNull(leg.to()).z(), "a leg stays on its own track");
            assertEquals(8, leg.rails().size());
        }
        assertEquals(Set.of(), trace.junctions());
    }

    @Test
    void aRailTurnedAwayWhileTracedEndsTheTrackAndIsNoJunction() {
        final Track track = new Track().eastWest(0, 40, 64, 0);
        // The rail at 20 and both beside it turn north to south just as the trace first reads it.
        final RailView turning = new RailView() {
            private boolean turned;

            @Override
            public boolean readable(final RailPos pos) {
                return true;
            }

            @Override
            public @Nullable RailShape shape(final RailPos pos) {
                if (!turned && pos.equals(at(20, 64, 0))) {
                    turned = true;
                    track.rail(19, 64, 0, RailShape.NORTH_SOUTH)
                            .rail(20, 64, 0, RailShape.NORTH_SOUTH)
                            .rail(21, 64, 0, RailShape.NORTH_SOUTH)
                            .rail(20, 64, -1, RailShape.NORTH_SOUTH)
                            .rail(20, 64, 1, RailShape.NORTH_SOUTH);
                }
                return track.shape(pos);
            }
        };
        final RailTrace trace =
                new RailTrace(turning, known(track, Set.of(at(0, 64, 0), at(40, 64, 0)), _ -> false, _ -> null));
        trace.look(at(0, 64, 0));
        trace.look(at(40, 64, 0));

        for (int steps = 0; steps < 1_000 && !trace.step(100, Long.MAX_VALUE); steps++) {}

        assertEquals(Set.of(), trace.junctions(), "a rail with two connections is no junction");
        assertTrue(trace.traced().stream().noneMatch(leg -> at(20, 64, 0).equals(leg.to())));
    }

    @Test
    void aBrokenRailEndsTheTrackOnEachSide() {
        final Track track = new Track().eastWest(0, 9, 64, 0).remove(5, 64, 0);

        final RailTrace trace = traced(track, at(0, 64, 0), at(9, 64, 0));

        assertEquals(Set.of(RailLeg.Kind.SPUR), kinds(trace));
        assertEquals(
                Set.of(4, 3),
                trace.traced().stream().map(leg -> leg.rails().size()).collect(Collectors.toSet()));
    }

    @Test
    void trackBetweenTwoStationsIsOneEdgeKeptFromTheLowerOne() {
        final Track track = new Track().eastWest(0, 9, 64, 0);

        final RailTrace trace = traced(track, at(9, 64, 0), at(0, 64, 0));

        assertEquals(1, trace.traced().size());
        final RailLeg leg = trace.traced().getFirst();
        assertEquals(RailLeg.Kind.EDGE, leg.kind());
        assertEquals(at(0, 64, 0), leg.from());
        assertEquals(at(9, 64, 0), leg.to());
        assertEquals(at(1, 64, 0), leg.first());
    }

    @Test
    void trackIntoTheSideOfAStationRailIsASideLeg() {
        final Track track = new Track().eastWest(-3, 3, 64, 0).northSouth(0, 64, 1, 3);

        final RailTrace trace = traced(track, at(-3, 64, 0), at(0, 64, 0), at(0, 64, 3));

        final RailLeg side = trace.traced().stream()
                .filter(leg -> leg.rails().contains(at(0, 64, 1)))
                .findFirst()
                .orElseThrow();
        assertEquals(RailLeg.Kind.SIDE, side.kind());
        assertEquals(Set.of(), trace.junctions(), "a station rail is never a junction");
    }

    @Test
    void aRailThatThreeRailsLeadIntoIsAJunction() {
        final Track track = new Track().eastWest(-3, 3, 64, 0).northSouth(0, 64, 1, 3);

        final RailTrace trace = traced(track, at(-3, 64, 0), at(3, 64, 0), at(0, 64, 3));

        assertEquals(Set.of(at(0, 64, 0)), trace.junctions());
        assertEquals(Set.of(RailLeg.Kind.EDGE), kinds(trace));
        assertEquals(3, trace.traced().size());
    }

    @Test
    void railsJoinedToNoStationAreNeverRead() {
        final Track track = new Track().eastWest(0, 4, 64, 0).eastWest(0, 4, 64, 10);

        traced(track, at(0, 64, 0));

        for (int x = 0; x <= 4; x++) {
            assertFalse(track.wasRead(at(x, 64, 10)), "the rail at x " + x + " joins no station");
        }
    }

    @Test
    void eachStepReadsAtMostItsBudget() {
        final Track track = new Track().eastWest(0, 99, 64, 0);
        final RailTrace trace = new RailTrace(track, known(track, Set.of(at(0, 64, 0)), _ -> false, _ -> null));
        trace.look(at(0, 64, 0));

        assertFalse(trace.step(50, Long.MAX_VALUE), "a hundred rails take more than one step of fifty");
        assertEquals(50, trace.read());
        assertTrue(trace.step(50, Long.MAX_VALUE));
        assertEquals(99, trace.traced().getFirst().rails().size());
    }

    @Test
    void aStepStopsOnceItsDeadlinePassed() {
        final Track track = new Track().eastWest(0, 99, 64, 0);
        final RailTrace trace = new RailTrace(track, known(track, Set.of(at(0, 64, 0)), _ -> false, _ -> null));
        trace.look(at(0, 64, 0));

        assertFalse(trace.step(1_000, System.nanoTime() - 1));
        assertEquals(0, trace.read());
    }

    @Test
    void aTraceWaitsForWhatItCannotReadYet() {
        final Track track = new Track().eastWest(0, 20, 64, 0).readableWhere(pos -> pos.x() <= 10);
        final RailTrace trace = new RailTrace(track, known(track, Set.of(at(0, 64, 0)), _ -> false, _ -> null));
        trace.look(at(0, 64, 0));

        assertFalse(trace.step(1_000, Long.MAX_VALUE));
        assertTrue(trace.takeWanted().contains(at(11, 64, 0)));

        track.readableWhere(_ -> true);
        assertTrue(trace.step(1_000, Long.MAX_VALUE));
        assertEquals(20, trace.traced().getFirst().rails().size());
    }

    @Test
    void aStationWithoutARailIsMissing() {
        final RailTrace trace = traced(new Track().eastWest(0, 3, 64, 0), at(0, 64, 0), at(7, 64, 0));

        assertEquals(List.of(at(7, 64, 0)), trace.missing());
        assertEquals(
                List.of(at(1, 64, 0), at(2, 64, 0), at(3, 64, 0)),
                trace.traced().getFirst().rails());
    }

    @Test
    void aSettledNodeEndsTheTrackWithoutBeingReadAgain() {
        final Track track = new Track().eastWest(0, 20, 64, 0);
        final RailPos far = at(20, 64, 0);
        final RailTrace trace = new RailTrace(track, known(track, Set.of(at(0, 64, 0), far), far::equals, _ -> null));
        trace.look(at(0, 64, 0));

        assertTrue(trace.step(1_000, Long.MAX_VALUE));

        assertEquals(far, trace.traced().getFirst().to());
        assertFalse(trace.nodes().contains(far), "a settled node is not read again as found");
    }

    @Test
    void trackTheWorldHoldsAlreadyIsKeptInsteadOfTracedAnew() {
        final Track track = new Track().eastWest(0, 9, 64, 0);
        final RailLeg held = traced(track, at(0, 64, 0), at(9, 64, 0)).traced().getFirst();
        final RailTrace trace = new RailTrace(
                track,
                known(track, Set.of(at(0, 64, 0), at(9, 64, 0)), _ -> false, leg -> leg.sameTrack(held) ? held : null));
        trace.look(at(9, 64, 0));

        assertTrue(trace.step(1_000, Long.MAX_VALUE));

        assertTrue(trace.traced().isEmpty());
        assertEquals(Set.of(held), trace.kept());
    }

    @Test
    void aKnownStretchIsSkippedWithoutReadingIt() {
        final Track track = new Track().eastWest(0, 9, 64, 0);
        final List<RailPos> middle = List.of(at(2, 64, 0), at(3, 64, 0), at(4, 64, 0), at(5, 64, 0), at(6, 64, 0));
        final RailTrace.Known plain = known(track, Set.of(at(0, 64, 0), at(9, 64, 0)), _ -> false, _ -> null);
        final RailTrace trace = new RailTrace(track, new RailTrace.Known() {
            @Override
            public @Nullable RailShape station(final RailPos pos) {
                return plain.station(pos);
            }

            @Override
            public boolean settled(final RailPos pos) {
                return false;
            }

            @Override
            public @Nullable RailLeg same(final RailLeg leg) {
                return null;
            }

            @Override
            public RailTrace.@Nullable Skip skip(final RailPos previous, final RailPos at) {
                return at.equals(middle.getFirst()) ? new RailTrace.Skip(middle, at(7, 64, 0)) : null;
            }
        });
        trace.look(at(0, 64, 0));

        assertTrue(trace.step(1_000, Long.MAX_VALUE));

        final RailLeg leg = trace.traced().getFirst();
        assertEquals(8, leg.rails().size());
        assertEquals(at(9, 64, 0), leg.to());
        // The rails next to the known stretch read it as their neighbour; the rails inside it are not read.
        middle.subList(1, middle.size() - 1).forEach(pos -> assertFalse(track.wasRead(pos), pos + " is known"));
    }
}
