package eu.nordtal.season.smp.rail;

import static eu.nordtal.season.smp.rail.Track.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RailWalkTest {

    private static RailWalk walked(final Track track, final int limit, final RailPos... starts) {
        final RailWalk walk = new RailWalk(track, List.of(starts), limit);
        for (int steps = 0; steps < 1_000; steps++) {
            if (walk.step(100)) {
                return walk;
            }
        }
        throw new AssertionError("the walk did not end");
    }

    @Test
    void parallelTracksSideBySideStayApart() {
        final Track track = new Track().eastWest(0, 9, 64, 0).eastWest(0, 9, 64, 1);

        final RailWalk walk = walked(track, 1_000, at(0, 64, 0), at(9, 64, 0), at(0, 64, 1), at(9, 64, 1));

        assertEquals(2, walk.found().size());
        assertEquals(10, walk.found().get(0).rails().size());
        assertEquals(10, walk.found().get(1).rails().size());
    }

    @Test
    void aBrokenRailSplitsTheNetwork() {
        final Track track = new Track().eastWest(0, 9, 64, 0).remove(5, 64, 0);

        final RailWalk walk = walked(track, 1_000, at(0, 64, 0), at(9, 64, 0));

        assertEquals(2, walk.found().size());
        assertEquals(5, walk.found().get(0).rails().size());
        assertEquals(4, walk.found().get(1).rails().size());
    }

    @Test
    void aJoiningRailMergesTwoNetworks() {
        final Track track = new Track().eastWest(0, 4, 64, 0).eastWest(6, 9, 64, 0);
        assertEquals(2, walked(track, 1_000, at(0, 64, 0), at(9, 64, 0)).found().size());

        track.rail(5, 64, 0, RailShape.EAST_WEST);

        final RailWalk walk = walked(track, 1_000, at(0, 64, 0), at(9, 64, 0));
        assertEquals(1, walk.found().size());
        assertEquals(10, walk.found().getFirst().rails().size());
    }

    @Test
    void railsJoinedToNoStationAreNeverRead() {
        final Track track = new Track().eastWest(0, 4, 64, 0).eastWest(0, 4, 64, 10);

        walked(track, 1_000, at(0, 64, 0));

        for (int x = 0; x <= 4; x++) {
            assertFalse(track.wasRead(at(x, 64, 10)), "the rail at x " + x + " joins no station");
        }
    }

    @Test
    void aWalkStopsAtItsBound() {
        final RailWalk walk = walked(new Track().eastWest(0, 99, 64, 0), 10, at(0, 64, 0));

        assertEquals(10, walk.found().getFirst().rails().size());
        assertTrue(walk.found().getFirst().bounded());
    }

    @Test
    void eachStepExpandsAtMostItsBudget() {
        final RailWalk walk = new RailWalk(new Track().eastWest(0, 99, 64, 0), List.of(at(0, 64, 0)), 1_000);

        assertFalse(walk.step(50), "a hundred rails take more than one step of fifty");
        int steps = 1;
        while (!walk.step(50)) {
            steps++;
        }
        assertEquals(2, steps);
        assertEquals(100, walk.found().getFirst().rails().size());
    }

    @Test
    void aWalkWaitsForWhatItCannotReadYet() {
        final Track track = new Track().eastWest(0, 20, 64, 0).readableWhere(pos -> pos.x() <= 10);
        final RailWalk walk = new RailWalk(track, List.of(at(0, 64, 0)), 1_000);

        assertFalse(walk.step(1_000));
        assertTrue(walk.takeWanted().contains(at(11, 64, 0)));

        track.readableWhere(_ -> true);
        assertTrue(walk.step(1_000));
        assertEquals(21, walk.found().getFirst().rails().size());
    }

    @Test
    void aStartWithoutARailIsMissing() {
        final RailWalk walk = walked(new Track().eastWest(0, 3, 64, 0), 1_000, at(0, 64, 0), at(7, 64, 0));

        assertEquals(List.of(at(7, 64, 0)), walk.missing());
        assertEquals(
                Set.of(at(0, 64, 0), at(1, 64, 0), at(2, 64, 0), at(3, 64, 0)),
                walk.found().getFirst().rails().keySet());
    }
}
