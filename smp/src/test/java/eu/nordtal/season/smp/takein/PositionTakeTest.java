package eu.nordtal.season.smp.takein;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.inbox.SmpRequest.TakePosition;
import org.junit.jupiter.api.Test;

/** Checks that a taken position reads as an admin would type it. */
class PositionTakeTest {

    @Test
    void aPlaceIsRoundedToTheHundredthOfABlockAndTheFacingToTheTenthOfADegree() {
        final TakePosition.Answer taken = PositionTake.answer("nordtal", 106.4999, 64.0, -88.123, 92.36f, 12.04f);

        assertEquals(new TakePosition.Answer("nordtal", 106.5, 64.0, -88.12, 92.4f, 12.0f), taken);
    }

    @Test
    void aFacingIsTurnedIntoTheRangeTheSettingAccepts() {
        final TakePosition.Answer taken = PositionTake.answer("nordtal", 0, 0, 0, 270f, -90f);

        assertEquals(-90f, taken.yaw());
        assertEquals(-90f, taken.pitch());
    }
}
