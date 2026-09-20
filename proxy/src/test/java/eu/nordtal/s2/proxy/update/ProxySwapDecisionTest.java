package eu.nordtal.s2.proxy.update;

import eu.nordtal.s2.proxy.online.OnlineCounts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What one pass of the live proxy's swap does, and - the part that cost a release to notice - what
 * it does when the standby is not actually there.
 *
 * <p>The decision is a static function of three answers precisely so that it can be held without a
 * {@code ProxyServer}: everything else in {@link ProxySwap} is Velocity, a socket and a clock.</p>
 */
class ProxySwapDecisionTest {

    private static final Set<String> PROXY_NEXT = Set.of(ProxySwap.OWN_SERVICE);

    @Test
    @DisplayName("the service this proxy watches for is the one the worker actually names")
    void theNameIsTheWorkersName() {
        // Two copies of a service name is one copy that is silently wrong, and this one fails
        // silently in the worst way: the swap simply never happens and the log says nothing. The
        // worker's own constant is Topology.PROXY, in a module this one cannot see.
        assertEquals("proxy", ProxySwap.OWN_SERVICE);
        assertEquals(OnlineCounts.PROXY, ProxySwap.OWN_SERVICE);
    }

    @Test
    @DisplayName("an ordinary backend run is not this proxy's business, and costs no socket")
    void aBackendRunIsIdle() {
        final AtomicInteger asked = new AtomicInteger();

        assertEquals(ProxySwap.Pass.IDLE,
                ProxySwap.decide(Set.of("smp", "limbo"), false, probe(asked, true)));
        assertEquals(0, asked.get(), "a pass with nothing to do must not open a socket");
    }

    @Test
    @DisplayName("a run that has already been acted on is not acted on again")
    void oneRunParksOnce() {
        final AtomicInteger asked = new AtomicInteger();

        assertEquals(ProxySwap.Pass.ALREADY_DONE, ProxySwap.decide(PROXY_NEXT, true, probe(asked, true)));
        assertEquals(0, asked.get(), "nor must a pass that has already parked");
    }

    @Test
    @DisplayName("a standby that does not answer means nobody is parked at all")
    void aSilentStandbyIsNoStandby() {
        // season-2-ops/139. Being configured is not being there: `proxy-standby` sits in its own
        // compose profile and is stopped for all but a minute of the season. Transferring the
        // whole network to an address nothing listens on drops every single player - strictly
        // worse than the plain restart this feature exists to avoid.
        assertEquals(ProxySwap.Pass.STANDBY_MISSING,
                ProxySwap.decide(PROXY_NEXT, false, () -> false));
    }

    @Test
    @DisplayName("a standby that answers gets the network")
    void anAnsweringStandbyGetsThem() {
        assertEquals(ProxySwap.Pass.PARK, ProxySwap.decide(PROXY_NEXT, false, () -> true));
    }

    @Test
    @DisplayName("the standby is asked once and only when everything else has already said yes")
    void theProbeIsTheLastQuestion() {
        final AtomicInteger asked = new AtomicInteger();

        ProxySwap.decide(PROXY_NEXT, false, probe(asked, true));

        assertEquals(1, asked.get());
    }

    // ------------------------------------------------------- the door (season-2-ops/151)

    @Test
    @DisplayName("the door stays shut for every pass after the one that parked, not just that one")
    void theDoorIsAStateAndNotAMoment() {
        // THE DEFECT, EXACTLY. Parking happened at 03:20:26 in run 59; the process went at
        // 03:20:42. A player who connected at 03:20:31 was never parked - parking was over - and
        // met "Proxy shutting down". Between those two instants every pass returns ALREADY_DONE,
        // and that is precisely the stretch the door has to be shut for.
        boolean shut = ProxySwap.doorAfter(ProxySwap.Pass.PARK, false);
        assertTrue(shut, "the pass that parks shuts the door");

        for (int pass = 0; pass < 4; pass++) {
            shut = ProxySwap.doorAfter(ProxySwap.Pass.ALREADY_DONE, shut);
            assertTrue(shut, "pass " + pass + " after the park reopened the door");
        }
    }

    @Test
    @DisplayName("a standby that is not there shuts the door too - that arrival would be dropped")
    void aMissingStandbyShutsItAsWell() {
        assertTrue(ProxySwap.doorAfter(ProxySwap.Pass.STANDBY_MISSING, false));
    }

    @Test
    @DisplayName("the door opens again when no run is moving this proxy")
    void theDoorOpensAgain() {
        // A proxy that is still here after a run that stopped nothing, and the second proxy run of
        // one session: both arrive as IDLE, and a door that never reopened would lock the network
        // out until somebody noticed.
        assertFalse(ProxySwap.doorAfter(ProxySwap.Pass.IDLE, true));
        assertFalse(ProxySwap.doorAfter(ProxySwap.Pass.IDLE, false));
    }

    @Test
    @DisplayName("nothing has been decided yet, so the door is open")
    void aFreshProxyLetsPeopleIn() {
        assertFalse(ProxySwap.doorAfter(ProxySwap.Pass.ALREADY_DONE, false),
                "ALREADY_DONE carries the previous answer and invents nothing");
    }

    private static java.util.function.BooleanSupplier probe(final AtomicInteger asked,
                                                            final boolean answer) {
        return () -> {
            asked.incrementAndGet();
            return answer;
        };
    }
}
