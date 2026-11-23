package eu.nordtal.season.smp.duel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class NegotiationTest {

    @Test
    void aPairEndsOnlyFurtherThanSixteenBlocksApart() {
        assertFalse(Negotiation.apart(true, 16 * 16));
        assertTrue(Negotiation.apart(true, 16 * 16 + 0.01));
    }

    @Test
    void aPairInDifferentWorldsEnds() {
        assertTrue(Negotiation.apart(false, 0));
    }

    @Test
    void aLimitEndsThePairAtOnceWithoutACountdown() {
        assertTrue(Negotiation.timeoutExpired(false));
    }

    @Test
    void aCountdownRunningAtTheLimitMayFinish() {
        assertFalse(Negotiation.timeoutExpired(true));
    }

    @Test
    void similarKitNamesKeepTheirLastThreeLetters() {
        final List<String> shown =
                NegotiationDialog.shortNames(List.of("Langschwert Nord", "Langschwert Süd", "Bogen"));
        assertEquals("Bogen", shown.get(2));
        assertTrue(shown.get(0).endsWith("…ord"), shown.get(0));
        assertTrue(shown.get(1).endsWith("…Süd"), shown.get(1));
    }
}
