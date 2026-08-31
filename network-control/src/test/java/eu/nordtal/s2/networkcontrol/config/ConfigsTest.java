package eu.nordtal.s2.networkcontrol.config;

import eu.nordtal.jcore.config.exception.ConfigValidationException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertEquals(30, config.phasePollIntervalSeconds(),
                "thirty seconds is the decided poll interval, docs/season-phases.md 2026-08-31");
        assertTrue(config.phaseListenEnabled(),
                "LISTEN/NOTIFY is built in the first pass rather than deferred, so it is on by default");
        assertEquals(60, config.playtimeFlushIntervalSeconds());
    }

    @Test
    void aNegativeFallbackWindowIsRejected() throws Exception {
        writeGate("fallback-cache-window-minutes: -1");

        final ConfigValidationException error = assertThrows(ConfigValidationException.class,
                () -> Configs.gate(directory, LOGGER));
        assertTrue(error.getMessage().contains("fallback-cache-window-minutes"), error.getMessage());
    }

    @Test
    void aZeroLinkCodeTtlIsRejected() throws Exception {
        writeGate("link-code-ttl-minutes: 0");

        final ConfigValidationException error = assertThrows(ConfigValidationException.class,
                () -> Configs.gate(directory, LOGGER));
        assertTrue(error.getMessage().contains("link-code-ttl-minutes"), error.getMessage());
    }

    @Test
    void aZeroPhasePollIntervalIsRejected() throws Exception {
        // A poll interval of zero would schedule a task with no repeat and leave the proxy on
        // whatever phase it read at startup - the one failure mode the poll exists to prevent.
        writeGate("phase-poll-interval-seconds: 0");

        final ConfigValidationException error = assertThrows(ConfigValidationException.class,
                () -> Configs.gate(directory, LOGGER));
        assertTrue(error.getMessage().contains("phase-poll-interval-seconds"), error.getMessage());
    }

    @Test
    void aNegativePlaytimeFlushIntervalIsRejected() throws Exception {
        writeGate("playtime-flush-interval-seconds: -30");

        final ConfigValidationException error = assertThrows(ConfigValidationException.class,
                () -> Configs.gate(directory, LOGGER));
        assertTrue(error.getMessage().contains("playtime-flush-interval-seconds"), error.getMessage());
    }

    @Test
    void turningTheListenerOffIsAllowedBecauseThePollIsTheGuarantee() throws Exception {
        writeGate("phase-listen-enabled: false");

        assertFalse(Configs.gate(directory, LOGGER).get().phaseListenEnabled(),
                "docs/operations.md#open-verification's fallback is to drop NOTIFY and keep the poll");
    }

    /**
     * Writes a complete, valid {@code gate.yml} with one line replaced. jcore stops the load on a
     * key the interface does not declare <em>and</em> on a missing one, so every test needs the
     * whole file rather than the one value it cares about.
     */
    private void writeGate(final String override) throws Exception {
        final String[] defaults = {
                "discord-invite-url: ''",
                "link-code-ttl-minutes: 10",
                "fallback-cache-window-minutes: 15",
                "expiry-check-interval-seconds: 60",
                "expiry-warning-lead-minutes: 5",
                "phase-poll-interval-seconds: 30",
                "phase-listen-enabled: true",
                "playtime-flush-interval-seconds: 60",
        };
        final String key = override.substring(0, override.indexOf(':') + 1);
        final StringBuilder yaml = new StringBuilder();
        for (final String line : defaults) {
            yaml.append(line.startsWith(key) ? override : line).append('\n');
        }
        Files.writeString(directory.resolve("gate.yml"), yaml.toString());
    }
}
