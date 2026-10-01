package eu.nordtal.s2.papercommon.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.s2.settings.DistancesSpec;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What {@code distances.yml} and a plugin's defaults make of a world's distances, before any world is touched. */
class DistancesTest {

    private final List<String> warnings = new ArrayList<>();

    @Test
    void aValueOutsideWhatPaperAcceptsIsTakenAsTheNearestEnd() {
        // Paper's World refuses anything outside 2 to 32, and a refusal at start would stop the server.
        assertEquals(new Distances(32, 2), Distances.of(file(40, 1), Distances.NONE, warnings::add));
        assertEquals(new Distances(2, 32), Distances.of(file(-5, 33), Distances.NONE, warnings::add));
        assertEquals(4, warnings.size(), "every value that was not taken as written is named: " + warnings);
    }

    @Test
    void nothingSetLeavesBothDistancesToTheServer() {
        assertEquals(Distances.NONE, Distances.of(file(0, 0), Distances.NONE, warnings::add));
        assertEquals(List.of(), warnings);
    }

    @Test
    void aPluginsDefaultsFillWhatTheFileLeavesUnset() {
        final Distances smp = new Distances(32, 10);

        assertEquals(smp, Distances.of(file(0, 0), smp, warnings::add));
        assertEquals(new Distances(16, 10), Distances.of(file(16, 0), smp, warnings::add));
        assertEquals(new Distances(32, 6), Distances.of(file(0, 6), smp, warnings::add));
    }

    private static DistancesSpec file(final int view, final int simulation) {
        return new DistancesSpec() {
            @Override
            public int viewDistance() {
                return view;
            }

            @Override
            public int simulationDistance() {
                return simulation;
            }
        };
    }
}
