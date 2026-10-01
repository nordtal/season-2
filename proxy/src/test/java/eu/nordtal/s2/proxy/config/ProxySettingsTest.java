package eu.nordtal.s2.proxy.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.settings.DatabasePool;
import eu.nordtal.s2.settings.DatabaseSpec;
import eu.nordtal.s2.settings.Environment;
import eu.nordtal.s2.settings.EnvironmentSettings;
import eu.nordtal.s2.settings.Group;
import eu.nordtal.s2.settings.MemorySettingStore;
import eu.nordtal.s2.settings.SettingsException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Every invalid value in the proxy's own settings is refused by name. */
class ProxySettingsTest {

    private final MemorySettingStore store = new MemorySettingStore();

    /** What an admin stored, taken by the next load. */
    private final Map<String, Object> values = new LinkedHashMap<>();

    // database, from the environment

    @Test
    void aFreshDirectoryGetsWorkingDefaults() throws Exception {
        final DatabaseSpec config = database();

        assertEquals("nordtal", config.username());
        assertEquals(5, config.maximumPoolSize());
        assertEquals(3, config.queryTimeoutSeconds());
    }

    @Test
    void aNonPostgresqlJdbcUrlIsRejected() throws Exception {
        values.put("NORDTAL_PROXY_DATABASE_JDBC_URL", "jdbc:mysql://localhost:3306/access");

        final SettingsException error = assertThrows(SettingsException.class, () -> database());
        assertTrue(error.getMessage().contains("jdbc-url"), error.getMessage());
    }

    @Test
    void aZeroQueryTimeoutIsRejected() throws Exception {
        values.put("NORDTAL_PROXY_DATABASE_QUERY_TIMEOUT_SECONDS", "0");

        final SettingsException error = assertThrows(SettingsException.class, () -> database());
        assertTrue(error.getMessage().contains("query-timeout-seconds"), error.getMessage());
    }

    // gate

    @Test
    void aFreshGateConfigGetsTheDocumentedDefaults() throws Exception {
        final GateSpec config =
                store.checked("proxy", Group.of("gate", GateSpec.class).checkedBy(ProxySettings::checkGate), values);

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
        final GateSpec config =
                store.checked("proxy", Group.of("gate", GateSpec.class).checkedBy(ProxySettings::checkGate), values);

        assertEquals("limbo", config.serverLimbo(), "MAINTENANCE routes here");
        assertEquals("hunger-games", config.serverHungerGames(), "PRE_EVENT and START_EVENT route here");
        assertEquals("smp", config.serverSmp(), "SMP routes here");
    }

    @Test
    void aBlankServerNameIsRejected() throws Exception {
        // A name that could never resolve to a registered server is a mistake; a velocity.toml mismatch is not.
        values.put("server-limbo", "");

        final SettingsException error = assertThrows(
                SettingsException.class,
                () -> store.checked(
                        "proxy", Group.of("gate", GateSpec.class).checkedBy(ProxySettings::checkGate), values));
        assertTrue(error.getMessage().contains("server-limbo"), error.getMessage());
    }

    @Test
    void aNegativeFallbackWindowIsRejected() throws Exception {
        values.put("fallback-cache-window-minutes", -1);

        final SettingsException error = assertThrows(
                SettingsException.class,
                () -> store.checked(
                        "proxy", Group.of("gate", GateSpec.class).checkedBy(ProxySettings::checkGate), values));
        assertTrue(error.getMessage().contains("fallback-cache-window-minutes"), error.getMessage());
    }

    @Test
    void aZeroLinkCodeTtlIsRejected() throws Exception {
        values.put("link-code-ttl-minutes", 0);

        final SettingsException error = assertThrows(
                SettingsException.class,
                () -> store.checked(
                        "proxy", Group.of("gate", GateSpec.class).checkedBy(ProxySettings::checkGate), values));
        assertTrue(error.getMessage().contains("link-code-ttl-minutes"), error.getMessage());
    }

    @Test
    void aNegativePlaytimeFlushIntervalIsRejected() throws Exception {
        values.put("playtime-flush-interval-seconds", -30);

        final SettingsException error = assertThrows(
                SettingsException.class,
                () -> store.checked(
                        "proxy", Group.of("gate", GateSpec.class).checkedBy(ProxySettings::checkGate), values));
        assertTrue(error.getMessage().contains("playtime-flush-interval-seconds"), error.getMessage());
    }

    // pack

    private static final String REAL_LOOKING_SHA1 = "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c";

    @Test
    void aFreshPackConfigIsEnabledButRefusesToStartUntilItIsFilledIn() throws Exception {
        // Enabled but empty by default, so a fresh install fails closed.
        assertThrows(
                SettingsException.class,
                () -> store.checked(
                        "proxy", Group.of("pack", PackSpec.class).checkedBy(ProxySettings::checkPack), values));
    }

    @Test
    void aFilledInPackConfigLoadsWithTheDocumentedDefaults() throws Exception {
        writePack(
                "https://github.com/nordtal/season-2/releases/download/v0.1.0/pack.zip",
                REAL_LOOKING_SHA1,
                true,
                true,
                180);

        final PackSpec config =
                store.checked("proxy", Group.of("pack", PackSpec.class).checkedBy(ProxySettings::checkPack), values);

        assertTrue(config.enabled());
        assertTrue(config.force(), "the pack offer is forced");
        assertEquals(REAL_LOOKING_SHA1, config.sha1());
        assertEquals(180, config.applyTimeoutSeconds());
    }

    @Test
    void aDisabledPackIsAllowedToLeaveTheUrlAndHashEmpty() throws Exception {
        // The escape hatch for a development proxy, which must not be refused over values nothing reads.
        writePack("", "", false, true, 180);

        final PackSpec config =
                store.checked("proxy", Group.of("pack", PackSpec.class).checkedBy(ProxySettings::checkPack), values);

        assertFalse(config.enabled());
        assertEquals("", config.url());
    }

    @Test
    void anEmptyUrlIsRejectedWhileThePackIsEnabled() throws Exception {
        writePack("", REAL_LOOKING_SHA1, true, true, 180);

        final SettingsException error = assertThrows(
                SettingsException.class,
                () -> store.checked(
                        "proxy", Group.of("pack", PackSpec.class).checkedBy(ProxySettings::checkPack), values));
        assertTrue(error.getMessage().contains("url"), error.getMessage());
    }

    @Test
    void aUrlTheClientCannotDownloadFromIsRejected() throws Exception {
        // A path or a file: URL makes the client answer INVALID_URL for every player.
        writePack("/var/www/pack.zip", REAL_LOOKING_SHA1, true, true, 180);

        final SettingsException error = assertThrows(
                SettingsException.class,
                () -> store.checked(
                        "proxy", Group.of("pack", PackSpec.class).checkedBy(ProxySettings::checkPack), values));
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
                    () -> store.checked(
                            "proxy", Group.of("pack", PackSpec.class).checkedBy(ProxySettings::checkPack), values),
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
                store.checked("proxy", Group.of("pack", PackSpec.class).checkedBy(ProxySettings::checkPack), values)
                        .sha1());
    }

    @Test
    void aZeroApplyTimeoutIsRejectedEvenWhenThePackIsOff() throws Exception {
        // Checked before the enabled branch, since the sweep reads it regardless.
        writePack("", "", false, true, 0);

        final SettingsException error = assertThrows(
                SettingsException.class,
                () -> store.checked(
                        "proxy", Group.of("pack", PackSpec.class).checkedBy(ProxySettings::checkPack), values));
        assertTrue(error.getMessage().contains("apply-timeout-seconds"), error.getMessage());
    }

    private void writePack(
            final String url, final String sha1, final boolean enabled, final boolean force, final int timeout) {
        values.put("enabled", enabled);
        values.put("url", url);
        values.put("sha1", sha1);
        values.put("force", force);
        values.put("apply-timeout-seconds", timeout);
    }

    // network

    @Test
    void aFreshNetworkConfigLoadsAndCarriesAMotdForEveryPhase() throws Exception {
        final NetworkSpec config = store.checked(
                "proxy", Group.of("network", NetworkSpec.class).checkedBy(ProxySettings::checkNetwork), values);

        assertEquals(500, config.maxPlayers());

        // The nested MotdSpec needs its own @ConfigSpec to survive the round trip.
        for (final SeasonPhase phase : SeasonPhase.values()) {
            assertFalse(motdFor(config, phase).isBlank(), "no MOTD for " + phase);
        }
    }

    @Test
    void everyPhaseGetsItsOwnMotdRatherThanOneSharedLine() throws Exception {
        // Two phases sharing a default would make five keys pointless.
        final NetworkSpec config = store.checked(
                "proxy", Group.of("network", NetworkSpec.class).checkedBy(ProxySettings::checkNetwork), values);
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
    void everyDefaultMotdOpensWithTheOneBrandMark() throws Exception {
        // One mark, and the phase is what the second line says: a name that changes colour is five marks.
        final NetworkSpec config = store.checked(
                "proxy", Group.of("network", NetworkSpec.class).checkedBy(ProxySettings::checkNetwork), values);
        for (final SeasonPhase phase : SeasonPhase.values()) {
            final String motd = motdFor(config, phase);
            assertTrue(motd.startsWith(NetworkSpec.MotdSpec.NORDTAL_BLUE), phase + " opens with its own mark: " + motd);
            assertFalse(motd.contains("<gradient:"), phase + " colours the name itself: " + motd);
            assertEquals(
                    motd.indexOf("nordtal.eu"),
                    motd.lastIndexOf("nordtal.eu"),
                    phase + " writes the name out a second time: " + motd);
        }
    }

    @Test
    void theBrandColourIsTheLogosBlueLightened() {
        // The logo's #24357d off resource-pack/src/pack.png, lightened for the server browser's near-black list.
        assertEquals("<#4a63d8><bold>nordtal.eu</bold></#4a63d8>", NetworkSpec.MotdSpec.NORDTAL_BLUE);
    }

    @Test
    void aFreshNetworkConfigCarriesTheAllowlistOfOurOwnPlayerCommands() throws Exception {
        // The default is the assertion: this list is what every player on the network can type.
        final NetworkSpec config = store.checked(
                "proxy", Group.of("network", NetworkSpec.class).checkedBy(ProxySettings::checkNetwork), values);

        assertEquals(
                List.of("navigate", "poi", "hg ready", "msg", "whisper", "r", "discord", "rules"),
                config.commandAllowlist());
    }

    @Test
    void aBlankAllowlistEntryIsRejectedBecauseItWouldBeDroppedSilently() throws Exception {
        // A blank entry has no segments and would match every command.
        values.put("command-allowlist", List.of("msg", ""));

        final SettingsException error = assertThrows(
                SettingsException.class,
                () -> store.checked(
                        "proxy",
                        Group.of("network", NetworkSpec.class).checkedBy(ProxySettings::checkNetwork),
                        values));
        assertTrue(error.getMessage().contains("command-allowlist"), error.getMessage());
    }

    @Test
    void anEmptyAllowlistIsAllowedBecauseLockingTheNetworkDownIsALegitimateThingToWant() throws Exception {
        // An empty allowlist is legitimate and is not refused.
        values.put("command-allowlist", List.of());

        assertEquals(
                List.of(),
                store.checked(
                                "proxy",
                                Group.of("network", NetworkSpec.class).checkedBy(ProxySettings::checkNetwork),
                                values)
                        .commandAllowlist());
    }

    @Test
    void anEmptyMotdIsRejectedRatherThanShownAsAnEmptyServerBrowserEntry() throws Exception {
        values.put("motd.pre-launch", "");

        final SettingsException error = assertThrows(
                SettingsException.class,
                () -> store.checked(
                        "proxy",
                        Group.of("network", NetworkSpec.class).checkedBy(ProxySettings::checkNetwork),
                        values));
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

    /** Takes the proxy's database settings from an environment holding what {@link #values} names. */
    private DatabaseSpec database() throws SettingsException {
        return EnvironmentSettings.of(
                        Environment.of("NORDTAL_PROXY").withMain("proxy").reading(name -> {
                            final Object value = values.get(name);
                            return value == null ? null : value.toString();
                        }))
                .load(Group.of("database", DatabaseSpec.class).checkedBy(DatabasePool::check))
                .get();
    }
}
