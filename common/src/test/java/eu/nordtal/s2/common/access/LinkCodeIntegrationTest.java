package eu.nordtal.s2.common.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
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
 * Exercises the link-code lifecycle against a real PostgreSQL: issue, repeat, expiry, redemption, 1:1.
 *
 * Skips itself when no Docker daemon is reachable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LinkCodeIntegrationTest {

    private static final String DISCORD_ID = "200000000000000001";
    private static final String OTHER_DISCORD_ID = "200000000000000002";
    private static final UUID MC_UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID OTHER_MC_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static PostgreSQLContainer<?> postgres;
    private static PGSimpleDataSource dataSource;

    private AccessDirectory directory;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon reachable - skipping the PostgreSQL-backed link code tests");

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
                + "player_playtime, discord_user CASCADE");
        directory = AccessDirectory.using(dataSource);
    }

    @Test
    void issuingForAFreshUuidMintsANewCode() {
        final LinkCode code = directory.issueLinkCode(MC_UUID, Duration.ofMinutes(10));

        assertEquals(MC_UUID, code.mcUuid());
        assertTrue(code.isValid());
        assertWithinSeconds(Instant.now().plus(Duration.ofMinutes(10)), code.expires(), 5);
    }

    @Test
    void aRepeatedAttemptReturnsTheSameCodeRatherThanMintingANewOne() {
        final LinkCode first = directory.issueLinkCode(MC_UUID, Duration.ofMinutes(10));
        final LinkCode second = directory.issueLinkCode(MC_UUID, Duration.ofMinutes(10));

        assertEquals(first.code(), second.code(), "join-spam must not mint a fresh code every attempt");
    }

    @Test
    void twoDifferentAccountsGetTwoDifferentCodes() {
        final LinkCode first = directory.issueLinkCode(MC_UUID, Duration.ofMinutes(10));
        final LinkCode second = directory.issueLinkCode(OTHER_MC_UUID, Duration.ofMinutes(10));

        assertNotEquals(first.code(), second.code());
    }

    @Test
    void anExpiredCodeIsReplacedByAFreshOneRatherThanReturnedAsIs() {
        final LinkCode first = directory.issueLinkCode(MC_UUID, Duration.ofMinutes(10));
        expireCode(MC_UUID);

        final LinkCode refreshed = directory.issueLinkCode(MC_UUID, Duration.ofMinutes(10));

        assertNotEquals(
                first.code(),
                refreshed.code(),
                "an expired code must not keep coming back - a stale code shown on screen would never work");
        assertTrue(refreshed.isValid());
    }

    @Test
    void issuingRejectsANonPositiveTtl() {
        assertThrows(IllegalArgumentException.class, () -> directory.issueLinkCode(MC_UUID, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> directory.issueLinkCode(MC_UUID, Duration.ofMinutes(-1)));
    }

    @Test
    void redeemingAValidCodeLinksTheAccountAndDeletesTheCode() {
        final LinkCode code = directory.issueLinkCode(MC_UUID, Duration.ofMinutes(10));

        final LinkRedemption result = directory.redeemLinkCode(DISCORD_ID, code.code());

        assertTrue(result.linked());
        assertEquals(MC_UUID, result.mcUuid());
        assertEquals(MC_UUID, directory.linkedMinecraftAccount(DISCORD_ID).orElseThrow());
        assertTrue(codeRowExists(code.code()) == 0, "the code must be gone once it is redeemed");
    }

    @Test
    void redeemingTheSameCodeTwiceFailsTheSecondTime() {
        final LinkCode code = directory.issueLinkCode(MC_UUID, Duration.ofMinutes(10));
        assertTrue(directory.redeemLinkCode(DISCORD_ID, code.code()).linked());

        final LinkRedemption second = directory.redeemLinkCode(OTHER_DISCORD_ID, code.code());

        assertFalse(second.linked());
        assertEquals(
                LinkRedemption.Status.INVALID_CODE,
                second.status(),
                "the code is gone after the first redemption, so a second attempt sees no code at all");
    }

    @Test
    void redeemingSomebodyElsesCodeStillLinksItToWhoeverTypesItIn() {
        // A code is bound to no Discord account until redeemed; this proves the mechanics, not the entropy.
        final LinkCode code = directory.issueLinkCode(MC_UUID, Duration.ofMinutes(10));

        final LinkRedemption result = directory.redeemLinkCode(OTHER_DISCORD_ID, code.code());

        assertTrue(result.linked());
        assertEquals(OTHER_DISCORD_ID, directory.linkedDiscordAccount(MC_UUID).orElseThrow());
    }

    @Test
    void redeemingAnUnknownCodeFails() {
        final LinkRedemption result = directory.redeemLinkCode(DISCORD_ID, "NOSUCHCODE");

        assertFalse(result.linked());
        assertEquals(LinkRedemption.Status.INVALID_CODE, result.status());
    }

    @Test
    void redeemingAnExpiredCodeFails() {
        final LinkCode code = directory.issueLinkCode(MC_UUID, Duration.ofMinutes(10));
        expireCode(MC_UUID);

        final LinkRedemption result = directory.redeemLinkCode(DISCORD_ID, code.code());

        assertFalse(result.linked());
        assertEquals(LinkRedemption.Status.INVALID_CODE, result.status());
        assertTrue(directory.linkedMinecraftAccount(DISCORD_ID).isEmpty());
    }

    @Test
    void redeemingLeavesTheCodeInPlaceWhenTheDiscordAccountIsAlreadyLinked() {
        directory.link(DISCORD_ID, OTHER_MC_UUID);
        final LinkCode code = directory.issueLinkCode(MC_UUID, Duration.ofMinutes(10));

        final LinkRedemption result = directory.redeemLinkCode(DISCORD_ID, code.code());

        assertFalse(result.linked());
        assertEquals(LinkRedemption.Status.ALREADY_LINKED, result.status());
        assertEquals(
                1,
                codeRowExists(code.code()),
                "a failed redemption must not burn the code - a wrong click deserves a retry");
        assertTrue(
                directory.linkedMinecraftAccount(DISCORD_ID).orElseThrow().equals(OTHER_MC_UUID),
                "the existing link must be untouched");
    }

    @Test
    void theRedeemedLinkIsOneToOneAndTheDatabaseIsWhatEnforcesIt() {
        final LinkCode code = directory.issueLinkCode(MC_UUID, Duration.ofMinutes(10));
        assertTrue(directory.redeemLinkCode(DISCORD_ID, code.code()).linked());

        // A second Minecraft account's code, redeemed by the already-linked Discord account.
        final LinkCode secondCode = directory.issueLinkCode(OTHER_MC_UUID, Duration.ofMinutes(10));
        final LinkRedemption result = directory.redeemLinkCode(DISCORD_ID, secondCode.code());

        assertFalse(result.linked());
        assertEquals(LinkRedemption.Status.ALREADY_LINKED, result.status());
        assertEquals(
                MC_UUID,
                directory.linkedMinecraftAccount(DISCORD_ID).orElseThrow(),
                "the first link must survive the second, rejected attempt");
    }

    /** Moves a code and its {@code created} into the past, so the expiry check constraint still holds. */
    private static void expireCode(final UUID mcUuid) {
        execute("""
                UPDATE link_code
                SET created = now() - interval '1 hour', expires = now() - interval '1 second'
                WHERE mc_uuid = '%s'
                """.formatted(mcUuid));
    }

    private static long codeRowExists(final String code) {
        try (Connection connection = dataSource.getConnection();
                var statement = connection.prepareStatement("SELECT count(*) FROM link_code WHERE code = ?")) {
            statement.setString(1, code);
            try (var rs = statement.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        } catch (final SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void execute(final String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (final SQLException exception) {
            throw new IllegalStateException("Test setup statement failed: " + sql, exception);
        }
    }

    private static void assertWithinSeconds(final Instant expected, final Instant actual, final long tolerance) {
        final long off = Math.abs(Duration.between(expected, actual).toSeconds());
        assertTrue(
                off <= tolerance,
                "expected " + actual + " to be within " + tolerance + "s of " + expected + ", was off by " + off + "s");
    }
}
