package eu.nordtal.season.papercommon.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.settings.DistancesSpec;
import eu.nordtal.season.settings.MemorySettingStore;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What the distances set and a server's defaults make of a world's distances, before any world is touched. */
class DistancesTest {

    private final List<String> warnings = new ArrayList<>();

    @Test
    void aValueOutsideWhatPaperAcceptsIsTakenAsTheNearestEnd() {
        // Paper's World refuses anything outside 2 to 32, and a refusal at start would stop the server.
        assertEquals(new Distances(32, 2), Distances.of(file(40, 1), warnings::add));
        assertEquals(new Distances(2, 32), Distances.of(file(-5, 33), warnings::add));
        assertEquals(4, warnings.size(), "every value that was not taken as written is named: " + warnings);
    }

    @Test
    void nothingSetLeavesBothDistancesToTheServer() {
        assertEquals(Distances.NONE, Distances.of(file(0, 0), warnings::add));
        assertEquals(List.of(), warnings);
    }

    /** The shared group defaults per server, so Steward shows the smp's 32 rather than the spec's 0. */
    @Test
    void aServersOwnDistancesAreItsDefaultsAndWhatStewardShows() throws Exception {
        final MemorySettingStore store = new MemorySettingStore();
        final Distances smp = new Distances(32, 10);

        assertEquals(
                smp,
                Distances.of(store.settings("smp").load(Distances.group(smp)).get(), warnings::add));
        assertTrue(store.group("smp", "distances").orElseThrow().defaults().contains("\"view-distance\":32"));
        store.set("smp", "distances", "simulation-distance", 6);
        assertEquals(
                new Distances(32, 6),
                Distances.of(store.settings("smp").load(Distances.group(smp)).get(), warnings::add));
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
