package eu.nordtal.s2.common.access;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises the stage C link-code lifecycle against a real PostgreSQL instance: issuing, the
 * "repeat attempt returns the same code" rule, expiry, redemption, and the 1:1 that redemption
 * enforces.
 * <p>
 * Same rationale as {@link AccessDirectoryIntegrationTest} for driving this against a real
 * database rather than in memory: the upsert-if-stale query, the unique constraints and the
 * expiry comparison are all evaluated by PostgreSQL. Testcontainers is started by hand for the
 * same JUnit-6-vs-{@code org.testcontainers:junit-jupiter} reason documented there, and this
 * class skips itself the same way when no Docker daemon is reachable.
 * </p>
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
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
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

    // ---------------------------------------------------------------- issuing

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

        assertEquals(first.code(), second.code(),
                "join-spam must not mint a fresh code every attempt");
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

        assertNotEquals(first.code(), refreshed.code(),
                "an expired code must not keep coming back - a stale code shown on screen would never work");
        assertTrue(refreshed.isValid());
    }

    @Test
    void issuingRejectsANonPositiveTtl() {
        assertThrows(IllegalArgumentException.class,
                () -> directory.issueLinkCode(MC_UUID, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> directory.issueLinkCode(MC_UUID, Duration.ofMinutes(-1)));
    }

    // ---------------------------------------------------------------- redeeming

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
        assertEquals(LinkRedemption.Status.INVALID_CODE, second.status(),
                "the code is gone after the first redemption, so a second attempt sees no code at all");
    }

    @Test
    void redeemingSomebodyElsesCodeStillLinksItToWhoeverTypesItIn() {
        // The code is not bound to a Discord account until it is redeemed - that is the whole
        // point of it being typed into Discord. "Somebody else's code" here means a code that was
        // never this Discord account's to begin with, and the entropy in LinkCodes is what is
        // supposed to make guessing it impractical; this test only proves the mechanics, not the
        // entropy budget.
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
        assertEquals(1, codeRowExists(code.code()),
                "a failed redemption must not burn the code - a wrong click deserves a retry");
        assertTrue(directory.linkedMinecraftAccount(DISCORD_ID).orElseThrow().equals(OTHER_MC_UUID),
                "the existing link must be untouched");
    }

    @Test
    void theRedeemedLinkIsOneToOneAndTheDatabaseIsWhatEnforcesIt() {
        final LinkCode code = directory.issueLinkCode(MC_UUID, Duration.ofMinutes(10));
        assertTrue(directory.redeemLinkCode(DISCORD_ID, code.code()).linked());

        // A fresh code for a second Minecraft account, redeemed by the same already-linked
        // Discord account.
        final LinkCode secondCode = directory.issueLinkCode(OTHER_MC_UUID, Duration.ofMinutes(10));
        final LinkRedemption result = directory.redeemLinkCode(DISCORD_ID, secondCode.code());

        assertFalse(result.linked());
        assertEquals(LinkRedemption.Status.ALREADY_LINKED, result.status());
        assertEquals(MC_UUID, directory.linkedMinecraftAccount(DISCORD_ID).orElseThrow(),
                "the first link must survive the second, rejected attempt");
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Pushes a code into the past far enough that {@code expires <= now()} while still satisfying
     * {@code link_code_expires_after_created} - {@code created} has to move back too, or "expires
     * a second ago" is earlier than a {@code created} that is only milliseconds old and the check
     * constraint refuses the write.
     */
    private static void expireCode(final UUID mcUuid) {
        execute("""
                UPDATE link_code
                SET created = now() - interval '1 hour', expires = now() - interval '1 second'
                WHERE mc_uuid = '%s'
                """.formatted(mcUuid));
    }

    private static long codeRowExists(final String code) {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT count(*) FROM link_code WHERE code = ?")) {
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
        assertTrue(off <= tolerance,
                "expected " + actual + " to be within " + tolerance + "s of " + expected + ", was off by " + off + "s");
    }
}
