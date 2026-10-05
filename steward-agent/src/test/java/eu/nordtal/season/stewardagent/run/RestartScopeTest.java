package eu.nordtal.season.stewardagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.internalapi.agent.Topology;
import eu.nordtal.season.stewardagent.topology.DeclaredTopology;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What a recreate actually takes round: only the services its scope names. */
class RestartScopeTest {

    private static final List<String> ALL = DeclaredTopology.topology().servers().stream()
            .map(Topology.Service::name)
            .toList();

    private static List<String> restarted(final List<String> scope, final List<String> holds) {
        return Runner.restarted(ALL, scope, holds);
    }

    @Test
    void aRecreateOfOneServiceIsARecreateOfOneService() {
        assertEquals(List.of("smp"), restarted(List.of("smp"), List.of()));
        assertFalse(
                restarted(List.of("smp"), List.of()).contains("proxy"),
                "the proxy is the one whose restart costs everybody a reconnect, scope or no scope");
    }

    @Test
    void noScopeIsStillTheWholeNetworkBecauseThatIsTheButtonThatExists() {
        assertEquals(ALL, restarted(List.of(), List.of()));
        assertTrue(ALL.size() > 1, "a topology with one Minecraft service would make this vacuous");
    }

    @Test
    void aHeldServiceIsNeverRestartedNamedByTheScopeOrNot() {
        // A hold is a standing decision somebody took; a scope is one request.
        assertEquals(List.of(), restarted(List.of("smp"), List.of("smp")));
        assertFalse(restarted(List.of(), List.of("smp")).contains("smp"));
    }

    @Test
    void aScopeNamingSomethingThatIsNotAMinecraftServiceRestartsNothing() {
        // The dangerous reading of "no match" is "then everything"; postgres is not restarted by asking for it here.
        assertEquals(List.of(), restarted(List.of("postgres"), List.of()));
    }

    @Test
    void theOrderIsTheTopologysOwnSoAReportReadsTheSameWayEveryTime() {
        final List<String> two = restarted(List.of(ALL.get(2), ALL.get(0)), List.of());
        assertEquals(List.of(ALL.get(0), ALL.get(2)), two);
    }
}
