package eu.nordtal.s2.steward.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code requested_by} picked apart for steward/95's Runs table, which needs the same three fields
 * the unified actions feed already reads out of it - see {@code ActionEntry.of(UpdateRequest)} on
 * steward-worker's side, which this mirrors rather than shares (a different module, a
 * package-private constructor, and a five-line regex is not worth a dependency for).
 */
class ActorFieldsTest {

    @Test
    @DisplayName("a name with the id that goes with it resolves through the roster, not as text")
    void aNameWithAnIdIsTheDiscordIdAlone() {
        final StewardUi.ActorFields fields = StewardUi.ActorFields.of("hm.till (594510749410525200)");

        assertEquals("594510749410525200", fields.discordId());
        assertEquals("", fields.label());
        assertFalse(fields.system());
    }

    @Test
    @DisplayName("a requester with no snowflake in it is shown as plain text, never guessed at")
    void aBareToolNameIsPlainText() {
        final StewardUi.ActorFields fields = StewardUi.ActorFields.of("token-rotation-check");

        assertEquals("", fields.discordId());
        assertEquals("token-rotation-check", fields.label());
        assertFalse(fields.system());
    }

    @Test
    @DisplayName("a parenthesised number too short to be a snowflake is not extracted as one")
    void aShortNumberInParenthesesIsNotMistakenForASnowflake() {
        // Exactly what the test fixture's own session id ("1") produces - proof the regex needs
        // 17 to 20 digits and does not reach for anything shorter.
        final StewardUi.ActorFields fields = StewardUi.ActorFields.of("Till (1)");

        assertEquals("", fields.discordId());
        assertEquals("Till (1)", fields.label());
        assertFalse(fields.system());
    }

    @Test
    @DisplayName("steward-worker's own nightly clock is Steward, never a person")
    void theNightlyClockIsSystem() {
        final StewardUi.ActorFields fields = StewardUi.ActorFields.of("steward-worker (nightly)");

        assertTrue(fields.system());
        assertEquals("", fields.discordId());
        assertEquals("", fields.label());
    }

    @Test
    @DisplayName("no requester at all is Steward too, not a blank person")
    void aMissingRequesterIsSystem() {
        final StewardUi.ActorFields fields = StewardUi.ActorFields.of(null);

        assertTrue(fields.system());
    }
}
