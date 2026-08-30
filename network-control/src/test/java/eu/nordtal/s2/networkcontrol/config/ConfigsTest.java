package eu.nordtal.s2.networkcontrol.config;

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
 * The fail-fast for {@code network-control}'s own two config files - same philosophy as
 * {@code access-bot}'s {@code ConfigsTest}: everything here is a value that must stop the gate
 * from starting rather than surface as a confusing failure later (a query timeout of zero, a
 * negative cache window).
 * <p>
 * Unlike the bot's version, {@link Configs#database(Path, Logger)} and
 * {@link Configs#gate(Path, Logger)} take the directory directly rather than through a system
 * property - Velocity hands the plugin its data directory via {@code @DataDirectory}, so there is
 * no equivalent of the bot's {@code -Daccess.config.dir} test hook to begin with.
 * </p>
 */
class ConfigsTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigsTest.class);

    @TempDir
    Path directory;

    // ------------------------------------------------------------- database.yml

    @Test
    void aFreshDirectoryGetsWorkingDefaults() throws Exception {
        final DatabaseSpec config = Configs.database(directory, LOGGER).get();

        assertEquals("access", config.username());
        assertEquals(5, config.maximumPoolSize());
        assertEquals(3, config.queryTimeoutSeconds());
        assertTrue(Files.isRegularFile(directory.resolve("database.yml")),
                "a fresh load must write the defaults out, the same as every other config in this repo");
    }

    @Test
    void aNonPostgresqlJdbcUrlIsRejected() throws Exception {
        Files.writeString(directory.resolve("database.yml"), """
                jdbc-url: 'jdbc:mysql://localhost:3306/access'
                username: access
                password: ''
                maximum-pool-size: 5
                query-timeout-seconds: 3
                """);

        final ConfigValidationException error = assertThrows(ConfigValidationException.class,
                () -> Configs.database(directory, LOGGER));
        assertTrue(error.getMessage().contains("jdbc-url"), error.getMessage());
    }

    @Test
    void aZeroQueryTimeoutIsRejected() throws Exception {
        Files.writeString(directory.resolve("database.yml"), """
                jdbc-url: 'jdbc:postgresql://localhost:5432/access'
                username: access
                password: ''
                maximum-pool-size: 5
                query-timeout-seconds: 0
                """);

        final ConfigValidationException error = assertThrows(ConfigValidationException.class,
                () -> Configs.database(directory, LOGGER));
        assertTrue(error.getMessage().contains("query-timeout-seconds"), error.getMessage());
    }

    // ------------------------------------------------------------- gate.yml

    @Test
    void aFreshGateConfigGetsTheDocumentedDefaults() throws Exception {
        final GateSpec config = Configs.gate(directory, LOGGER).get();

        assertEquals("", config.discordInviteUrl());
        assertEquals(10, config.linkCodeTtlMinutes());
        assertEquals(15, config.fallbackCacheWindowMinutes());
        assertEquals(60, config.expiryCheckIntervalSeconds());
        assertEquals(5, config.expiryWarningLeadMinutes());
    }

    @Test
    void aNegativeFallbackWindowIsRejected() throws Exception {
        Files.writeString(directory.resolve("gate.yml"), """
                discord-invite-url: ''
                link-code-ttl-minutes: 10
                fallback-cache-window-minutes: -1
                expiry-check-interval-seconds: 60
                expiry-warning-lead-minutes: 5
                """);

        final ConfigValidationException error = assertThrows(ConfigValidationException.class,
                () -> Configs.gate(directory, LOGGER));
        assertTrue(error.getMessage().contains("fallback-cache-window-minutes"), error.getMessage());
    }

    @Test
    void aZeroLinkCodeTtlIsRejected() throws Exception {
        Files.writeString(directory.resolve("gate.yml"), """
                discord-invite-url: ''
                link-code-ttl-minutes: 0
                fallback-cache-window-minutes: 15
                expiry-check-interval-seconds: 60
                expiry-warning-lead-minutes: 5
                """);

        final ConfigValidationException error = assertThrows(ConfigValidationException.class,
                () -> Configs.gate(directory, LOGGER));
        assertTrue(error.getMessage().contains("link-code-ttl-minutes"), error.getMessage());
    }
}
