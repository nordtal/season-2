package eu.nordtal.s2.limbo.config;

import eu.nordtal.jcore.config.exception.ConfigValidationException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fail-fast for {@code limbo}'s two config files.
 * <p>
 * Small, like the module: the only values it has are the world it builds, how often the title is
 * refreshed, and one connection. What is worth pinning is that each of them stops the plugin rather
 * than surfacing later — on a server whose whole interface is one line of text, a value that is
 * quietly wrong shows up as a black screen, which is what a crash looks like.
 * </p>
 */
class ConfigsTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigsTest.class);

    @TempDir
    Path directory;

    @Test
    void aFreshDirectoryGetsWorkingDefaults() throws Exception {
        final LimboSpec config = Configs.load(directory, LOGGER).get();

        assertEquals("limbo", config.worldName());
        assertEquals(64, config.spawnY());
        assertEquals(4, config.titleRefreshSeconds());
        assertTrue(config.blindness(), "blindness on is what makes the screen actually black");
        assertTrue(Files.isRegularFile(directory.resolve("config.yml")));
    }

    @Test
    void aZeroTitleRefreshIsRejected() throws Exception {
        // Zero would mean a task that never fires, and a title that expires after a few seconds
        // leaving a completely blank black screen behind it.
        write("config.yml", """
                world-name: limbo
                spawn-y: 64
                title-refresh-seconds: 0
                blindness: true
                """);

        final ConfigValidationException error = assertThrows(ConfigValidationException.class,
                () -> Configs.load(directory, LOGGER));
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

        assertThrows(ConfigValidationException.class, () -> Configs.load(directory, LOGGER));
    }

    @Test
    void aSpawnHeightOutsideAnyBuildLimitIsRejected() throws Exception {
        // Not physics - the world is empty - but a height the server will not keep a player at.
        write("config.yml", """
                world-name: limbo
                spawn-y: 5000
                title-refresh-seconds: 4
                blindness: true
                """);

        final ConfigValidationException error = assertThrows(ConfigValidationException.class,
                () -> Configs.load(directory, LOGGER));
        assertTrue(error.getMessage().contains("spawn-y"), error.getMessage());
    }

    @Test
    void theDatabaseDefaultsCarryTheShortTimeoutThisModuleNeeds() throws Exception {
        // Three seconds, the same value network-control uses on the login path. limbo is on that
        // path too: it makes one query per join, off the main thread, and a database that has
        // stopped answering must fail fast onto the English fallback rather than pile joins up.
        final DatabaseSpec config = Configs.database(directory, LOGGER).get();

        assertEquals(3, config.queryTimeoutSeconds());
        assertEquals(3, config.maximumPoolSize(),
                "one indexed lookup per join needs no more, and every process has its own pool");
    }

    @Test
    void aNonPostgresqlJdbcUrlIsRejected() throws Exception {
        write("database.yml", """
                jdbc-url: 'jdbc:mysql://localhost:3306/nordtal'
                username: limbo
                password: ''
                maximum-pool-size: 3
                query-timeout-seconds: 3
                """);

        final ConfigValidationException error = assertThrows(ConfigValidationException.class,
                () -> Configs.database(directory, LOGGER));
        assertTrue(error.getMessage().contains("jdbc-url"), error.getMessage());
    }

    @Test
    void aZeroQueryTimeoutIsRejected() throws Exception {
        write("database.yml", """
                jdbc-url: 'jdbc:postgresql://localhost:5432/nordtal'
                username: limbo
                password: ''
                maximum-pool-size: 3
                query-timeout-seconds: 0
                """);

        final ConfigValidationException error = assertThrows(ConfigValidationException.class,
                () -> Configs.database(directory, LOGGER));
        assertTrue(error.getMessage().contains("query-timeout-seconds"), error.getMessage());
    }

    private void write(final String name, final String content) throws Exception {
        Files.writeString(directory.resolve(name), content);
    }
}
