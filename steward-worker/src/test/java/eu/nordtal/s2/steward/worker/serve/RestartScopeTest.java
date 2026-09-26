package eu.nordtal.s2.steward.worker.serve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.steward.worker.plan.Topology;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a recreate actually takes round (season-2-ops/161).
 *
 * <p>The defect this is written for was measured on the dev host in run 77 on 2026-09-20: a
 * {@code RESTART} carrying {@code scope = 'smp'} planned {@code [proxy, limbo, hunger-games, smp]}
 * and took the whole network round, proxy swap included. The scope had been written faithfully into
 * the row since season-2-ops/127 and this one kind never read it - which is invisible in the code
 * (nothing is missing, a list is simply built from somewhere else) and very visible in the game,
 * because since season-2-ops/118 the announcement names the services it is about.</p>
 */
class RestartScopeTest {

    private static final List<String> ALL =
            Topology.SERVICES.stream().map(Topology.Service::name).toList();

    @Test
    @DisplayName("a recreate of one service is a recreate of one service")
    void theScopeIsRead() {
        assertEquals(List.of("smp"), Runner.restarted(List.of("smp"), List.of()));
        assertFalse(
                Runner.restarted(List.of("smp"), List.of()).contains("proxy"),
                "the proxy is the one whose restart costs everybody a reconnect, scope or no scope");
    }

    @Test
    @DisplayName("no scope is still the whole network, because that is the button that exists")
    void anEmptyScopeIsEverything() {
        assertEquals(ALL, Runner.restarted(List.of(), List.of()));
        assertTrue(ALL.size() > 1, "a topology with one Minecraft service would make this vacuous");
    }

    @Test
    @DisplayName("a held service is never restarted, named by the scope or not")
    void aHoldOutranksAScope() {
        // season-2-ops/125. A hold is a standing decision somebody took; a scope is one request.
        assertEquals(List.of(), Runner.restarted(List.of("smp"), List.of("smp")));
        assertFalse(Runner.restarted(List.of(), List.of("smp")).contains("smp"));
    }

    @Test
    @DisplayName("a scope naming something that is not a Minecraft service restarts nothing")
    void anUnknownScopeIsNotAWildcard() {
        // The dangerous reading of "no match" is "then everything", which is exactly how the
        // empty list behaves one test above. postgres is not restarted by asking for it here.
        assertEquals(List.of(), Runner.restarted(List.of("postgres"), List.of()));
    }

    @Test
    @DisplayName("the order is the topology's own, so a report reads the same way every time")
    void theOrderIsTheTopologys() {
        final List<String> two = Runner.restarted(List.of(ALL.get(2), ALL.get(0)), List.of());
        assertEquals(List.of(ALL.get(0), ALL.get(2)), two);
    }
}
