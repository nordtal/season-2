package eu.nordtal.season.smp.duel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.GameMode;
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
    void aTimeoutEndsEvenWhenTheCountdownWasRunning() {
        assertTrue(Negotiation.timeoutExpired(true));
    }

    @Test
    void moveHandlingIgnoresOnlySpectators() {
        assertTrue(Negotiation.ignores(GameMode.SPECTATOR));
        assertFalse(Negotiation.ignores(GameMode.SURVIVAL));
        assertFalse(Negotiation.ignores(GameMode.ADVENTURE));
        assertFalse(Negotiation.ignores(GameMode.CREATIVE));
    }
}
