package eu.nordtal.season.underground.transplant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Refuses measures the transplant cannot hold. */
class ParametersTest {

    private static Parameters with(final int outsideReach, final int protectedReach, final int crust) {
        return new Parameters(8, outsideReach, 8, 5, protectedReach, 3, crust, 12, 1.5, 236, true);
    }

    @Test
    void theStandardMeasuresHold() {
        assertDoesNotThrow(() -> Parameters.standard().check());
    }

    @Test
    void aDistanceOneByteCannotHoldIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> with(100, 24, 8).check());
    }

    @Test
    void protectedSpaceBeyondTheTilesMarginIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> with(24, 60, 8).check());
    }

    @Test
    void aTaperLongerThanTheOutsideReachIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> with(5, 24, 8).check());
    }

    @Test
    void aCrustThinnerThanTheTaperIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> with(24, 24, 4).check());
    }
}
