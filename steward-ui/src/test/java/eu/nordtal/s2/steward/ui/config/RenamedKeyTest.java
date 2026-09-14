package eu.nordtal.s2.steward.ui.config;

import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.exception.ConfigException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What happens to a deployment whose {@code steward-ui.yml} was written before a key was renamed.
 *
 * <h2>Why this is worth a test of its own</h2>
 * jcore writes a config file once and then <em>preserves</em> it, so a changed {@code default} in a
 * {@code @ConfigSpec} never reaches a deployment that has already run. That is written down in this
 * workspace's guide as a rule of thumb, and it has already cost one thing: {@code stop-services}
 * still named {@code bot} after that service was renamed to {@code discord-bot}, and had to be
 * carried across by hand.
 *
 * <p>A <b>rename</b> is not obviously the same case as a changed default, and the difference
 * decides whether {@code session-hours} → {@code session-days} is a deployment step or only a
 * commit. Guessing either way would be guessing about the live deployment, so it is asked here of
 * the real loader against a real file.</p>
 */
class RenamedKeyTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("a file written before the rename gains the new key at its default")
    void anOldFileGetsTheNewKey() throws IOException, ConfigException {
        // Exactly what is in the nordtal-s2_steward-ui-config volume today, cut down to the keys
        // this is about. The point is that `session-days` is absent and `session-hours` is not.
        final Path file = directory.resolve("steward-ui.yml");
        Files.writeString(file, """
                port: 8080
                public-url: https://steward.dev.nordtal.eu
                session-hours: 12
                """);

        final UiSpec config = ConfigLoader.builder(file, UiSpec.class).load().get();

        assertEquals(30, config.sessionDays(),
                "a preserved file with no session-days must fall back to the spec's default, not"
                        + " to zero - a zero would sign everybody out on the redirect that signed"
                        + " them in");

        final String after = Files.readString(file);
        assertTrue(after.contains("session-days: 30"), "the loader did not write the new key into"
                + " the preserved file, so correcting it by hand is a deployment step and the"
                + " comment in UiSpec has to say so. The file now reads:\n" + after);

        // And the value the operator had actually chosen is NOT carried over: 12 hours does not
        // become 12 days or half a day. Nobody renames a key and keeps the number, and a test that
        // did not say so out loud would leave the next person wondering.
        assertEquals(30, config.sessionDays());
    }
}
