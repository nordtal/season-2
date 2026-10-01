package eu.nordtal.s2.steward.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.s2.database.access.PlaytimeWording;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The journal says what the dialog asked for, holding the Java formatting against the TypeScript one.
 *
 * That the bot's journal line uses it is {@code :architecture}'s rule.
 */
class PlaytimeWordingTest {

    /** {@code format.ts}'s own tests pin these; the point here is that both halves agree. */
    private static final List<long[]> CASES = List.of(
            new long[] {86_400 + 6 * 3_600 + 30 * 60, 0},
            new long[] {2 * 86_400, 1},
            new long[] {6 * 3_600 + 30 * 60, 2},
            new long[] {30 * 60, 3},
            new long[] {0, 4},
            new long[] {59, 5},
            new long[] {3_659, 6});

    private static final List<String> EXPECTED =
            List.of("1 d 6 h 30 min", "2 d", "6 h 30 min", "30 min", "0 min", "0 min", "1 h");

    @Test
    void theWordingIsTheOneTheInterfaceUses() {
        for (final long[] each : CASES) {
            assertEquals(EXPECTED.get((int) each[1]), PlaytimeWording.of(each[0]), each[0] + " seconds");
        }
    }

    @Test
    void secondsAreDropped() {
        // Rounding 3 659 up to "1 h 1 min" would disagree with the column beside it in the journal.
        assertEquals("1 h", PlaytimeWording.of(3_659));
        assertEquals("0 min", PlaytimeWording.of(-1));
    }
}
