package eu.nordtal.s2.common.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.nordtal.s2.common.access.AccessSchema;
import eu.nordtal.s2.common.audit.AuditLine;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.core.JdbiException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Tests that a command request and its journal line are written as one statement or not at all.
 *
 * Against a real PostgreSQL: the property is that no {@code command_request} row exists when the
 * journal line fails, and only the database's handling of the two CTEs decides it. A request left
 * behind would run while the operator is shown an error and presses the button again.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommandRequestJournalIntegrationTest {

    private static final String DISCORD_ID = "300000000000000042";

    /** The account on the <em>request</em>. It must never be mistaken for the journal line's. */
    private static final UUID REQUEST_MC = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    private static PostgreSQLContainer<?> postgres;
    private static PGSimpleDataSource dataSource;

    private CommandRequests requests;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon reachable - skipping the journalled submit tests");

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
    void freshTables() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE command_request");
            statement.execute("TRUNCATE audit_log");
        }
        requests = CommandRequests.borrowing(dataSource);
    }

    /** A request shaped like the one {@code CommandApi} writes: source WEB, with a Discord id. */
    private static NewCommandRequest webRequest() {
        return new NewCommandRequest(
                "SMP",
                "smp milestone unlock",
                "aufbruch",
                "WEB",
                "Till (300000000000000042)",
                Optional.of(DISCORD_ID),
                Optional.of(REQUEST_MC),
                "de",
                Instant.now().plusSeconds(120));
    }

    @Test
    void theRequestAndItsJournalLineAreBothThereWithTheJournalsOwnValues() throws SQLException {
        final long id = requests.submit(
                webRequest(),
                new AuditLine(
                        "COMMAND",
                        "Till (300000000000000042)",
                        "/smp milestone unlock",
                        null,
                        "from the web interface: aufbruch"));

        assertEquals(1, countOf("SELECT count(*) FROM command_request WHERE id = " + id));

        final List<String> journal = rowOf("""
                SELECT action, actor, subject, mc_uuid::text, detail FROM audit_log
                """);
        assertEquals("COMMAND", journal.get(0));
        assertEquals("Till (300000000000000042)", journal.get(1));
        assertEquals("/smp milestone unlock", journal.get(2));
        // The journal line's mc_uuid is its own; binding the request's here would misattribute every web command.
        assertNull(journal.get(3), "the journal line's mc_uuid is its own, and this one has none");
        assertEquals("from the web interface: aufbruch", journal.get(4));
    }

    @Test
    void theJournalLinesMinecraftAccountIsTheLinesNotTheRequests() throws SQLException {
        // The mirror case: a binding that read the request's field would write NULL here.
        final UUID subjectOfTheLine = UUID.fromString("11111111-2222-3333-4444-555555555555");
        requests.submit(
                new NewCommandRequest(
                        "SMP",
                        "smp aura",
                        "-25",
                        "WEB",
                        "Till",
                        Optional.of(DISCORD_ID),
                        Optional.empty(),
                        "de",
                        Instant.now().plusSeconds(120)),
                new AuditLine("COMMAND", "Till", "/smp aura", subjectOfTheLine, "aura corrected"));

        assertEquals(
                subjectOfTheLine.toString(),
                rowOf("SELECT mc_uuid::text FROM audit_log").getFirst());
    }

    @Test
    void aJournalLineTheColumnCannotHoldTakesTheCommandRequestDownWithIt() throws SQLException {
        // audit_log.action is varchar(32), so a 33-character action fails the journal CTE in the same statement.
        final String tooLong = "A".repeat(33);

        assertThrows(
                JdbiException.class,
                () -> requests.submit(webRequest(), new AuditLine(tooLong, "Till", "x", null, "y")));

        assertEquals(
                0,
                countOf("SELECT count(*) FROM command_request"),
                "a PENDING row survived a failed journal line - a target will claim and run it"
                        + " while the operator is being told nothing happened, and the operator"
                        + " will press the button again");
        assertEquals(0, countOf("SELECT count(*) FROM audit_log"));
    }

    @Test
    void theSequenceTheRequestIdComesFromIsTheOnlyThingARollbackLeavesBehind() throws SQLException {
        // A refused submit leaves only a gap in `id`, which is not to be read as a deleted row.
        assertThrows(
                JdbiException.class,
                () -> requests.submit(webRequest(), new AuditLine("A".repeat(33), "Till", "x", null, "y")));

        final long after = requests.submit(
                webRequest(), new AuditLine("COMMAND", "Till", "/smp milestone unlock", null, "second try"));

        assertEquals(1, countOf("SELECT count(*) FROM command_request"));
        assertEquals(1, countOf("SELECT count(*) FROM command_request WHERE id = " + after));
    }

    @Test
    void aJournalActorOfMoreThan32CharactersIsRefusedCommandAndAll() throws SQLException {
        // audit_log.actor is varchar(32), so a long display name plus a snowflake refuses the command too.
        requests.submit(webRequest(), new AuditLine("COMMAND", "T".repeat(32), "x", null, "y"));
        assertEquals(1, countOf("SELECT count(*) FROM audit_log"));

        assertThrows(
                JdbiException.class,
                () -> requests.submit(webRequest(), new AuditLine("COMMAND", "T".repeat(33), "x", null, "y")));
        assertEquals(
                1,
                countOf("SELECT count(*) FROM command_request"),
                "the second submit wrote a request row despite its journal line being impossible");
    }

    @Test
    void theJournalledSubmitStillWakesTheTargetOnNordtalCommandByName() throws Exception {
        // `notified` is a plain SELECT and only runs because the outer query joins it.
        try (Connection listener = dataSource.getConnection()) {
            try (Statement statement = listener.createStatement()) {
                statement.execute("LISTEN nordtal_command");
            }

            requests.submit(
                    new NewCommandRequest(
                            "HUNGER_GAMES",
                            "hg start",
                            "",
                            "WEB",
                            "Till",
                            Optional.of(DISCORD_ID),
                            Optional.empty(),
                            "de",
                            Instant.now().plusSeconds(120)),
                    new AuditLine("COMMAND", "Till", "/hg start", null, "from the web interface"));

            final PGNotification[] sent = awaitNotification(listener);
            assertTrue(
                    sent != null && sent.length > 0,
                    "nothing arrived on nordtal_command - the target waits for its poll instead");
            assertEquals("nordtal_command", sent[0].getName());
            assertEquals(
                    "HUNGER_GAMES",
                    sent[0].getParameter(),
                    "the payload is the target's name, which is how three inboxes on one channel"
                            + " each decide whether the signal was about them");
        }
    }

    @Test
    void noNotificationEscapesASubmitThatWasRolledBack() throws Exception {
        // No notification for a refused write: it would send every inbox to the database for nothing.
        try (Connection listener = dataSource.getConnection()) {
            try (Statement statement = listener.createStatement()) {
                statement.execute("LISTEN nordtal_command");
            }

            assertThrows(
                    JdbiException.class,
                    () -> requests.submit(webRequest(), new AuditLine("A".repeat(33), "Till", "x", null, "y")));

            final PGConnection pg = listener.unwrap(PGConnection.class);
            final PGNotification[] sent = pg.getNotifications(500);
            assertTrue(sent == null || sent.length == 0, "a notification was delivered for a row that never committed");
        }
    }

    @Test
    void thePlainSubmitWritesNoJournalLineAndThatIsTheDistinctionBetweenThem() throws SQLException {
        // The unjournalled path must stay unjournalled, or audit_log fills with server announcements.
        requests.submit(webRequest());

        assertEquals(1, countOf("SELECT count(*) FROM command_request"));
        assertEquals(0, countOf("SELECT count(*) FROM audit_log"), "the un-journalled submit wrote a journal line");
    }

    @Test
    void everySourceNewcommandrequestAcceptsIsOneTheCheckAcceptsAndNoOther() throws SQLException {
        // V18's CHECK and NewCommandRequest's list must agree; SYSTEM is deliberately not a source.
        for (final String source : List.of("DISCORD", "GAME", "CONSOLE", "WEB")) {
            final boolean needsAnId = "DISCORD".equals(source) || "WEB".equals(source);
            insertRaw(source, needsAnId ? DISCORD_ID : null);
        }
        assertEquals(4, countOf("SELECT count(*) FROM command_request"));

        for (final String refused : List.of("SYSTEM", "web", "Discord", "API", "")) {
            final SQLException failure = assertThrows(
                    SQLException.class,
                    () -> insertRaw(refused, DISCORD_ID),
                    refused + " reached the table - the CHECK does not pin the four");
            assertTrue(
                    failure.getMessage().contains("command_request_source_check"),
                    "expected command_request_source_check to refuse " + refused + ", got: " + failure.getMessage());

            assertThrows(
                    IllegalArgumentException.class,
                    () -> new NewCommandRequest(
                            "SMP",
                            "smp reload",
                            "",
                            refused,
                            "till",
                            Optional.of(DISCORD_ID),
                            Optional.empty(),
                            "de",
                            Instant.now().plusSeconds(30)),
                    refused + " was accepted by NewCommandRequest but refused by the database, so"
                            + " the operator's error message names a constraint rather than the"
                            + " adapter that built the row");
        }
        assertEquals(4, countOf("SELECT count(*) FROM command_request"));
    }

    @Test
    void aWebRowWithoutADiscordIdIsRefusedBecauseNothingCouldReCheckIt() {
        // A WEB row needs an identity, because CommandInbox re-authorises the row against it when claiming.
        final SQLException refused = assertThrows(SQLException.class, () -> insertRaw("WEB", null));
        assertTrue(refused.getMessage().contains("command_request_web_knows_who"), refused.getMessage());

        assertThrows(
                IllegalArgumentException.class,
                () -> new NewCommandRequest(
                        "SMP",
                        "smp reload",
                        "",
                        "WEB",
                        "till",
                        Optional.empty(),
                        Optional.empty(),
                        "de",
                        Instant.now().plusSeconds(30)),
                "the record let a WEB request through without an id");
    }

    private static PGNotification[] awaitNotification(final Connection listener) throws SQLException {
        final PGConnection pg = listener.unwrap(PGConnection.class);
        final Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            final PGNotification[] notifications = pg.getNotifications(500);
            if (notifications != null && notifications.length > 0) {
                return notifications;
            }
        }
        return null;
    }

    private void insertRaw(final String source, final String discordId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO command_request
                         (target, command, source, requested_by, discord_id, expires)
                     VALUES ('SMP', 'smp reload', ?, 'till', ?, now() + '30 seconds')
                     """)) {
            statement.setString(1, source);
            statement.setString(2, discordId);
            statement.executeUpdate();
        }
    }

    private long countOf(final String sql) throws SQLException {
        return Long.parseLong(rowOf(sql).getFirst());
    }

    /** One row as text, nulls kept as nulls - which is half of what these tests assert. */
    private List<String> rowOf(final String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next(), "no row for: " + sql);
            final List<String> values = new ArrayList<>();
            for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
                values.add(rows.getString(column));
            }
            assertFalse(rows.next(), "more than one row for: " + sql);
            return values;
        }
    }
}
