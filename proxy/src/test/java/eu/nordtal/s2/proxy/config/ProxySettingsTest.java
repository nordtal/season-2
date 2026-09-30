package eu.nordtal.s2.proxy.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.settings.DatabasePool;
import eu.nordtal.s2.settings.DatabaseSpec;
import eu.nordtal.s2.settings.FileSettings;
import eu.nordtal.s2.settings.SettingsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Every invalid value in the proxy's own config files stops the gate from starting. */
class ProxySettingsTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProxySettingsTest.class);

    @TempDir
    Path directory;

    // database.yml

    @Test
    void aFreshDirectoryGetsWorkingDefaults() throws Exception {
        final DatabaseSpec config = FileSettings.in(directory, "NORDTAL_PROXY", "proxy", LOGGER)
                .load("database", DatabaseSpec.class, DatabasePool::check)
                .get();

        assertEquals("nordtal", config.username());
        assertEquals(5, config.maximumPoolSize());
        assertEquals(3, config.queryTimeoutSeconds());
        assertTrue(
                Files.isRegularFile(directory.resolve("database.yml")),
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

        final SettingsException error = assertThrows(
                SettingsException.class,
                () -> FileSettings.in(directory, "NORDTAL_PROXY", "proxy", LOGGER)
                        .load("database", DatabaseSpec.class, DatabasePool::check));
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

        final SettingsException error = assertThrows(
                SettingsException.class,
                () -> FileSettings.in(directory, "NORDTAL_PROXY", "proxy", LOGGER)
                        .load("database", DatabaseSpec.class, DatabasePool::check));
        assertTrue(error.getMessage().contains("query-timeout-seconds"), error.getMessage());
    }

    // gate.yml

    @Test
    void aFreshGateConfigGetsTheDocumentedDefaults() throws Exception {
        final GateSpec config = FileSettings.in(directory, "NORDTAL_PROXY", "proxy", LOGGER)
                .load("gate", GateSpec.class, ProxySettings::checkGate)
                .get();

        assertEquals(
                "https://nordtal.eu",
                config.discordInviteUrl(),
                "the website, not an invite link: nordtal.eu forwards to the "
                        + "Discord and an address that never changes beats one that can expire");
        assertEquals(10, config.linkCodeTtlMinutes());
        assertEquals(15, config.fallbackCacheWindowMinutes());
        assertEquals(60, config.expiryCheckIntervalSeconds());
        assertEquals(5, config.expiryWarningLeadMinutes());
        assertEquals(
                300,
                config.playtimeFlushIntervalSeconds(),
                "five minutes is the decided flush interval - a proxy crash "
                        + "costing up to five minutes of play time is the accepted trade");
        assertEquals("limbo", config.serverLimbo());
        assertEquals("hunger-games", config.serverHungerGames());
        assertEquals("smp", config.serverSmp());
    }

    @Test
    void theServerNamesDefaultToTheModuleDirectoryNames() throws Exception {
        // The defaults are the module directory names, the runtime identity of the three Paper plugins.
        final GateSpec config = FileSettings.in(directory, "NORDTAL_PROXY", "proxy", LOGGER)
                .load("gate", GateSpec.class, ProxySettings::checkGate)
                .get();

        assertEquals("limbo", config.serverLimbo(), "MAINTENANCE routes here");
        assertEquals("hunger-games", config.serverHungerGames(), "PRE_EVENT and START_EVENT route here");
        assertEquals("smp", config.serverSmp(), "SMP routes here");
    }

    @Test
    void aBlankServerNameIsRejected() throws Exception {
        // A name that could never resolve to a registered server is a mistake; a velocity.toml mismatch is not.
        writeGate("server-limbo: ''");

        final SettingsException error = assertThrows(
                SettingsException.class,
                () -> FileSettings.in(directory, "NORDTAL_PROXY", "proxy", LOGGER)
                        .load("gate", GateSpec.class, ProxySettings::checkGate));
        assertTrue(error.getMessage().contains("server-limbo"), error.getMessage());
    }

    @Test
    void aNegativeFallbackWindowIsRejected() throws Exception {
        writeGate("fallback-cache-window-minutes: -1");

        final SettingsException error = assertThrows(
                SettingsException.class,
                () -> FileSettings.in(directory, "NORDTAL_PROXY", "proxy", LOGGER)
                        .load("gate", GateSpec.class, ProxySettings::checkGate));
        assertTrue(error.getMessage().contains("fallback-cache-window-minutes"), error.getMessage());
    }

    @Test
    void aZeroLinkCodeTtlIsRejected() throws Exception {
        writeGate("link-code-ttl-minutes: 0");

        final SettingsException error = assertThrows(
                SettingsException.class,
                () -> FileSettings.in(directory, "NORDTAL_PROXY", "proxy", LOGGER)
                        .load("gate", GateSpec.class, ProxySettings::checkGate));
        assertTrue(error.getMessage().contains("link-code-ttl-minutes"), error.getMessage());
    }

    @Test
    void aNegativePlaytimeFlushIntervalIsRejected() throws Exception {
        writeGate("playtime-flush-interval-seconds: -30");

        final SettingsException error = assertThrows(
                SettingsException.class,
                () -> FileSettings.in(directory, "NORDTAL_PROXY", "proxy", LOGGER)
                        .load("gate", GateSpec.class, ProxySettings::checkGate));
        assertTrue(error.getMessage().contains("playtime-flush-interval-seconds"), error.getMessage());
    }

    // pack.yml

    private static final String REAL_LOOKING_SHA1 = "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c";

    @Test
    void aFreshPackConfigIsEnabledButRefusesToStartUntilItIsFilledIn() throws Exception {
        // Enabled but empty by default, so a fresh install fails closed.
        assertThrows(
                SettingsException.class,
                () -> FileSettings.in(directory, "NORDTAL_PROXY", "proxy", LOGGER)
                        .load("pack", PackSpec.class, ProxySettings::checkPack));
        assertTrue(
                Files.isRegularFile(directory.resolve("pack.yml")),
                "the defaults must still be written out, or there is nothing to fill in");
    }

    @Test
    void aFilledInPackConfigLoadsWithTheDocumentedDefaults() throws Exception {
        writePack(
                "https://github.com/nordtal/season-2/releases/download/v0.1.0/pack.zip",
                REAL_LOOKING_SHA1,
                true,
                true,
                180);

        final PackSpec config = FileSettings.in(directory, "NORDTAL_PROXY", "proxy", LOGGER)
                .load("pack", PackSpec.class, ProxySettings::checkPack)
                .get();

        assertTrue(config.enabled());
        assertTrue(config.force(), "the pack offer is forced");
        assertEquals(REAL_LOOKING_SHA1, config.sha1());
        assertEquals(180, config.applyTimeoutSeconds());
    }

    @Test
    void aDisabledPackIsAllowedToLeaveTheUrlAndHashEmpty() throws Exception {
        // The escape hatch for a development proxy, which must not be refused over values nothing reads.
        writePack("", "", false, true, 180);

        final PackSpec config = FileSettings.in(directory, "NORDTAL_PROXY", "proxy", LOGGER)
                .load("pack", PackSpec.class, ProxySettings::checkPack)
                .get();

        assertFalse(config.enabled());
        assertEquals("", config.url());
    }

    @Test
    void anEmptyUrlIsRejectedWhileThePackIsEnabled() throws Exception {
        writePack("", REAL_LOOKING_SHA1, true, true, 180);

        final SettingsException error = assertThrows(
                SettingsException.class,
                () -> FileSettings.in(directory, "NORDTAL_PROXY", "proxy", LOGGER)
                        .load("pack", PackSpec.class, ProxySettings::checkPack));
        assertTrue(error.getMessage().contains("url"), error.getMessage());
    }

    @Test
    void aUrlTheClientCannotDownloadFromIsRejected() throws Exception {
        // A path or a file: URL makes the client answer INVALID_URL for every player.
        writePack("/var/www/pack.zip", REAL_LOOKING_SHA1, true, true, 180);

        final SettingsException error = assertThrows(
                SettingsException.class,
                () -> FileSettings.in(directory, "NORDTAL_PROXY", "proxy", LOGGER)
                        .load("pack", PackSpec.class, ProxySettings::checkPack));
        assertTrue(error.getMessage().contains("url"), error.getMessage());
    }

    @Test
    void aHashThatIsNotFortyHexCharactersIsRejected() throws Exception {
        // Only length and alphabet can be checked here.
        for (final String wrong : new String[] {
            "deadbeef", REAL_LOOKING_SHA1 + "0", "sha1-" + REAL_LOOKING_SHA1, "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3g"
        }) {
            writePack("https://example.invalid/pack.zip", wrong, true, true, 180);

            final SettingsException error = assertThrows(
                    SettingsException.class,
                    () -> FileSettings.in(directory, "NORDTAL_PROXY", "proxy", LOGGER)
                            .load("pack", PackSpec.class, ProxySettings::checkPack),
                    wrong);
            assertTrue(error.getMessage().contains("sha1"), error.getMessage());
        }
    }

    @Test
    void anUppercaseHashIsAccepted() throws Exception {
        // Some tools write it uppercase, which is not a safety concern.
        writePack(
                "https://example.invalid/pack.zip",
                REAL_LOOKING_SHA1.toUpperCase(java.util.Locale.ROOT),
                true,
                true,
                180);

        assertEquals(
                REAL_LOOKING_SHA1.toUpperCase(java.util.Locale.ROOT),
                FileSettings.in(directory, "NORDTAL_PROXY", "proxy", LOGGER)
                        .load("pack", PackSpec.class, ProxySettings::checkPack)
                        .get()
                        .sha1());
    }

    @Test
    void aZeroApplyTimeoutIsRejectedEvenWhenThePackIsOff() throws Exception {
        // Checked before the enabled branch, since the sweep reads it regardless.
        writePack("", "", false, true, 0);

        final SettingsException error = assertThrows(
                SettingsException.class,
                () -> FileSettings.in(directory, "NORDTAL_PROXY", "proxy", LOGGER)
                        .load("pack", PackSpec.class, ProxySettings::checkPack));
        assertTrue(error.getMessage().contains("apply-timeout-seconds"), error.getMessage());
    }

    private void writePack(
            final String url, final String sha1, final boolean enabled, final boolean force, final int timeout)
            throws Exception {
        Files.writeString(directory.resolve("pack.yml"), """
                enabled: %s
                url: '%s'
                sha1: '%s'
                force: %s
                apply-timeout-seconds: %d
                """.formatted(enabled, url, sha1, force, timeout));
    }

    // network.yml

    @Test
    void aFreshNetworkConfigLoadsAndCarriesAMotdForEveryPhase() throws Exception {
        final NetworkSpec config = FileSettings.in(directory, "NORDTAL_PROXY", "proxy", LOGGER)
                .load("network", NetworkSpec.class, ProxySettings::checkNetwork)
                .get();

        assertEquals(500, config.maxPlayers());
        assertTrue(
                Files.isRegularFile(directory.resolve("network.yml")),
                "a fresh load must write the defaults out - and this file is also the only place the"
                        + " placeholder list is documented");

        // The nested MotdSpec needs its own @ConfigSpec to survive the round trip.
        for (final SeasonPhase phase : SeasonPhase.values()) {
            assertFalse(motdFor(config, phase).isBlank(), "no MOTD for " + phase);
        }
    }

    @Test
    void everyPhaseGetsItsOwnMotdRatherThanOneSharedLine() throws Exception {
        // Two phases sharing a default would make five keys pointless.
        final NetworkSpec config = FileSettings.in(directory, "NORDTAL_PROXY", "proxy", LOGGER)
                .load("network", NetworkSpec.class, ProxySettings::checkNetwork)
                .get();
        final Set<String> distinct = new HashSet<>();
        for (final SeasonPhase phase : SeasonPhase.values()) {
            distinct.add(motdFor(config, phase));
        }
        assertEquals(
                SeasonPhase.values().length,
                distinct.size(),
                "two phases ship the same default MOTD, so one of them is not saying anything");
    }

    @Test
    void aNetworkConfigStillCarryingBackendLimitLosesTheLineRatherThanTheProxy() throws Exception {
        // A deployed network.yml may still carry `backend-limit`; the loader drops it.
        Files.writeString(directory.resolve("network.yml"), """
                max-players: 500
                backend-limit: 1000
                snapshot-refresh-seconds: 10
                motd:
                  pre-launch: 'a'
                  pre-event: 'b'
                  start-event: 'c'
                  smp: 'd'
                  maintenance: 'e'
                """);

        final NetworkSpec config = FileSettings.in(directory, "NORDTAL_PROXY", "proxy", LOGGER)
                .load("network", NetworkSpec.class, ProxySettings::checkNetwork)
                .get();

        assertAll(
                () -> assertEquals(
                        500,
                        config.maxPlayers(),
                        "the one number there is has to survive the deletion of the retired one"),
                () -> assertFalse(
                        Files.readString(directory.resolve("network.yml")).contains("backend-limit"),
                        "the retired key stays in the file, still looking like a setting"),
                () -> assertTrue(
                        Files.readString(directory.resolve("network.yml.bak")).contains("backend-limit"),
                        "what was deleted has to be recoverable"));
    }

    @Test
    void aFreshNetworkConfigCarriesTheAllowlistOfOurOwnPlayerCommands() throws Exception {
        // The default is the assertion: this list is what every player on the network can type.
        final NetworkSpec config = FileSettings.in(directory, "NORDTAL_PROXY", "proxy", LOGGER)
                .load("network", NetworkSpec.class, ProxySettings::checkNetwork)
                .get();

        assertEquals(
                List.of("navigate", "poi", "hg ready", "msg", "whisper", "r", "discord", "rules"),
                config.commandAllowlist());
    }

    @Test
    void aBlankAllowlistEntryIsRejectedBecauseItWouldBeDroppedSilently() throws Exception {
        // A blank entry has no segments and would match every command.
        Files.writeString(directory.resolve("network.yml"), """
                max-players: 500
                snapshot-refresh-seconds: 10
                command-allowlist:
                  - msg
                  - ''
                motd:
                  pre-launch: 'a'
                  pre-event: 'b'
                  start-event: 'c'
                  smp: 'd'
                  maintenance: 'e'
                """);

        final SettingsException error = assertThrows(
                SettingsException.class,
                () -> FileSettings.in(directory, "NORDTAL_PROXY", "proxy", LOGGER)
                        .load("network", NetworkSpec.class, ProxySettings::checkNetwork));
        assertTrue(error.getMessage().contains("command-allowlist"), error.getMessage());
    }

    @Test
    void anEmptyAllowlistIsAllowedBecauseLockingTheNetworkDownIsALegitimateThingToWant() throws Exception {
        // An empty allowlist is legitimate and is not refused.
        Files.writeString(directory.resolve("network.yml"), """
                max-players: 500
                snapshot-refresh-seconds: 10
                command-allowlist: []
                motd:
                  pre-launch: 'a'
                  pre-event: 'b'
                  start-event: 'c'
                  smp: 'd'
                  maintenance: 'e'
                """);

        assertEquals(
                List.of(),
                FileSettings.in(directory, "NORDTAL_PROXY", "proxy", LOGGER)
                        .load("network", NetworkSpec.class, ProxySettings::checkNetwork)
                        .get()
                        .commandAllowlist());
    }

    @Test
    void anEmptyMotdIsRejectedRatherThanShownAsAnEmptyServerBrowserEntry() throws Exception {
        Files.writeString(directory.resolve("network.yml"), """
                max-players: 500
                snapshot-refresh-seconds: 10
                motd:
                  pre-launch: ''
                  pre-event: 'b'
                  start-event: 'c'
                  smp: 'd'
                  maintenance: 'e'
                """);

        final SettingsException error = assertThrows(
                SettingsException.class,
                () -> FileSettings.in(directory, "NORDTAL_PROXY", "proxy", LOGGER)
                        .load("network", NetworkSpec.class, ProxySettings::checkNetwork));
        assertTrue(error.getMessage().contains("motd.pre-launch"), error.getMessage());
    }

    private static String motdFor(final NetworkSpec config, final SeasonPhase phase) {
        return switch (phase) {
            case PRE_LAUNCH -> config.motd().preLaunch();
            case PRE_EVENT -> config.motd().preEvent();
            case START_EVENT -> config.motd().startEvent();
            case SMP -> config.motd().smp();
            case MAINTENANCE -> config.motd().maintenance();
        };
    }

    /** Writes a complete, valid {@code gate.yml} with one line replaced, since jcore refuses unknown keys. */
    private void writeGate(final String override) throws Exception {
        final String[] defaults = {
            "discord-invite-url: 'https://nordtal.eu'",
            "link-code-ttl-minutes: 10",
            "fallback-cache-window-minutes: 15",
            "expiry-check-interval-seconds: 60",
            "expiry-warning-lead-minutes: 5",
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
