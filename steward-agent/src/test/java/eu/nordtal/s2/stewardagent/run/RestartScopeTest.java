package eu.nordtal.s2.stewardagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.internalapi.agent.Topology;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What a recreate actually takes round: only the services its scope names. */
class RestartScopeTest {

    private static final List<String> ALL =
            Topology.SERVICES.stream().map(Topology.Service::name).toList();

    @Test
    void aRecreateOfOneServiceIsARecreateOfOneService() {
        assertEquals(List.of("smp"), Runner.restarted(List.of("smp"), List.of()));
        assertFalse(
                Runner.restarted(List.of("smp"), List.of()).contains("proxy"),
                "the proxy is the one whose restart costs everybody a reconnect, scope or no scope");
    }

    @Test
    void noScopeIsStillTheWholeNetworkBecauseThatIsTheButtonThatExists() {
        assertEquals(ALL, Runner.restarted(List.of(), List.of()));
        assertTrue(ALL.size() > 1, "a topology with one Minecraft service would make this vacuous");
    }

    @Test
    void aHeldServiceIsNeverRestartedNamedByTheScopeOrNot() {
        // A hold is a standing decision somebody took; a scope is one request.
        assertEquals(List.of(), Runner.restarted(List.of("smp"), List.of("smp")));
        assertFalse(Runner.restarted(List.of(), List.of("smp")).contains("smp"));
    }

    @Test
    void aScopeNamingSomethingThatIsNotAMinecraftServiceRestartsNothing() {
        // The dangerous reading of "no match" is "then everything"; postgres is not restarted by asking for it here.
        assertEquals(List.of(), Runner.restarted(List.of("postgres"), List.of()));
    }

    @Test
    void theOrderIsTheTopologysOwnSoAReportReadsTheSameWayEveryTime() {
        final List<String> two = Runner.restarted(List.of(ALL.get(2), ALL.get(0)), List.of());
        assertEquals(List.of(ALL.get(0), ALL.get(2)), two);
    }
}
