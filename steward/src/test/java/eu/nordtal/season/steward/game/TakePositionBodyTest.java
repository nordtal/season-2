package eu.nordtal.season.steward.game;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.javalin.http.BadRequestResponse;
import org.junit.jupiter.api.Test;

class TakePositionBodyTest {

    @Test
    void readsWhetherTheTakeAsksForABlock() {
        assertTrue(CommandApi.blockOf("{\"block\":true}"));
        assertFalse(CommandApi.blockOf("{\"block\":false}"));
    }

    @Test
    void refusesABodyThatNamesNoBoolean() {
        assertThrows(BadRequestResponse.class, () -> CommandApi.blockOf("{\"block\":\"yes\"}"));
        assertThrows(BadRequestResponse.class, () -> CommandApi.blockOf("{\"block\":1}"));
        assertThrows(BadRequestResponse.class, () -> CommandApi.blockOf("{}"));
        assertThrows(BadRequestResponse.class, () -> CommandApi.blockOf("not json"));
    }
}
