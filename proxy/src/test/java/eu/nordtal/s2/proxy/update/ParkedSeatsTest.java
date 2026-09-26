package eu.nordtal.s2.proxy.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.proxy.PhaseServers;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Where a player coming back from a proxy swap is let out to. */
class ParkedSeatsTest {

    private static final PhaseServers SERVERS = new PhaseServers("limbo", "limbo-standby", "hunger-games", "smp");
    private static final Set<String> AVAILABLE = Set.of("limbo", "limbo-standby", "hunger-games", "smp");
    private static final Instant NOW = Instant.parse("2026-09-19T20:00:00Z");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000f00d");

    @Test
    void anAdminGoesBackWhereTheyWere() {
        // The only case where the seat differs from the phase's own answer: an admin is deliberately not moved.
        assertEquals("hunger-games", ParkedSeats.destination("hunger-games", true, "smp", AVAILABLE, SERVERS));
    }

    @Test
    void everybodyElseGoesByThePhase() {
        // For a non-admin the two agree by construction; a moved phase disagreeing is what the seat check guards.
        assertEquals("smp", ParkedSeats.destination("hunger-games", false, "smp", AVAILABLE, SERVERS));
    }

    @Test
    void aWaitingRoomIsNeverASeat() {
        // Releasing somebody from the waiting room INTO the waiting room is a stale title with no timeout at all.
        assertEquals("smp", ParkedSeats.destination("limbo", true, "smp", AVAILABLE, SERVERS));
        assertEquals("smp", ParkedSeats.destination("limbo-standby", true, "smp", AVAILABLE, SERVERS));
    }

    @Test
    void aServerThatIsGoneIsIgnored() {
        assertEquals("smp", ParkedSeats.destination("hunger-games", true, "smp", Set.of("limbo", "smp"), SERVERS));
    }

    @Test
    void noSeatChangesNothing() {
        assertEquals("smp", ParkedSeats.destination(null, true, "smp", AVAILABLE, SERVERS));
        assertEquals("smp", ParkedSeats.destination(null, false, "smp", AVAILABLE, SERVERS));
    }

    @Test
    void aSeatIsUsedOnce() {
        final ParkedSeats seats = new ParkedSeats(List.of(new SwapStore.Seat(PLAYER, "hunger-games", NOW)), NOW);
        assertEquals("hunger-games", seats.releaseTo(PLAYER, true, "smp", AVAILABLE, SERVERS));
        // A retried release goes by the phase, the safe direction: the seat is already tried and the phase is now.
        assertEquals("smp", seats.releaseTo(PLAYER, true, "smp", AVAILABLE, SERVERS));
    }

    @Test
    void aStaleSeatIsDropped() {
        // The failure this prevents: an admin logging in days later, moved by a row nobody remembers writing.
        final Instant old = NOW.minus(SwapStore.SEAT_VALID_FOR).minusSeconds(1);
        assertFalse(new SwapStore.Seat(PLAYER, "hunger-games", old).isFresh(NOW));
        assertTrue(new SwapStore.Seat(PLAYER, "hunger-games", NOW.minus(SwapStore.SEAT_VALID_FOR)).isFresh(NOW));
        assertEquals(0, new ParkedSeats(List.of(new SwapStore.Seat(PLAYER, "hunger-games", old)), NOW).size());
    }
}
