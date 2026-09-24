package eu.nordtal.s2.steward.worker.configfile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The name heuristic behind a hidden value. A key called just {@code key} is the ID of an entry in
 * this repository - a milestone, an objective, a sound - and hiding it blanks the title of every
 * card that shows one. Every credential here is named {@code something-key}.
 */
class ConfigEntrySecretKeyTest {

    @Test
    void anEntrysIdIsNotASecret() {
        assertFalse(ConfigEntry.isSecretKey("key"));
        assertFalse(ConfigEntry.isSecretKey("Key"));
    }

    @Test
    void aKeyThatNamesWhatItUnlocksStillIs() {
        for (final String name : new String[] {"api-key", "access-key", "secret-key", "private-key",
                "apikey", "token", "password", "client-secret"}) {
            assertTrue(ConfigEntry.isSecretKey(name), name);
        }
    }
}
