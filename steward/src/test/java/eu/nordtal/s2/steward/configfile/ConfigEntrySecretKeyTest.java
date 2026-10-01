package eu.nordtal.s2.steward.configfile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The name heuristic behind a hidden value.
 *
 * A key called just {@code key} is an entry id here; every credential is named {@code something-key}.
 */
class ConfigEntrySecretKeyTest {

    @Test
    void anEntrysIdIsNotASecret() {
        assertFalse(ConfigEntry.isSecretKey("key"));
        assertFalse(ConfigEntry.isSecretKey("Key"));
    }

    @Test
    void aKeyThatNamesWhatItUnlocksStillIs() {
        for (final String name : new String[] {
            "api-key", "access-key", "secret-key", "private-key", "apikey", "token", "password", "client-secret"
        }) {
            assertTrue(ConfigEntry.isSecretKey(name), name);
        }
    }
}
