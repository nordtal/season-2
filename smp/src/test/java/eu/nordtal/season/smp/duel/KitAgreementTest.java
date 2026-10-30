package eu.nordtal.season.smp.duel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class KitAgreementTest {

    @Test
    void aPlayerAloneOnTheAreaWaitsWhateverTheyPicked() {
        assertEquals(KitAgreement.ALONE, KitAgreement.of(false, 1, null));
    }

    @Test
    void aPlayerWithoutAPickIsAskedForOne() {
        assertEquals(KitAgreement.PICK, KitAgreement.of(true, null, 0));
    }

    @Test
    void aPlayerWhoPickedWaitsForTheOpponent() {
        assertEquals(KitAgreement.WAITING, KitAgreement.of(true, 0, null));
    }

    @Test
    void twoDifferentPicksStartNothing() {
        assertEquals(KitAgreement.DIFFERENT, KitAgreement.of(true, 0, 1));
    }

    @Test
    void theSamePickOnBothSidesIsAnAgreement() {
        assertEquals(KitAgreement.AGREED, KitAgreement.of(true, 1, 1));
    }
}
