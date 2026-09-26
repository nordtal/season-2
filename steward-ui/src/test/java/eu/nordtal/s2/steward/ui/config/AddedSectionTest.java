package eu.nordtal.s2.steward.ui.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.exception.ConfigException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What happens to the deployment's {@code steward-ui.yml} when a whole SECTION is added to the spec.
 *
 * <h2>Why this is a second test and not a line in {@link RenamedKeyTest}</h2>
 * That one measured a scalar: a file with no {@code session-days} gains the key at its default.
 * {@code webauthn} is not a scalar, it is a nested block with a key inside it, and jcore's
 * behaviour for one does not follow from its behaviour for the other. The difference decides
 * something real: whether adding the second factor is a commit, or a commit <em>plus</em> somebody
 * editing a file inside the {@code nordtal-s2_steward-ui-config} volume before the container will
 * start at all - because {@code Configs.ui} refuses a null relying party.
 *
 * <p>Guessing either way would be guessing about the live deployment, which is the mistake this
 * workspace's guide already records once ({@code backup.stop-services} still naming {@code bot}
 * after the rename). So it is asked of the real loader against a real file.</p>
 */
class AddedSectionTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("a file written before the section existed gains the whole block at its defaults")
    void anOldFileGetsTheNewSection() throws IOException, ConfigException {
        // A cut-down version of what is in the volume today: no `webauthn` anywhere, and a nested
        // section beside it so that this is not accidentally testing a file with no sections at all.
        final Path file = directory.resolve("steward-ui.yml");
        Files.writeString(file, """
                port: 8080
                public-url: https://steward.dev.nordtal.eu
                session-days: 30
                worker:
                  base-url: http://steward-worker:8082
                  token: ''
                """);

        final UiSpec config = ConfigLoader.builder(file, UiSpec.class).load().get();

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
                        + " UiSpec has to say so. The file now reads:\n" + after);

        // And what was already there is untouched - the reason a written file is preserved at all.
        assertEquals(8080, config.port());
        assertEquals("http://steward-worker:8082", config.worker().baseUrl());
    }
}
