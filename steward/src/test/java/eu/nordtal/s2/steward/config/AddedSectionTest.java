package eu.nordtal.s2.steward.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.exception.ConfigException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a {@code web.yml} gains when a whole nested section is added to the spec.
 *
 * {@code StewardSettings.web} refuses a null relying party, so this decides whether the file needs editing by hand.
 */
class AddedSectionTest {

    @TempDir
    Path directory;

    @Test
    void anOldFileGetsTheNewSection() throws IOException, ConfigException {
        // No `webauthn` anywhere, and a nested section beside it so this is not a file with none at all.
        final Path file = directory.resolve("web.yml");
        Files.writeString(file, """
                port: 8080
                public-url: https://steward.dev.nordtal.eu
                session-days: 30
                discord:
                  client-id: an-application
                """);

        final WebSpec config = ConfigLoader.builder(file, WebSpec.class).load().get();

        assertNotNull(
                config.webauthn(),
                "the loader handed back a null section, so every start would fail on a"
                        + " NullPointerException rather than on a message");
        assertEquals(
                "nordtal.eu",
                config.webauthn().relyingPartyId(),
                "a preserved file with no webauthn block must fall back to the spec's default");

        final String after = Files.readString(file);
        assertTrue(
                after.contains("relying-party-id: nordtal.eu"),
                "the loader did not write the new section into the preserved file, so setting it"
                        + " by hand in the config volume is a DEPLOYMENT STEP and the comment in"
                        + " WebSpec has to say so. The file now reads:\n" + after);

        // What was already there is untouched.
        assertEquals(8080, config.port());
        assertEquals("an-application", config.discord().clientId());
    }
}
