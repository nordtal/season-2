package eu.nordtal.s2.proxy.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.proxy.online.OnlineCounts;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** What one pass of the live proxy's swap does, including when the standby is not there. */
class ProxySwapDecisionTest {

    private static final Set<String> PROXY_NEXT = Set.of(ProxySwap.OWN_SERVICE);

    @Test
    void theNameIsTheOneStewardStops() {
        // Must equal steward's Topology.PROXY, which this module cannot see; a mismatch silently stops swaps.
        assertEquals("proxy", ProxySwap.OWN_SERVICE);
        assertEquals(OnlineCounts.PROXY, ProxySwap.OWN_SERVICE);
    }

    @Test
    void aBackendRunIsIdle() {
        final AtomicInteger asked = new AtomicInteger();

        assertEquals(ProxySwap.Pass.IDLE, ProxySwap.decide(Set.of("smp", "limbo"), false, false, probe(asked, true)));
        assertEquals(0, asked.get(), "a pass with nothing to do must not open a socket");
    }

    @Test
    void oneRunParksOnce() {
        final AtomicInteger asked = new AtomicInteger();

        assertEquals(ProxySwap.Pass.ALREADY_DONE, ProxySwap.decide(PROXY_NEXT, true, false, probe(asked, true)));
        assertEquals(0, asked.get(), "nor must a pass that has already parked");
    }

    @Test
    void aSilentStandbyIsNoStandby() {
        // proxy-standby is stopped most of the season; a transfer to it would drop everyone.
        assertEquals(ProxySwap.Pass.STANDBY_MISSING, ProxySwap.decide(PROXY_NEXT, false, false, () -> false));
    }

    @Test
    void anAnsweringStandbyGetsThem() {
        assertEquals(ProxySwap.Pass.PARK, ProxySwap.decide(PROXY_NEXT, false, false, () -> true));
    }

    @Test
    void theProbeIsTheLastQuestion() {
        final AtomicInteger asked = new AtomicInteger();

        ProxySwap.decide(PROXY_NEXT, false, false, probe(asked, true));

        assertEquals(1, asked.get());
    }

    // the run that already went through this proxy

    @Test
    void aRestartedProxyIsNotTheOneBeingStopped() {
        final AtomicInteger asked = new AtomicInteger();
        // The row names `proxy` as moving for the whole run; the restarted process must not read that as stopping.
        assertEquals(ProxySwap.Pass.ALREADY_MOVED, ProxySwap.decide(PROXY_NEXT, false, true, probe(asked, true)));
        assertEquals(0, asked.get(), "and it costs no socket either");
        // The door stays open for the players the standby hands back.
        assertFalse(ProxySwap.doorAfter(ProxySwap.Pass.ALREADY_MOVED, true));
        assertFalse(ProxySwap.doorAfter(ProxySwap.Pass.ALREADY_MOVED, false));
    }

    @Test
    void whoIsTheProcessTheRunMeans() {
        final java.time.Instant zero = java.time.Instant.parse("2026-09-20T18:45:00Z");

        assertFalse(
                ProxySwap.hasBeenThroughMe(zero.minusSeconds(3600), zero),
                "a proxy that was running before the countdown is the one being stopped");
        assertFalse(
                ProxySwap.hasBeenThroughMe(zero, zero),
                "the same instant is not after it, and the safe reading is 'still to come'");
        assertTrue(
                ProxySwap.hasBeenThroughMe(zero.plusSeconds(20), zero),
                "a process that started after the zero can only have been started BY the run");
        assertFalse(ProxySwap.hasBeenThroughMe(zero, null), "a row with no instant decides nothing");
    }

    // the door

    @Test
    void theDoorIsAStateAndNotAMoment() {
        // ALREADY_DONE covers the whole stop, so a player connecting then meets "Proxy shutting down".
        boolean shut = ProxySwap.doorAfter(ProxySwap.Pass.PARK, false);
        assertTrue(shut, "the pass that parks shuts the door");

        for (int pass = 0; pass < 4; pass++) {
            shut = ProxySwap.doorAfter(ProxySwap.Pass.ALREADY_DONE, shut);
            assertTrue(shut, "pass " + pass + " after the park reopened the door");
        }
    }

    @Test
    void aMissingStandbyShutsItAsWell() {
        assertTrue(ProxySwap.doorAfter(ProxySwap.Pass.STANDBY_MISSING, false));
    }

    @Test
    void theDoorOpensAgain() {
        // A leftover proxy and a second run both arrive as IDLE and must reopen the door.
        assertFalse(ProxySwap.doorAfter(ProxySwap.Pass.IDLE, true));
        assertFalse(ProxySwap.doorAfter(ProxySwap.Pass.IDLE, false));
    }

    @Test
    void aFreshProxyLetsPeopleIn() {
        assertFalse(
                ProxySwap.doorAfter(ProxySwap.Pass.ALREADY_DONE, false),
                "ALREADY_DONE carries the previous answer and invents nothing");
    }

    private static java.util.function.BooleanSupplier probe(final AtomicInteger asked, final boolean answer) {
        return () -> {
            asked.incrementAndGet();
            return answer;
        };
    }
}
