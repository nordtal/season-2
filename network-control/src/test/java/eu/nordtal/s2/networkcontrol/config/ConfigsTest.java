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

        assertEquals("nordtal", config.username());
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
        assertEquals(300, config.playtimeFlushIntervalSeconds(),
                "five minutes is the decided flush interval, settled 2026-08-31 - a proxy crash "
                        + "costing up to five minutes of play time is the accepted trade");
        assertEquals("limbo", config.serverLimbo());
        assertEquals("hunger-games", config.serverHungerGames());
        assertEquals("smp", config.serverSmp());
    }

    @Test
    void theServerNamesDefaultToTheModuleDirectoryNames() throws Exception {
        // Nothing in docs/ says what velocity.toml calls the three backends. The defaults are the
        // module directory names, which are already the runtime identity of the three Paper
        // plugins; if the proxy calls them something else, these are the keys to change.
        final GateSpec config = Configs.gate(directory, LOGGER).get();

        assertEquals("limbo", config.serverLimbo(), "MAINTENANCE routes here");
        assertEquals("hunger-games", config.serverHungerGames(), "PRE_EVENT and START_EVENT route here");
        assertEquals("smp", config.serverSmp(), "SMP routes here");
    }

    @Test
    void aBlankServerNameIsRejected() throws Exception {
        // A name that could never resolve to a registered server is certainly a mistake, unlike a
        // name that simply does not match this proxy's velocity.toml - which is not checkable here.
        writeGate("server-limbo: ''");

        final ConfigValidationException error = assertThrows(ConfigValidationException.class,
                () -> Configs.gate(directory, LOGGER));
        assertTrue(error.getMessage().contains("server-limbo"), error.getMessage());
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
                "the row in docs/state-of-play.md#the-unverified-assumptions fallback is to drop NOTIFY and keep the poll");
    }

    /**
     * Writes a complete, valid {@code gate.yml} with one line replaced. jcore stops the load on a
     * key the interface does not declare <em>and</em> on a missing one, so every test needs the
     * whole file rather than the one value it cares about.
     */
    // ------------------------------------------------------------- pack.yml

    private static final String REAL_LOOKING_SHA1 = "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c";

    @Test
    void aFreshPackConfigIsEnabledButRefusesToStartUntilItIsFilledIn() throws Exception {
        // The standing rule for every value nobody can guess (docs/README.md, "Ids never get real
        // defaults"), applied to the pack: enabled by default because a production network has one,
        // and empty by default because a default pointing at somebody's release would be worse than
        // none. The proxy therefore fails closed on a fresh install rather than letting everybody in
        // without the pack, which is exactly the outcome limbo exists to prevent.
        assertThrows(ConfigValidationException.class, () -> Configs.pack(directory, LOGGER));
        assertTrue(Files.isRegularFile(directory.resolve("pack.yml")),
                "the defaults must still be written out, or there is nothing to fill in");
    }

    @Test
    void aFilledInPackConfigLoadsWithTheDocumentedDefaults() throws Exception {
        writePack("https://github.com/nordtal/season-2/releases/download/v0.1.0/pack.zip",
                REAL_LOOKING_SHA1, true, true, 180);

        final PackSpec config = Configs.pack(directory, LOGGER).get();

        assertTrue(config.enabled());
        assertTrue(config.force(), "docs/architecture.md: the offer is forced, decided 2026-09-01");
        assertEquals(REAL_LOOKING_SHA1, config.sha1());
        assertEquals(180, config.applyTimeoutSeconds());
    }

    @Test
    void aDisabledPackIsAllowedToLeaveTheUrlAndHashEmpty() throws Exception {
        // The escape hatch for a development proxy and for the hours between "the network is up" and
        // "the first pack release exists". Refusing to start over values nothing reads would make
        // the escape hatch harder to use than the thing it escapes.
        writePack("", "", false, true, 180);

        final PackSpec config = Configs.pack(directory, LOGGER).get();

        assertFalse(config.enabled());
        assertEquals("", config.url());
    }

    @Test
    void anEmptyUrlIsRejectedWhileThePackIsEnabled() throws Exception {
        writePack("", REAL_LOOKING_SHA1, true, true, 180);

        final ConfigValidationException error = assertThrows(ConfigValidationException.class,
                () -> Configs.pack(directory, LOGGER));
        assertTrue(error.getMessage().contains("url"), error.getMessage());
    }

    @Test
    void aUrlTheClientCannotDownloadFromIsRejected() throws Exception {
        // A path or a file: URL is the mistake somebody makes once, and the client's answer to it is
        // INVALID_URL for every player at the same moment.
        writePack("/var/www/pack.zip", REAL_LOOKING_SHA1, true, true, 180);

        final ConfigValidationException error = assertThrows(ConfigValidationException.class,
                () -> Configs.pack(directory, LOGGER));
        assertTrue(error.getMessage().contains("url"), error.getMessage());
    }

    @Test
    void aHashThatIsNotFortyHexCharactersIsRejected() throws Exception {
        // The mistake this file exists to prevent: a hash typed by hand, truncated in a copy, or
        // left behind from the previous release. Length and alphabet are all that can be checked
        // here - whether it is the hash of the zip at `url` is a question only the client answers,
        // and it answers it with FAILED_DOWNLOAD, which reads as a network problem and is not one.
        for (final String wrong : new String[]{"deadbeef", REAL_LOOKING_SHA1 + "0", "sha1-" + REAL_LOOKING_SHA1,
                "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3g"}) {
            writePack("https://example.invalid/pack.zip", wrong, true, true, 180);

            final ConfigValidationException error = assertThrows(ConfigValidationException.class,
                    () -> Configs.pack(directory, LOGGER), wrong);
            assertTrue(error.getMessage().contains("sha1"), error.getMessage());
        }
    }

    @Test
    void anUppercaseHashIsAccepted() throws Exception {
        // Some tools write it uppercase. Refusing that would be a rule about typography, not safety.
        writePack("https://example.invalid/pack.zip", REAL_LOOKING_SHA1.toUpperCase(java.util.Locale.ROOT),
                true, true, 180);

        assertEquals(REAL_LOOKING_SHA1.toUpperCase(java.util.Locale.ROOT),
                Configs.pack(directory, LOGGER).get().sha1());
    }

    @Test
    void aZeroApplyTimeoutIsRejectedEvenWhenThePackIsOff() throws Exception {
        // Checked before the enabled/disabled branch, because a zero here would disconnect every
        // player the instant they were offered the pack - and the value is read by the sweep
        // regardless of which way `enabled` is set.
        writePack("", "", false, true, 0);

        final ConfigValidationException error = assertThrows(ConfigValidationException.class,
                () -> Configs.pack(directory, LOGGER));
        assertTrue(error.getMessage().contains("apply-timeout-seconds"), error.getMessage());
    }

    private void writePack(final String url, final String sha1, final boolean enabled,
                           final boolean force, final int timeout) throws Exception {
        Files.writeString(directory.resolve("pack.yml"), """
                enabled: %s
                url: '%s'
                sha1: '%s'
                force: %s
                apply-timeout-seconds: %d
                """.formatted(enabled, url, sha1, force, timeout));
    }

    private void writeGate(final String override) throws Exception {
        final String[] defaults = {
                "discord-invite-url: ''",
                "link-code-ttl-minutes: 10",
                "fallback-cache-window-minutes: 15",
                "expiry-check-interval-seconds: 60",
                "expiry-warning-lead-minutes: 5",
                "phase-poll-interval-seconds: 30",
                "phase-listen-enabled: true",
                "playtime-flush-interval-seconds: 300",
                "server-limbo: limbo",
                "server-hunger-games: hunger-games",
                "server-smp: smp",
        };
        final String key = override.substring(0, override.indexOf(':') + 1);
        final StringBuilder yaml = new StringBuilder();
        for (final String line : defaults) {
            yaml.append(line.startsWith(key) ? override : line).append('\n');
        }
        Files.writeString(directory.resolve("gate.yml"), yaml.toString());
    }
}
