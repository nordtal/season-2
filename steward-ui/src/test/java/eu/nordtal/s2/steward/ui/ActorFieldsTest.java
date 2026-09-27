package eu.nordtal.s2.steward.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@code requested_by} picked apart for the Runs table.
 *
 * Mirrors {@code ActionEntry.of(UpdateRequest)} on steward-worker's side rather than sharing it.
 */
class ActorFieldsTest {

    @Test
    void aNameWithAnIdIsTheDiscordIdAlone() {
        final ActorFields fields = ActorFields.of("hm.ally (300000000000000077)");

        assertEquals("300000000000000077", fields.discordId());
        assertEquals("", fields.label());
        assertFalse(fields.system());
    }

    @Test
    void aBareToolNameIsPlainText() {
        final ActorFields fields = ActorFields.of("token-rotation-check");

        assertEquals("", fields.discordId());
        assertEquals("token-rotation-check", fields.label());
        assertFalse(fields.system());
    }

    @Test
    void aShortNumberInParenthesesIsNotMistakenForASnowflake() {
        // Proof the regex needs 17 to 20 digits and does not reach for anything shorter.
        final ActorFields fields = ActorFields.of("Ally (1)");

        assertEquals("", fields.discordId());
        assertEquals("Ally (1)", fields.label());
        assertFalse(fields.system());
    }

    @Test
    void theNightlyClockIsSystem() {
        final ActorFields fields = ActorFields.of("steward-worker (nightly)");

        assertTrue(fields.system());
        assertEquals("", fields.discordId());
        assertEquals("", fields.label());
    }

    @Test
    void aMissingRequesterIsSystem() {
        final ActorFields fields = ActorFields.of(null);

        assertTrue(fields.system());
    }
}
