package eu.nordtal.s2.updater.config;

import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.jcore.config.exception.ConfigValidationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Arcane block's fail-fast, and only that.
 * <p>
 * Everything else in {@code updater.yml} is a value whose default is the real one, so a fresh file
 * is correct and there is nothing to catch. The restart settings are the exception: they are all
 * optional <em>together</em>, and the failure mode being guarded against is a half-filled block
 * that fails with a 401 or a 404 at the one moment somebody is standing in front of a button
 * waiting for the network to come back.
 * </p>
 * <p>
 * The project id is the one worth a test of its own. It is a UUID Arcane generated, and the
 * compose project is called {@code nordtal-s2} in six other files - so writing that name here is
 * the mistake a person actually makes, and a 404 half an hour later is not when they should find
 * out.
 * </p>
 */
class ConfigsTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigsTest.class);

    @TempDir
    Path directory;

    @Test
    @DisplayName("a fresh file has no restart configured, which is a supported state")
    void aFreshFileLeavesArcaneUnconfigured() throws Exception {
        final UpdaterSpec config = Configs.updater(directory, LOGGER).get();

        assertEquals("", config.arcane().baseUrl());
        assertEquals("", config.arcane().project(), "there is no id to guess");
        assertEquals("0", config.arcane().environment(), "Arcane's own host");
        assertTrue(config.arcane().redeployPath().startsWith("/api/environments/{environment}/"),
                "the default path is the one read from Arcane's source: "
                        + config.arcane().redeployPath());
    }

    @Test
    @DisplayName("a base-url with no project id is refused, and the message says it is not a name")
    void aBaseUrlWithoutAProjectIdIsRefused() throws Exception {
        write("""
                  base-url: 'https://arcane.example.com'
                  api-key: 'token'
                  environment: '0'
                  project: ''
                """);

        final ConfigValidationException error =
                assertThrows(ConfigValidationException.class, () -> Configs.updater(directory, LOGGER));

        final String message = String.valueOf(error.getMessage() + error.getCause());
        assertTrue(message.contains("arcane.project"), message);
        assertTrue(message.contains("nordtal-s2"),
                "it names the wrong value a person would otherwise put there: " + message);
    }

    @Test
    @DisplayName("a base-url with no token is refused before anything can 401")
    void aBaseUrlWithoutATokenIsRefused() throws Exception {
        write("""
                  base-url: 'https://arcane.example.com'
                  api-key: ''
                  environment: '0'
                  project: '51b523fe-21aa-49ea-93b6-74b5217e14c1'
                """);

        final ConfigValidationException error =
                assertThrows(ConfigValidationException.class, () -> Configs.updater(directory, LOGGER));

        assertTrue(String.valueOf(error.getMessage() + error.getCause()).contains("arcane.api-key"),
                String.valueOf(error.getMessage()));
    }

    @Test
    @DisplayName("a fully filled block loads, and the two ids stay ids")
    void aCompleteArcaneBlockLoads() throws Exception {
        write("""
                  base-url: 'https://arcane.example.com'
                  api-key: 'token'
                  environment: 'db21959d-4067-4b79-991f-9b489ede02a6'
                  project: '51b523fe-21aa-49ea-93b6-74b5217e14c1'
                """);

        final UpdaterSpec config = Configs.updater(directory, LOGGER).get();

        assertEquals("db21959d-4067-4b79-991f-9b489ede02a6", config.arcane().environment());
        assertEquals("51b523fe-21aa-49ea-93b6-74b5217e14c1", config.arcane().project());
    }

    /**
     * Replaces the four Arcane values in a freshly written {@code updater.yml}.
     * <p>
     * Written by the loader first rather than by hand: this spec has twenty settings and a
     * hand-written file would be a second copy of all of them, going stale the first time one is
     * added. What is exercised here is the Arcane block, so that is the only part replaced.
     * </p>
     */
    private void write(final String arcaneBlock) throws IOException, ConfigException {
        Configs.updater(directory, LOGGER);

        final Path file = directory.resolve("updater.yml");
        final String written = Files.readString(file, StandardCharsets.UTF_8);
        final int start = written.indexOf("arcane:");
        assertTrue(start >= 0, "the written config has no arcane block any more");

        Files.writeString(file, written.substring(0, start) + "arcane:\n" + arcaneBlock,
                StandardCharsets.UTF_8);
    }
}
