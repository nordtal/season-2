package eu.nordtal.s2.proxy.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.proxy.PhaseServers;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Where a player coming back from a proxy swap is let out to (season-2-ops/121). */
class ParkedSeatsTest {

    private static final PhaseServers SERVERS = new PhaseServers("limbo", "limbo-standby", "hunger-games", "smp");
    private static final Set<String> AVAILABLE = Set.of("limbo", "limbo-standby", "hunger-games", "smp");
    private static final Instant NOW = Instant.parse("2026-09-19T20:00:00Z");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000f00d");

    @Test
    @DisplayName("an admin goes back where they were, not where the phase points")
    void anAdminGoesBackWhereTheyWere() {
        // The only case where the seat differs from the phase's own answer, and therefore the only
        // case it exists for: an admin is the one player routing deliberately does not move.
        assertEquals("hunger-games", ParkedSeats.destination("hunger-games", true, "smp", AVAILABLE, SERVERS));
    }

    @Test
    @DisplayName("everybody else is routed by the phase, seat or no seat")
    void everybodyElseGoesByThePhase() {
        // Not a limitation. For a non-admin the two agree by construction - the seat says `smp`
        // because the phase said `smp` twenty seconds ago. Where they DISAGREE is a phase that
        // moved during the swap, and honouring the seat there would put somebody on a backend the
        // current phase does not allow: past the check that exists to stop exactly that.
        assertEquals("smp", ParkedSeats.destination("hunger-games", false, "smp", AVAILABLE, SERVERS));
    }

    @Test
    @DisplayName("a seat naming a waiting room is ignored, because that is a black screen")
    void aWaitingRoomIsNeverASeat() {
        // Releasing somebody from the waiting room INTO the waiting room is a stale title and no
        // timeout - the same failure PhaseServers#forAdmitted was written to avoid.
        assertEquals("smp", ParkedSeats.destination("limbo", true, "smp", AVAILABLE, SERVERS));
        assertEquals("smp", ParkedSeats.destination("limbo-standby", true, "smp", AVAILABLE, SERVERS));
    }

    @Test
    @DisplayName("a seat naming a server this proxy no longer has is ignored")
    void aServerThatIsGoneIsIgnored() {
        assertEquals("smp", ParkedSeats.destination("hunger-games", true, "smp", Set.of("limbo", "smp"), SERVERS));
    }

    @Test
    @DisplayName("no seat is the ordinary login, and it changes nothing")
    void noSeatChangesNothing() {
        assertEquals("smp", ParkedSeats.destination(null, true, "smp", AVAILABLE, SERVERS));
        assertEquals("smp", ParkedSeats.destination(null, false, "smp", AVAILABLE, SERVERS));
    }

    @Test
    @DisplayName("a seat is used once")
    void aSeatIsUsedOnce() {
        final ParkedSeats seats = new ParkedSeats(List.of(new SwapStore.Seat(PLAYER, "hunger-games", NOW)), NOW);
        assertEquals("hunger-games", seats.releaseTo(PLAYER, true, "smp", AVAILABLE, SERVERS));
        // A release that fails and is retried goes by the phase the second time, which is the safe
        // direction: the seat was already tried and the phase's answer is the one true now.
        assertEquals("smp", seats.releaseTo(PLAYER, true, "smp", AVAILABLE, SERVERS));
    }

    @Test
    @DisplayName("a seat older than the window is dropped on the way into memory")
    void aStaleSeatIsDropped() {
        // The failure this prevents is not a crash. It is an admin logging in days later and being
        // moved to hunger-games by a row nobody remembers writing - which looks exactly like
        // broken routing and is not.
        final Instant old = NOW.minus(SwapStore.SEAT_VALID_FOR).minusSeconds(1);
        assertFalse(new SwapStore.Seat(PLAYER, "hunger-games", old).isFresh(NOW));
        assertTrue(new SwapStore.Seat(PLAYER, "hunger-games", NOW.minus(SwapStore.SEAT_VALID_FOR)).isFresh(NOW));
        assertEquals(0, new ParkedSeats(List.of(new SwapStore.Seat(PLAYER, "hunger-games", old)), NOW).size());
    }
}
