package eu.nordtal.s2.common.command;

import eu.nordtal.s2.common.access.AccessSchema;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The command inbox, against a real PostgreSQL running the real migrations.
 *
 * <h2>Why this cannot be an in-memory test</h2>
 * Almost everything worth asserting here is evaluated by the database and by nothing else: the
 * atomic claim is one {@code UPDATE ... FOR UPDATE SKIP LOCKED} whose whole point is what happens
 * when two connections run it at once, the expiry boundary is {@code expires > now()} in the
 * server's clock, and the five constraints are the reason an adapter cannot write a row that means
 * nothing. {@code FakeRequests} in {@code :commands} enforces the same transitions so the unit tests
 * there are not testing against a more permissive world - this class is what proves the two agree.
 *
 * <p>It skips itself when no Docker daemon is reachable, like every other integration test here.</p>
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
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
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

    private NewCommandRequest request(final String target, final String command,
                                      final String arguments, final Instant expires) {
        return new NewCommandRequest(target, command, arguments, "DISCORD", "till",
                Optional.of(DISCORD_ID), Optional.of(MC_UUID), "de", expires);
    }

    @Test
    @DisplayName("a request is written, claimed once, and answered into the same row")
    void theWholeRoundTrip() {
        final long id = requests.submit(
                request("SMP", "smp aura", MC_UUID + " -25", Instant.now().plusSeconds(30)));

        final CommandRequest claimed = requests.claim("SMP").orElseThrow();
        assertEquals(id, claimed.id());
        assertEquals("smp aura", claimed.command());
        assertEquals(MC_UUID + " -25", claimed.arguments());
        assertEquals("DISCORD", claimed.source());
        assertEquals(DISCORD_ID, claimed.discordId().orElseThrow());
        assertEquals(MC_UUID, claimed.minecraftId().orElseThrow());
        assertEquals("de", claimed.locale(),
                "the asker's language rides on the row - looking it up on this side would make the"
                        + " reply's language depend on when it was claimed");

        requests.finish(id, true, "Aura geändert.");

        final CommandOutcome outcome = requests.outcome(id).orElseThrow();
        assertEquals(CommandOutcome.Status.DONE, outcome.status());
        assertEquals("Aura geändert.", outcome.result().orElseThrow());
    }

    @Test
    @DisplayName("a claimed request cannot be claimed again")
    void oneClaimPerRow() {
        requests.submit(request("SMP", "smp reload", "", Instant.now().plusSeconds(30)));

        assertTrue(requests.claim("SMP").isPresent());
        assertTrue(requests.claim("SMP").isEmpty(),
                "a second claim took the same row - two processes would run one command twice");
    }

    @Test
    @DisplayName("two connections claiming at once get one row each, never the same one")
    void concurrentClaimsDoNotCollide() throws Exception {
        // The case FOR UPDATE SKIP LOCKED exists for. It is not hypothetical: a rolling restart
        // briefly runs two of a backend, and both would be draining the same inbox.
        requests.submit(request("SMP", "smp reload", "", Instant.now().plusSeconds(30)));
        requests.submit(request("SMP", "smp reload", "", Instant.now().plusSeconds(30)));

        final CommandRequests second = CommandRequests.borrowing(dataSource);
        final var first = requests.claim("SMP").orElseThrow();
        final var other = second.claim("SMP").orElseThrow();

        assertFalse(first.id() == other.id(), "both processes claimed the same request");
        assertTrue(requests.claim("SMP").isEmpty());
    }

    @Test
    @DisplayName("a request whose deadline has passed is never claimed")
    void anExpiredRowIsInvisibleToTheTarget() {
        requests.submit(request("SMP", "smp reload", "", Instant.now().minusSeconds(1)));

        assertTrue(requests.claim("SMP").isEmpty(),
                "the asker has stopped listening; running it anyway is how a correction is applied"
                        + " twice, once by the request they gave up on and once by the retype");
    }

    @Test
    @DisplayName("only the target it is addressed to can claim it")
    void aRowBelongsToOneTarget() {
        requests.submit(request("HUNGER_GAMES", "hg start", "", Instant.now().plusSeconds(30)));

        assertTrue(requests.claim("SMP").isEmpty());
        assertTrue(requests.claim("HUNGER_GAMES").isPresent());
    }

    @Test
    @DisplayName("giving up expires a pending row and loses to a claim that already happened")
    void theExpiryRace() {
        final long pending = requests.submit(
                request("SMP", "smp reload", "", Instant.now().plusSeconds(30)));
        assertTrue(requests.expire(pending));
        assertEquals(CommandOutcome.Status.EXPIRED, requests.outcome(pending).orElseThrow().status());

        final long claimed = requests.submit(
                request("SMP", "smp reload", "", Instant.now().plusSeconds(30)));
        requests.claim("SMP");

        assertFalse(requests.expire(claimed),
                "the asker cancelled work that was already running - a half-applied command is"
                        + " worse than a slow one");
        assertEquals(CommandOutcome.Status.RUNNING,
                requests.outcome(claimed).orElseThrow().status());
    }

    @Test
    @DisplayName("settling a row twice writes once")
    void finishIsGuardedByItsOwnStatus() {
        final long id = requests.submit(
                request("SMP", "smp reload", "", Instant.now().plusSeconds(30)));
        requests.claim("SMP");

        requests.finish(id, true, "first");
        requests.finish(id, false, "second");

        final CommandOutcome outcome = requests.outcome(id).orElseThrow();
        assertEquals(CommandOutcome.Status.DONE, outcome.status());
        assertEquals("first", outcome.result().orElseThrow());
    }

    @Test
    @DisplayName("an unsettled row carries no answer, and that is distinguishable from an empty one")
    void pendingHasNoResult() {
        final long id = requests.submit(
                request("SMP", "smp reload", "", Instant.now().plusSeconds(30)));

        final CommandOutcome outcome = requests.outcome(id).orElseThrow();
        assertTrue(outcome.pending());
        assertTrue(outcome.result().isEmpty());
    }

    @Test
    @DisplayName("a request id nobody wrote is empty rather than an error")
    void anUnknownIdIsEmpty() {
        assertTrue(requests.outcome(999_999L).isEmpty());
    }

    @Test
    @DisplayName("the console cannot carry an identity, and the database says so too")
    void theConsoleIsAnonymousInTheSchema() throws SQLException {
        // NewCommandRequest refuses this as well, so the CHECK is the second line rather than the
        // first - which is what makes it worth asserting: a future adapter that builds its row with
        // raw SQL still cannot get past it.
        assertThrows(SQLException.class, () -> insertRaw("SMP", "smp reload", "CONSOLE",
                DISCORD_ID, null));
    }

    @Test
    @DisplayName("a request from Discord without an id is refused, because it could not be re-checked")
    void discordAlwaysKnowsWho() throws SQLException {
        assertThrows(SQLException.class,
                () -> insertRaw("SMP", "smp reload", "DISCORD", null, null));
    }

    @Test
    @DisplayName("an unknown target is refused by the CHECK")
    void theTargetIsPinned() throws SQLException {
        assertThrows(SQLException.class,
                () -> insertRaw("UPDATER", "update apply", "GAME", null, MC_UUID));
    }

    @Test
    @DisplayName("EXPIRED is exactly the status that never started")
    void expiredNeverRan() throws SQLException {
        // Which is what makes "nothing ever picked this up" a diagnosis rather than a guess: a
        // target that is down and one that is up and stuck are different problems.
        final long id = requests.submit(
                request("SMP", "smp reload", "", Instant.now().plusSeconds(30)));
        requests.claim("SMP");

        assertThrows(SQLException.class, () -> update(
                "UPDATE command_request SET status = 'EXPIRED', finished = now() WHERE id = " + id));
    }

    private void insertRaw(final String target, final String command, final String source,
                           final String discordId, final UUID mcUuid) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO command_request
                         (target, command, source, requested_by, discord_id, mc_uuid, expires)
                     VALUES (?, ?, ?, 'till', ?, ?, now() + '30 seconds')
                     """)) {
            statement.setString(1, target);
            statement.setString(2, command);
            statement.setString(3, source);
            statement.setString(4, discordId);
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
    @DisplayName("the partial index can serve the claim, columns and predicate both")
    void theClaimIsIndexed() throws SQLException {
        // WHAT THIS PROVES, AND WHAT IT DOES NOT. It asks PostgreSQL whether the index is USABLE
        // for the claim - with sequential scans disabled, an index whose columns or whose WHERE do
        // not cover the query cannot be chosen and the plan says so. It deliberately does not
        // assert that the planner picks it: on a table of a few hundred rows a sequential scan is
        // genuinely cheaper, and a test that filled the table until the planner changed its mind
        // would be asserting a cost model rather than a schema.
        //
        // The failure it is guarding is real and quiet: an index on (id) alone, or without the
        // partial WHERE, still exists and still makes every query correct. It only shows up as a
        // sequential scan over a season of history on every notification, by which point it is on
        // production.
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
                assertTrue(text.toString().contains("command_request_pending"),
                        "the claim cannot use the partial index at all - its columns or its WHERE"
                                + " no longer match the query:\n" + text);
            }
        }
    }
}
