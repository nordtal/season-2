package eu.nordtal.season.steward.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.nordtal.season.database.inbox.TakeInPurpose;
import io.javalin.http.BadRequestResponse;
import org.junit.jupiter.api.Test;

class TakeInPurposeTest {

    @Test
    void aBlankBodyAsksForAWheelPrize() {
        assertEquals(TakeInPurpose.WHEEL_PRIZE, CommandApi.purposeOf(""));
    }

    @Test
    void aBodyNamesItsPurpose() {
        assertEquals(TakeInPurpose.DUEL_KIT, CommandApi.purposeOf("{\"purpose\":\"DUEL_KIT\"}"));
    }

    @Test
    void anUnknownOrMissingPurposeIsABadRequest() {
        assertThrows(BadRequestResponse.class, () -> CommandApi.purposeOf("{\"purpose\":\"X\"}"));
        assertThrows(BadRequestResponse.class, () -> CommandApi.purposeOf("{}"));
        assertThrows(BadRequestResponse.class, () -> CommandApi.purposeOf("not json"));
    }
}
