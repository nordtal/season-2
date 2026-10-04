package eu.nordtal.s2.database.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.common.id.PlayerId;
import eu.nordtal.s2.database.Jdbis;
import eu.nordtal.s2.database.TestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Exercises what {@link AccessDirectory} keeps about a person against a real PostgreSQL: profiles, language, play time.
 *
 * Skips itself when no Docker daemon is reachable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccessProfileIntegrationTest {

    private static final String DISCORD_ID = "100000000000000001";
    private static final UUID MC_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static DataSource dataSource;

    private AccessDirectory directory;

    @BeforeAll
    static void startDatabase() {
        dataSource = TestDatabase.fresh().dataSource();
    }

    @BeforeEach
    void freshDirectory() {
        execute("TRUNCATE TABLE access_grant, account_link, link_code, payment_request, audit_log, "
                + "player_playtime, admin_grant, discord_user CASCADE");
        directory = AccessDirectory.using(dataSource, Clock.systemUTC());
    }

    @Test
    void discordProfileOfAnUnknownAccountIsEmptyNotNull() {
        assertEquals(DiscordProfile.EMPTY, directory.discordProfile(DiscordId.of("999999999999999999")));
    }

    @Test
    void minecraftProfileOfAnUnknownAccountIsEmptyNotNull() {
        assertEquals(MinecraftProfile.EMPTY, directory.minecraftProfile(DiscordId.of("999999999999999999")));
    }

    @Test
    void setDiscordProfileWritesAllThreeFieldsWithTheirOwnTimestamps() {
        directory.ensureUser(DiscordId.of(DISCORD_ID));

        directory.setDiscordProfile(DiscordId.of(DISCORD_ID), "steve", "Bau-Steve", "https://example.invalid/a.png");

        final DiscordProfile profile = directory.discordProfile(DiscordId.of(DISCORD_ID));
        assertEquals("steve", profile.username());
        assertEquals("Bau-Steve", profile.displayName());
        assertEquals("https://example.invalid/a.png", profile.avatarUrl());
        assertWithinSeconds(Instant.now(), profile.usernameUpdated(), 5);
        assertWithinSeconds(Instant.now(), profile.displayNameUpdated(), 5);
        assertWithinSeconds(Instant.now(), profile.avatarUrlUpdated(), 5);
    }

    @Test
    void setDiscordProfileCreatesTheUserRowIfItIsNotThereYet() {
        directory.setDiscordProfile(DiscordId.of("400000000000000002"), "new-user", null, null);

        assertEquals(
                "new-user",
                directory.discordProfile(DiscordId.of("400000000000000002")).username());
    }

    @Test
    void aMemberWithNoGuildNicknameOrAvatarHasNullThereNotAnEmptyString() {
        directory.setDiscordProfile(DiscordId.of(DISCORD_ID), "steve", null, null);

        final DiscordProfile profile = directory.discordProfile(DiscordId.of(DISCORD_ID));
        assertNull(profile.displayName());
        assertNull(profile.avatarUrl());
        // The timestamp says when the absence was last confirmed, not when a value last existed.
        assertNotNull(profile.displayNameUpdated());
        assertNotNull(profile.avatarUrlUpdated());
    }

    @Test
    void leavingTheGuildClearsTheNicknameAndTheAvatarButTheUsernameMerelyGoesStale() {
        directory.setDiscordProfile(DiscordId.of(DISCORD_ID), "steve", "Bau-Steve", "https://example.invalid/a.png");

        directory.clearGuildProfile(DiscordId.of(DISCORD_ID));

        final DiscordProfile profile = directory.discordProfile(DiscordId.of(DISCORD_ID));
        assertEquals("steve", profile.username(), "the global username is not guild-scoped");
        assertNull(profile.displayName(), "the guild nickname does not survive a departure");
        assertNull(profile.avatarUrl(), "neither does the guild avatar");
    }

    @Test
    void clearGuildProfileOfAnUnknownAccountDoesNothing() {
        directory.clearGuildProfile(DiscordId.of("999999999999999999"));

        assertEquals(DiscordProfile.EMPTY, directory.discordProfile(DiscordId.of("999999999999999999")));
    }

    @Test
    void setMinecraftNameWritesOntoTheLinkedAccount() {
        directory.link(DiscordId.of(DISCORD_ID), MC_UUID);

        final boolean written = directory.setMinecraftName(MC_UUID, "Notch");

        assertTrue(written);
        final MinecraftProfile profile = directory.minecraftProfile(DiscordId.of(DISCORD_ID));
        assertEquals("Notch", profile.name());
        assertWithinSeconds(Instant.now(), profile.nameUpdated(), 5);
    }

    @Test
    void aNameCannotBeCachedForAnAccountNobodyHasLinkedThereIsNoRowToWriteItOnto() {
        final boolean written = directory.setMinecraftName(UUID.randomUUID(), "Notch");

        assertFalse(written);
    }

    @Test
    void twoDiscordAccountsMayShareEveryObservedFieldWithoutBecomingOneIdentity() {
        // Two people may share a username or nickname; a UNIQUE constraint here would fail the INSERT below.
        final String otherDiscordId = "100000000000000099";
        final UUID otherMcUuid = UUID.randomUUID();
        directory.link(DiscordId.of(DISCORD_ID), MC_UUID);
        directory.link(DiscordId.of(otherDiscordId), otherMcUuid);

        directory.setDiscordProfile(DiscordId.of(DISCORD_ID), "steve", "Steve", "https://example.invalid/a.png");
        directory.setDiscordProfile(DiscordId.of(otherDiscordId), "steve2", "Steve", "https://example.invalid/a.png");
        directory.setMinecraftName(MC_UUID, "Herobrine");
        directory.setMinecraftName(otherMcUuid, "Herobrine");

        // Each account still resolves to its own Minecraft account: the lookup is keyed on discordId.
        assertEquals(
                MC_UUID,
                directory.linkedMinecraftAccount(DiscordId.of(DISCORD_ID)).orElseThrow());
        assertEquals(
                otherMcUuid,
                directory.linkedMinecraftAccount(DiscordId.of(otherDiscordId)).orElseThrow());
        assertEquals(
                DiscordId.of(DISCORD_ID),
                directory.linkedDiscordAccount(MC_UUID).orElseThrow());
        assertEquals(
                DiscordId.of(otherDiscordId),
                directory.linkedDiscordAccount(otherMcUuid).orElseThrow());

        final DiscordProfile first = directory.discordProfile(DiscordId.of(DISCORD_ID));
        final DiscordProfile second = directory.discordProfile(DiscordId.of(otherDiscordId));
        assertEquals("Steve", first.displayName());
        assertEquals("Steve", second.displayName());
        assertNotEquals(first, second, "identical display names must not make the two records equal");
    }

    @Test
    void anIdentityIsTheLinkTheNameTheLanguageTheZoneBothFlagsTheAuraAndThePlayTimeInOneRead() {
        directory.link(DiscordId.of(DISCORD_ID), MC_UUID);
        directory.setLocale(DiscordId.of(DISCORD_ID), Locale.GERMAN);
        Jdbis.over(dataSource).useTransaction(handle -> Grants.markDonor(handle, DiscordId.of(DISCORD_ID)));
        execute("UPDATE discord_user SET admin = true, admin_granted_at = now(), time_zone = 'America/New_York'"
                + " WHERE discord_id = '" + DISCORD_ID + "'");
        execute("UPDATE account_link SET mc_name = 'Steve' WHERE mc_uuid = '" + MC_UUID + "'");
        execute("INSERT INTO player_playtime (discord_id, seconds) VALUES ('" + DISCORD_ID + "', 3600)");
        execute("INSERT INTO smp_player (discord_id, aura) VALUES ('" + DISCORD_ID + "', 42)");

        assertEquals(
                List.of(new PlayerIdentity(
                        PlayerId.of(MC_UUID),
                        DiscordId.of(DISCORD_ID),
                        "Steve",
                        Locale.GERMAN,
                        ZoneId.of("America/New_York"),
                        true,
                        true,
                        42,
                        3600L)),
                directory.identities(List.of(PlayerId.of(MC_UUID))));
    }

    @Test
    void theLoginStateCarriesThePlayTimeTheProxysCardShows() {
        directory.link(DiscordId.of(DISCORD_ID), MC_UUID);
        assertEquals(0L, directory.accessState(MC_UUID).playtimeSeconds(), "nobody has played yet");

        execute("INSERT INTO player_playtime (discord_id, seconds) VALUES ('" + DISCORD_ID + "', 7200)");

        assertEquals(7200L, directory.accessState(MC_UUID).playtimeSeconds());
    }

    @Test
    void anAccountThatChoseNoLanguageReadsTheNetworksAndItsZoneIsTheNetworks() {
        directory.link(DiscordId.of(DISCORD_ID), MC_UUID);

        final PlayerIdentity identity =
                directory.identities(List.of(PlayerId.of(MC_UUID))).getFirst();

        assertEquals(Locale.ENGLISH, identity.language());
        assertEquals(ZoneId.of("Europe/Berlin"), identity.timeZoneOr(ZoneId.of("Europe/Berlin")));
    }

    @Test
    void aZoneThatIsNoZoneIsRefusedByTheSchema() {
        directory.link(DiscordId.of(DISCORD_ID), MC_UUID);

        final SQLException refused = assertThrows(
                SQLException.class,
                () -> executeChecked(
                        "UPDATE discord_user SET time_zone = 'not a zone' WHERE discord_id = '" + DISCORD_ID + "'"));
        assertTrue(refused.getMessage().contains("discord_user_time_zone_check"), refused.getMessage());
    }

    @Test
    void severalAccountsAreReadInOneCallAndAnAccountNobodyLinkedIsLeftOut() {
        directory.link(DiscordId.of(DISCORD_ID), MC_UUID);
        final PlayerId nobody = PlayerId.of(UUID.randomUUID());

        final List<PlayerIdentity> read = directory.identities(List.of(PlayerId.of(MC_UUID), nobody));

        assertEquals(
                List.of(PlayerId.of(MC_UUID)),
                read.stream().map(PlayerIdentity::player).toList());
        assertEquals(List.of(), directory.identities(List.of()));
    }

    @Test
    void playtimeHangsOffDiscordUserAndNotOffTheMinecraftUuid() throws SQLException {
        final SQLException orphan = assertThrows(
                SQLException.class,
                () -> executeChecked(
                        "INSERT INTO player_playtime (discord_id, seconds) VALUES ('999999999999999999', 60)"));
        assertTrue(orphan.getMessage().contains("player_playtime_discord_id_fkey"), orphan.getMessage());

        directory.ensureUser(DiscordId.of(DISCORD_ID));
        executeChecked("INSERT INTO player_playtime (discord_id, seconds) VALUES ('" + DISCORD_ID + "', 60)");
        assertEquals(1, count("SELECT count(*) FROM player_playtime WHERE seconds = 60"));
    }

    @Test
    void playtimeIsAnIntegerCountOfSecondsThatCannotGoBackwardsPastZero() {
        directory.ensureUser(DiscordId.of(DISCORD_ID));

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

    /** Checks that an admin may set play time outright, unlike the proxy's {@code add}. */
    @Test
    void playtimeCanBeSetOutright() {
        directory.ensureUser(DiscordId.of(DISCORD_ID));

        directory.setPlaytimeSeconds(DiscordId.of(DISCORD_ID), 32400);
        assertEquals(
                32400,
                count("SELECT seconds FROM player_playtime WHERE discord_id = '" + DISCORD_ID + "'"),
                "the first write makes the row");

        directory.setPlaytimeSeconds(DiscordId.of(DISCORD_ID), 60);
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
