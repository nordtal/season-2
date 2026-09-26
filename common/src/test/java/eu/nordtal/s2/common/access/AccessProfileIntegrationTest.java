package eu.nordtal.s2.common.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.nordtal.s2.common.message.PlayerLocales;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Exercises what {@link AccessDirectory} keeps about a person against a real PostgreSQL: profiles, language, play time.
 *
 * Skips itself when no Docker daemon is reachable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccessProfileIntegrationTest {

    private static final String DISCORD_ID = "100000000000000001";
    private static final UUID MC_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static PostgreSQLContainer<?> postgres;
    private static PGSimpleDataSource dataSource;

    private AccessDirectory directory;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon reachable - skipping the PostgreSQL-backed access tests");

        postgres = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("access")
                .withUsername("access")
                .withPassword("access");
        postgres.start();

        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        AccessSchema.migrate(dataSource);
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
            postgres = null;
        }
        dataSource = null;
    }

    @BeforeEach
    void freshDirectory() {
        execute("TRUNCATE TABLE access_grant, account_link, link_code, payment_request, audit_log, "
                + "player_playtime, admin_grant, discord_user CASCADE");
        directory = AccessDirectory.using(dataSource);
    }

    // the profile cache

    @Test
    void discordProfileOfAnUnknownAccountIsEmptyNotNull() {
        assertEquals(DiscordProfile.EMPTY, directory.discordProfile("999999999999999999"));
    }

    @Test
    void minecraftProfileOfAnUnknownAccountIsEmptyNotNull() {
        assertEquals(MinecraftProfile.EMPTY, directory.minecraftProfile("999999999999999999"));
    }

    @Test
    void setDiscordProfileWritesAllThreeFieldsWithTheirOwnTimestamps() {
        directory.ensureUser(DISCORD_ID);

        directory.setDiscordProfile(DISCORD_ID, "steve", "Bau-Steve", "https://example.invalid/a.png");

        final DiscordProfile profile = directory.discordProfile(DISCORD_ID);
        assertEquals("steve", profile.username());
        assertEquals("Bau-Steve", profile.displayName());
        assertEquals("https://example.invalid/a.png", profile.avatarUrl());
        assertWithinSeconds(Instant.now(), profile.usernameUpdated(), 5);
        assertWithinSeconds(Instant.now(), profile.displayNameUpdated(), 5);
        assertWithinSeconds(Instant.now(), profile.avatarUrlUpdated(), 5);
    }

    @Test
    void setDiscordProfileCreatesTheUserRowIfItIsNotThereYet() {
        directory.setDiscordProfile("400000000000000002", "new-user", null, null);

        assertEquals("new-user", directory.discordProfile("400000000000000002").username());
    }

    @Test
    void aMemberWithNoGuildNicknameOrAvatarHasNullThereNotAnEmptyString() {
        directory.setDiscordProfile(DISCORD_ID, "steve", null, null);

        final DiscordProfile profile = directory.discordProfile(DISCORD_ID);
        assertNull(profile.displayName());
        assertNull(profile.avatarUrl());
        // The timestamp says when the absence was last confirmed, not when a value last existed.
        assertNotNull(profile.displayNameUpdated());
        assertNotNull(profile.avatarUrlUpdated());
    }

    @Test
    void leavingTheGuildClearsTheNicknameAndTheAvatarButTheUsernameMerelyGoesStale() {
        directory.setDiscordProfile(DISCORD_ID, "steve", "Bau-Steve", "https://example.invalid/a.png");

        directory.clearGuildProfile(DISCORD_ID);

        final DiscordProfile profile = directory.discordProfile(DISCORD_ID);
        assertEquals("steve", profile.username(), "the global username is not guild-scoped");
        assertNull(profile.displayName(), "the guild nickname does not survive a departure");
        assertNull(profile.avatarUrl(), "neither does the guild avatar");
    }

    @Test
    void clearGuildProfileOfAnUnknownAccountDoesNothing() {
        directory.clearGuildProfile("999999999999999999");

        assertEquals(DiscordProfile.EMPTY, directory.discordProfile("999999999999999999"));
    }

    @Test
    void setMinecraftNameWritesOntoTheLinkedAccount() {
        directory.link(DISCORD_ID, MC_UUID);

        final boolean written = directory.setMinecraftName(MC_UUID, "Notch");

        assertTrue(written);
        final MinecraftProfile profile = directory.minecraftProfile(DISCORD_ID);
        assertEquals("Notch", profile.name());
        assertWithinSeconds(Instant.now(), profile.nameUpdated(), 5);
    }

    @Test
    void aNameCannotBeCachedForAnAccountNobodyHasLinkedThereIsNoRowToWriteItOnto() {
        final boolean written = directory.setMinecraftName(UUID.randomUUID(), "Notch");

        assertFalse(written);
    }

    // a name is not a key

    @Test
    void twoDiscordAccountsMayShareEveryObservedFieldWithoutBecomingOneIdentity() {
        // Two people may share a username or nickname; a UNIQUE constraint here would fail the INSERT below.
        final String otherDiscordId = "100000000000000099";
        final UUID otherMcUuid = UUID.randomUUID();
        directory.link(DISCORD_ID, MC_UUID);
        directory.link(otherDiscordId, otherMcUuid);

        directory.setDiscordProfile(DISCORD_ID, "steve", "Steve", "https://example.invalid/a.png");
        directory.setDiscordProfile(otherDiscordId, "steve2", "Steve", "https://example.invalid/a.png");
        directory.setMinecraftName(MC_UUID, "Herobrine");
        directory.setMinecraftName(otherMcUuid, "Herobrine");

        // Each account still resolves to its own Minecraft account: the lookup is keyed on discordId.
        assertEquals(MC_UUID, directory.linkedMinecraftAccount(DISCORD_ID).orElseThrow());
        assertEquals(
                otherMcUuid, directory.linkedMinecraftAccount(otherDiscordId).orElseThrow());
        assertEquals(DISCORD_ID, directory.linkedDiscordAccount(MC_UUID).orElseThrow());
        assertEquals(otherDiscordId, directory.linkedDiscordAccount(otherMcUuid).orElseThrow());

        final DiscordProfile first = directory.discordProfile(DISCORD_ID);
        final DiscordProfile second = directory.discordProfile(otherDiscordId);
        assertEquals("Steve", first.displayName());
        assertEquals("Steve", second.displayName());
        assertNotEquals(first, second, "identical display names must not make the two records equal");
    }

    @Test
    void playerLocalesReadsTheLanguageFromTheDatabaseAtJoin() {
        directory.link(DISCORD_ID, MC_UUID);
        directory.setLocale(DISCORD_ID, Locale.GERMAN);

        // The wiring every module uses: the access directory is the LocaleSource.
        final PlayerLocales locales = new PlayerLocales(directory::locale);

        assertEquals(Locale.GERMAN, locales.join(MC_UUID));
        assertEquals(Locale.GERMAN, locales.of(MC_UUID));
    }

    @Test
    void playerLocalesHoldsTheLanguageForTheSessionAndPicksAChangeUpOnTheNextJoin() {
        directory.link(DISCORD_ID, MC_UUID);
        directory.setLocale(DISCORD_ID, Locale.GERMAN);

        final PlayerLocales locales = new PlayerLocales(directory::locale);
        locales.join(MC_UUID);

        // The player picks the English role in Discord; the bot mirrors it.
        directory.setLocale(DISCORD_ID, Locale.ENGLISH);
        assertEquals(
                Locale.GERMAN,
                locales.of(MC_UUID),
                "docs/i18n.md: a language changed mid-session takes effect on the next join, which is "
                        + "the trade for not re-querying on every message");

        locales.quit(MC_UUID);
        assertEquals(Locale.ENGLISH, locales.join(MC_UUID));
    }

    @Test
    void playerLocalesFallsBackToEnglishForAnAccountNobodyHasLinked() {
        final PlayerLocales locales = new PlayerLocales(directory::locale);

        assertEquals(Locale.ENGLISH, locales.join(UUID.randomUUID()));
    }

    @Test
    void playtimeHangsOffDiscordUserAndNotOffTheMinecraftUuid() throws SQLException {
        final SQLException orphan = assertThrows(
                SQLException.class,
                () -> executeChecked(
                        "INSERT INTO player_playtime (discord_id, seconds) VALUES ('999999999999999999', 60)"));
        assertTrue(orphan.getMessage().contains("player_playtime_discord_id_fkey"), orphan.getMessage());

        directory.ensureUser(DISCORD_ID);
        executeChecked("INSERT INTO player_playtime (discord_id, seconds) VALUES ('" + DISCORD_ID + "', 60)");
        assertEquals(1, count("SELECT count(*) FROM player_playtime WHERE seconds = 60"));
    }

    @Test
    void playtimeIsAnIntegerCountOfSecondsThatCannotGoBackwardsPastZero() {
        directory.ensureUser(DISCORD_ID);

        final SQLException negative = assertThrows(
                SQLException.class,
                () -> executeChecked(
                        "INSERT INTO player_playtime (discord_id, seconds) VALUES ('" + DISCORD_ID + "', -1)"));
        assertTrue(negative.getMessage().contains("player_playtime_seconds_not_negative"), negative.getMessage());

        // Seconds, not an interval: the proxy's flush is a plain addition, free of any time zone.
        execute("INSERT INTO player_playtime (discord_id, seconds) VALUES ('" + DISCORD_ID + "', 0)");
        execute("UPDATE player_playtime SET seconds = seconds + 86400, updated = now() WHERE discord_id = '"
                + DISCORD_ID + "'");
        assertEquals(86400, count("SELECT seconds FROM player_playtime WHERE discord_id = '" + DISCORD_ID + "'"));
    }

    /**
     * An admin may set play time outright, because the prestige tier is derived from it.
     *
     * An absolute write, unlike the proxy's {@code add}: "set this account to nine hours".
     */
    @Test
    void playtimeCanBeSetOutright() {
        directory.ensureUser(DISCORD_ID);

        directory.setPlaytimeSeconds(DISCORD_ID, 32400);
        assertEquals(
                32400,
                count("SELECT seconds FROM player_playtime WHERE discord_id = '" + DISCORD_ID + "'"),
                "the first write makes the row");

        directory.setPlaytimeSeconds(DISCORD_ID, 60);
        assertEquals(
                60,
                count("SELECT seconds FROM player_playtime WHERE discord_id = '" + DISCORD_ID + "'"),
                "the second replaces it rather than adding to it");
    }

    private static void execute(final String sql) {
        try {
            executeChecked(sql);
        } catch (final SQLException exception) {
            throw new IllegalStateException("Test setup statement failed: " + sql, exception);
        }
    }

    /** Like {@link #execute(String)}, but hands the failure back so a constraint can be asserted on. */
    private static void executeChecked(final String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long count(final String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                var rs = statement.executeQuery(sql)) {
            assertTrue(rs.next(), "expected a row from: " + sql);
            return rs.getLong(1);
        } catch (final SQLException exception) {
            throw new IllegalStateException("Test query failed: " + sql, exception);
        }
    }

    private static void assertWithinSeconds(final Instant expected, final Instant actual, final long tolerance) {
        assertNotNull(actual, "expected a timestamp around " + expected + ", got null");
        final long off = Math.abs(Duration.between(expected, actual).toSeconds());
        assertTrue(
                off <= tolerance,
                "expected " + actual + " to be within " + tolerance + "s of " + expected + ", was off by " + off + "s");
    }
}
