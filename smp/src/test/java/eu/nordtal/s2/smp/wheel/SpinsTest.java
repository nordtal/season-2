package eu.nordtal.s2.smp.wheel;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When the free spin comes back.
 *
 * <p>A calendar day in the server's own time zone, decided 2026-09-01. Midnight is the boundary, and
 * a boundary is exactly the kind of thing that is easy to get right by accident and wrong by one
 * comparison operator - which is why it is asserted against fixed dates rather than waited for.
 */
class SpinsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 1);

    @Test
    void somebodyWhoHasNeverSpunHasTheirFreeSpin() {
        final Spins spins = new Spins(0, 0, null);

        assertTrue(spins.hasFree(TODAY));
        assertTrue(spins.canSpin(TODAY));
        assertEquals(1, spins.available(TODAY));
    }

    @Test
    void spinningTodayUsesTodaysFreeSpinAndNoMore() {
        final Spins spins = new Spins(0, 0, TODAY);

        assertFalse(spins.hasFree(TODAY));
        assertFalse(spins.canSpin(TODAY));
        assertEquals(0, spins.available(TODAY));
    }

    @Test
    void yesterdaysSpinIsBackAtMidnight() {
        final Spins spins = new Spins(0, 0, TODAY.minusDays(1));

        assertTrue(spins.hasFree(TODAY), "the boundary is the calendar day, not 24 hours");
    }

    @Test
    void earnedSpinsAreOnTopOfTheFreeOne() {
        assertEquals(4, new Spins(3, 0, null).available(TODAY));
        assertEquals(3, new Spins(3, 0, TODAY).available(TODAY));
        assertEquals(1, new Spins(3, 2, TODAY).available(TODAY));
    }

    /** The free one goes first: an earned spin kept is still there tomorrow, a free one is not. */
    @Test
    void theFreeSpinIsSpentBeforeAnEarnedOne() {
        assertTrue(new Spins(5, 0, null).nextIsFree(TODAY));
        assertFalse(new Spins(5, 0, TODAY).nextIsFree(TODAY));
    }

    @Test
    void columnsThatDisagreeNeverProduceNegativeSpins() {
        assertEquals(0, new Spins(2, 5, TODAY).extras(),
                "the schema forbids it, but a caller must not be able to produce a negative either");
        assertEquals(0, new Spins(2, 5, TODAY).available(TODAY));
    }

    @Test
    void negativeCountsAreRefusedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new Spins(-1, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new Spins(0, -1, null));
    }
}
