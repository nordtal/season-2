package eu.nordtal.s2.database.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.AccessSchema;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;
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
 * Exercises the command inbox against a real PostgreSQL running the real migrations.
 *
 * It proves {@code FakeRequests} agrees with the database's rules; it skips itself without Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommandRequestIntegrationTest {

    private static final String DISCORD_ID = "300000000000000001";
    private static final UUID MC_UUID = UUID.fromString("cccccccc-dddd-eeee-ffff-000000000000");

    private static PostgreSQLContainer<?> postgres;
    private static PGSimpleDataSource dataSource;

    private CommandRequests requests;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon reachable - skipping the command request tests");

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
    void freshTable() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE command_request");
        }
        requests = CommandRequests.borrowing(dataSource);
    }

    private NewCommandRequest request(
            final String target, final String command, final String arguments, final Instant expires) {
        return new NewCommandRequest(
                target,
                command,
                arguments,
                "DISCORD",
                "till",
                Optional.of(DiscordId.of(DISCORD_ID)),
                Optional.of(MC_UUID),
                "de",
                expires);
    }

    @Test
    void aRequestIsWrittenClaimedOnceAndAnsweredIntoTheSameRow() {
        final long id = requests.submit(
                request("SMP", "smp aura", MC_UUID + " -25", Instant.now().plusSeconds(30)));

        final CommandRequest claimed = requests.claim("SMP").orElseThrow();
        assertEquals(id, claimed.id());
        assertEquals("smp aura", claimed.command());
        assertEquals(MC_UUID + " -25", claimed.arguments());
        assertEquals("DISCORD", claimed.source());
        assertEquals(DiscordId.of(DISCORD_ID), claimed.discordId().orElseThrow());
        assertEquals(MC_UUID, claimed.minecraftId().orElseThrow());
        assertEquals(
                "de",
                claimed.locale(),
                "the asker's language rides on the row - looking it up on this side would make the"
                        + " reply's language depend on when it was claimed");

        requests.finish(id, true, "Aura geändert.");

        final CommandOutcome outcome = requests.outcome(id).orElseThrow();
        assertEquals(CommandOutcome.Status.DONE, outcome.status());
        assertEquals("Aura geändert.", outcome.result().orElseThrow());
    }

    @Test
    void aClaimedRequestCannotBeClaimedAgain() {
        requests.submit(request("SMP", "smp reload", "", Instant.now().plusSeconds(30)));

        assertTrue(requests.claim("SMP").isPresent());
        assertTrue(
                requests.claim("SMP").isEmpty(),
                "a second claim took the same row - two processes would run one command twice");
    }

    @Test
    void twoConnectionsClaimingAtOnceGetOneRowEachNeverTheSameOne() throws Exception {
        // A rolling restart briefly runs two of a backend, both draining the same inbox.
        requests.submit(request("SMP", "smp reload", "", Instant.now().plusSeconds(30)));
        requests.submit(request("SMP", "smp reload", "", Instant.now().plusSeconds(30)));

        final CommandRequests second = CommandRequests.borrowing(dataSource);
        final var first = requests.claim("SMP").orElseThrow();
        final var other = second.claim("SMP").orElseThrow();

        assertFalse(first.id() == other.id(), "both processes claimed the same request");
        assertTrue(requests.claim("SMP").isEmpty());
    }

    @Test
    void aRequestWhoseDeadlineHasPassedIsNeverClaimed() {
        requests.submit(request("SMP", "smp reload", "", Instant.now().minusSeconds(1)));

        assertTrue(
                requests.claim("SMP").isEmpty(),
                "the asker has stopped listening; running it anyway is how a correction is applied"
                        + " twice, once by the request they gave up on and once by the retype");
    }

    @Test
    void onlyTheTargetItIsAddressedToCanClaimIt() {
        requests.submit(request("HUNGER_GAMES", "hg start", "", Instant.now().plusSeconds(30)));

        assertTrue(requests.claim("SMP").isEmpty());
        assertTrue(requests.claim("HUNGER_GAMES").isPresent());
    }

    @Test
    void givingUpExpiresAPendingRowAndLosesToAClaimThatAlreadyHappened() {
        final long pending =
                requests.submit(request("SMP", "smp reload", "", Instant.now().plusSeconds(30)));
        assertTrue(requests.expire(pending));
        assertEquals(
                CommandOutcome.Status.EXPIRED,
                requests.outcome(pending).orElseThrow().status());

        final long claimed =
                requests.submit(request("SMP", "smp reload", "", Instant.now().plusSeconds(30)));
        requests.claim("SMP");

        assertFalse(
                requests.expire(claimed),
                "the asker cancelled work that was already running - a half-applied command is"
                        + " worse than a slow one");
        assertEquals(
                CommandOutcome.Status.RUNNING,
                requests.outcome(claimed).orElseThrow().status());
    }

    @Test
    void settlingARowTwiceWritesOnce() {
        final long id =
                requests.submit(request("SMP", "smp reload", "", Instant.now().plusSeconds(30)));
        requests.claim("SMP");

        requests.finish(id, true, "first");
        requests.finish(id, false, "second");

        final CommandOutcome outcome = requests.outcome(id).orElseThrow();
        assertEquals(CommandOutcome.Status.DONE, outcome.status());
        assertEquals("first", outcome.result().orElseThrow());
    }

    @Test
    void anUnsettledRowCarriesNoAnswerAndThatIsDistinguishableFromAnEmptyOne() {
        final long id =
                requests.submit(request("SMP", "smp reload", "", Instant.now().plusSeconds(30)));

        final CommandOutcome outcome = requests.outcome(id).orElseThrow();
        assertTrue(outcome.pending());
        assertTrue(outcome.result().isEmpty());
    }

    @Test
    void aRequestIdNobodyWroteIsEmptyRatherThanAnError() {
        assertTrue(requests.outcome(999_999L).isEmpty());
    }

    @Test
    void theConsoleCannotCarryAnIdentityAndTheDatabaseSaysSoToo() throws SQLException {
        // NewCommandRequest refuses this too; the CHECK also stops an adapter that writes raw SQL.
        refusedBy(
                "command_request_console_is_anonymous",
                () -> insertRaw("SMP", "smp reload", "CONSOLE", DiscordId.of(DISCORD_ID), null));
    }

    @Test
    void aRequestFromDiscordWithoutAnIdIsRefusedBecauseItCouldNotBeReChecked() throws SQLException {
        refusedBy("command_request_discord_knows_who", () -> insertRaw("SMP", "smp reload", "DISCORD", null, null));
    }

    @Test
    void anUnknownTargetIsRefusedByTheCheck() throws SQLException {
        refusedBy("command_request_target_check", () -> insertRaw("UPDATER", "update apply", "GAME", null, MC_UUID));
    }

    @Test
    void expiredIsExactlyTheStatusThatNeverStarted() throws SQLException {
        // A target that is down and one that is up and stuck are different problems.
        final long id =
                requests.submit(request("SMP", "smp reload", "", Instant.now().plusSeconds(30)));
        requests.claim("SMP");

        refusedBy(
                "command_request_expired_never_started",
                () -> update("UPDATE command_request SET status = 'EXPIRED', finished = now() WHERE id = " + id));
    }

    @Test
    void retentionDeletesSettledRowsPastTheWindowAndNeverAnUnsettledOne() throws SQLException {
        // Settled rows carry personal data and are swept after thirty days.
        final long old =
                requests.submit(request("SMP", "smp reload", "", Instant.now().plusSeconds(30)));
        requests.claim("SMP");
        requests.finish(old, true, "reloaded");
        // requested and started move too, or command_request_finished_after_started refuses the row.
        update("UPDATE command_request SET requested = now() - interval '31 days',"
                + " started = now() - interval '31 days',"
                + " finished = now() - interval '31 days' WHERE id = " + old);

        final long recent =
                requests.submit(request("SMP", "smp reload", "", Instant.now().plusSeconds(30)));
        requests.claim("SMP");
        requests.finish(recent, true, "reloaded");

        // An unclaimed row is never swept, however old: somebody may still be waiting on it.
        final long waiting = requests.submit(
                request("HUNGER_GAMES", "hg reload", "", Instant.now().plusSeconds(30)));
        update("UPDATE command_request SET requested = now() - interval '90 days' WHERE id = " + waiting);

        assertEquals(1, requests.deleteSettledOlderThan(30));
        assertTrue(requests.outcome(old).isEmpty(), "the old settled row is still there");
        assertTrue(requests.outcome(recent).isPresent(), "a row inside the window was deleted");
        assertTrue(
                requests.outcome(waiting).isPresent(),
                "a PENDING row was deleted, which is a command somebody may still be waiting on");
    }

    @Test
    void aWindowOfZeroDaysIsRefusedRatherThanEmptyingTheTable() {
        assertThrows(IllegalArgumentException.class, () -> requests.deleteSettledOlderThan(0));
    }

    /** Asserts that the row was refused by the named constraint, not by any {@code SQLException}. */
    private static void refusedBy(final String constraint, final org.junit.jupiter.api.function.Executable insert) {
        final SQLException refused = assertThrows(SQLException.class, insert);
        assertTrue(
                refused.getMessage().contains(constraint),
                "expected " + constraint + " to refuse the row, got: " + refused.getMessage());
    }

    private void insertRaw(
            final String target,
            final String command,
            final String source,
            final DiscordId discordId,
            final UUID mcUuid)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO command_request
                         (target, command, source, requested_by, discord_id, mc_uuid, expires)
                     VALUES (?, ?, ?, 'till', ?, ?, now() + '30 seconds')
                     """)) {
            statement.setString(1, target);
            statement.setString(2, command);
            statement.setString(3, source);
            statement.setString(4, discordId == null ? null : discordId.value());
            statement.setObject(5, mcUuid);
            statement.executeUpdate();
        }
    }

    private void update(final String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    @Test
    void thePartialIndexCanServeTheClaimColumnsAndPredicateBoth() throws SQLException {
        // Proves the index is usable for the claim with seqscan off, not that the planner picks it.
        for (int i = 0; i < 20; i++) {
            final long id = requests.submit(
                    request("SMP", "smp reload", "", Instant.now().plusSeconds(30)));
            requests.claim("SMP");
            requests.finish(id, true, "done");
        }
        requests.submit(request("SMP", "smp reload", "", Instant.now().plusSeconds(30)));

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("ANALYZE command_request");
            statement.execute("SET enable_seqscan = off");
            try (ResultSet plan = statement.executeQuery("""
                    EXPLAIN SELECT id FROM command_request
                    WHERE target = 'SMP' AND status = 'PENDING' AND expires > now()
                    ORDER BY id LIMIT 1
                    """)) {
                final StringBuilder text = new StringBuilder();
                while (plan.next()) {
                    text.append(plan.getString(1)).append('\n');
                }
                assertTrue(
                        text.toString().contains("command_request_pending"),
                        "the claim cannot use the partial index at all - its columns or its WHERE"
                                + " no longer match the query:\n" + text);
            }
        }
    }
}
