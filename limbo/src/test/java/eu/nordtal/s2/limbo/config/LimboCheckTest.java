package eu.nordtal.s2.limbo.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.settings.FileSettings;
import eu.nordtal.s2.settings.SettingsException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The fail-fast for {@code limbo}'s {@code config.yml}.
 *
 * A quietly wrong value here shows up as a black screen, which looks like a crash.
 */
class LimboCheckTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(LimboCheckTest.class);

    @TempDir
    Path directory;

    @Test
    void aFreshDirectoryGetsWorkingDefaults() throws Exception {
        final LimboSpec config = FileSettings.in(directory, "NORDTAL_LIMBO", LOGGER)
                .load("config", LimboSpec.class, LimboCheck::check)
                .get();

        assertEquals("limbo", config.worldName());
        assertEquals(64, config.spawnY());
        assertEquals(4, config.titleRefreshSeconds());
        assertTrue(config.blindness(), "blindness on is what makes the screen actually black");
        assertTrue(Files.isRegularFile(directory.resolve("config.yml")));
    }

    @Test
    void aZeroTitleRefreshIsRejected() throws Exception {
        // Zero means a task that never fires, and a title that expires into a blank black screen.
        write("config.yml", """
                world-name: limbo
                spawn-y: 64
                title-refresh-seconds: 0
                blindness: true
                """);

        final SettingsException error = assertThrows(
                SettingsException.class,
                () -> FileSettings.in(directory, "NORDTAL_LIMBO", LOGGER)
                        .load("config", LimboSpec.class, LimboCheck::check));
        assertTrue(error.getMessage().contains("title-refresh-seconds"), error.getMessage());
    }

    @Test
    void aBlankWorldNameIsRejected() throws Exception {
        write("config.yml", """
                world-name: ''
                spawn-y: 64
                title-refresh-seconds: 4
                blindness: true
                """);

        assertThrows(
                SettingsException.class,
                () -> FileSettings.in(directory, "NORDTAL_LIMBO", LOGGER)
                        .load("config", LimboSpec.class, LimboCheck::check));
    }

    @Test
    void aSpawnHeightOutsideAnyBuildLimitIsRejected() throws Exception {
        // Not physics, since the world is empty, but a height the server will not keep a player at.
        write("config.yml", """
                world-name: limbo
                spawn-y: 5000
                title-refresh-seconds: 4
                blindness: true
                """);

        final SettingsException error = assertThrows(
                SettingsException.class,
                () -> FileSettings.in(directory, "NORDTAL_LIMBO", LOGGER)
                        .load("config", LimboSpec.class, LimboCheck::check));
        assertTrue(error.getMessage().contains("spawn-y"), error.getMessage());
    }

    private void write(final String name, final String content) throws Exception {
        Files.writeString(directory.resolve(name), content);
    }
}
