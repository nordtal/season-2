package eu.nordtal.s2.common.command;

import eu.nordtal.s2.common.access.AccessSchema;
import eu.nordtal.s2.common.audit.AuditLine;

import org.jdbi.v3.core.JdbiException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link CommandRequests#submit(NewCommandRequest, AuditLine)} - the request and its journal line
 * as one indivisible write, against a real PostgreSQL running the real migrations.
 *
 * <h2>Why this is a class of its own and why none of it can be faked</h2>
 * The property under test is a negative one - that <em>no</em> {@code command_request} row exists
 * when the journal line could not be written - and there is exactly one thing in this system that
 * decides it: PostgreSQL, applying the two data-modifying CTEs of {@code submitJournalled} in one
 * statement. A fake that implements the interface can only demonstrate that somebody wrote the fake
 * to be atomic. {@code CommandRequestIntegrationTest} beside this one owns the table's own
 * behaviour and truncates only {@code command_request}; these tests have to see {@code audit_log}
 * as well, so they clear both.
 *
 * <h2>The bug this exists to make impossible</h2>
 * Before 2026-09-14 {@code steward-ui} wrote the request and then, separately, the journal line. A
 * failure of the second left an executable {@code PENDING} row in the table while the operator's
 * browser was shown an error - and the next thing an operator does when told a command did not go
 * through is press the button again. Two unlocked milestones, one of them in nobody's name.
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
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
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
        return new NewCommandRequest("SMP", "smp milestone unlock", "aufbruch", "WEB",
                "Till (300000000000000042)", Optional.of(DISCORD_ID), Optional.of(REQUEST_MC),
                "de", Instant.now().plusSeconds(120));
    }

    // -------------------------------------------------------------------------------------------
    // Both rows, and the right values in the second one
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the request and its journal line are both there, with the journal's own values")
    void bothRowsLand() throws SQLException {
        final long id = requests.submit(webRequest(), new AuditLine("COMMAND",
                "Till (300000000000000042)", "/smp milestone unlock", null,
                "from the web interface: aufbruch"));

        assertEquals(1, countOf("SELECT count(*) FROM command_request WHERE id = " + id));

        final List<String> journal = rowOf("""
                SELECT action, actor, subject, mc_uuid::text, detail FROM audit_log
                """);
        assertEquals("COMMAND", journal.get(0));
        assertEquals("Till (300000000000000042)", journal.get(1));
        assertEquals("/smp milestone unlock", journal.get(2));
        // THE SHARP ONE. The request carries a Minecraft account and the line does not, and the two
        // uuid-typed values sit next to each other in the binding list of one statement. Binding
        // the request's `mc_uuid` into the journal's would produce a journal that quietly claims
        // every web command was "about" the admin who pressed the button - a sentence somebody
        // reads later and believes. Asserting null here is asserting that the two are not confused.
        assertNull(journal.get(3), "the journal line's mc_uuid is its own, and this one has none");
        assertEquals("from the web interface: aufbruch", journal.get(4));
    }

    @Test
    @DisplayName("the journal line's Minecraft account is the line's, not the request's")
    void theTwoMinecraftAccountsAreNotTheSameField() throws SQLException {
        // The mirror of the assertion above, from the other side: a request with no account at all
        // and a line that has one. A binding that read the request's field would write NULL here,
        // and a test that only ever checked the null case would never notice.
        final UUID subjectOfTheLine = UUID.fromString("11111111-2222-3333-4444-555555555555");
        requests.submit(new NewCommandRequest("SMP", "smp aura", "-25", "WEB", "Till",
                        Optional.of(DISCORD_ID), Optional.empty(), "de",
                        Instant.now().plusSeconds(120)),
                new AuditLine("COMMAND", "Till", "/smp aura", subjectOfTheLine, "aura corrected"));

        assertEquals(subjectOfTheLine.toString(),
                rowOf("SELECT mc_uuid::text FROM audit_log").getFirst());
    }

    // -------------------------------------------------------------------------------------------
    // Atomicity
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a journal line the column cannot hold takes the command request down with it")
    void nothingIsWrittenWhenTheJournalLineCannotBe() throws SQLException {
        // HOW THIS FORCES THE FAILURE WITHOUT TOUCHING PRODUCTION CODE. `audit_log.action` is
        // varchar(32) in V1. A 33-character action is a value the column cannot hold, so the
        // journalled CTE raises inside the same statement as the insert into command_request -
        // which is precisely the shape of failure this method exists to survive. Anything that
        // makes only the second write fail would do; this one needs no schema surgery and cannot
        // leave the database in a state the next test inherits.
        final String tooLong = "A".repeat(33);

        assertThrows(JdbiException.class,
                () -> requests.submit(webRequest(), new AuditLine(tooLong, "Till", "x", null, "y")));

        assertEquals(0, countOf("SELECT count(*) FROM command_request"),
                "a PENDING row survived a failed journal line - a target will claim and run it"
                        + " while the operator is being told nothing happened, and the operator"
                        + " will press the button again");
        assertEquals(0, countOf("SELECT count(*) FROM audit_log"));
    }

    @Test
    @DisplayName("the sequence the request id comes from is the only thing a rollback leaves behind")
    void aRolledBackSubmitCostsAnIdAndNothingElse() throws SQLException {
        // Not decoration: a reader of the table who sees a gap in `id` after an incident should be
        // able to tell "a submit was rolled back" from "a row was deleted", and the retention sweep
        // deletes rows. This pins that a refused submit is invisible in the table, gap aside, so
        // the gap is the only evidence and is not to be read as a missing row.
        assertThrows(JdbiException.class, () -> requests.submit(webRequest(),
                new AuditLine("A".repeat(33), "Till", "x", null, "y")));

        final long after = requests.submit(webRequest(),
                new AuditLine("COMMAND", "Till", "/smp milestone unlock", null, "second try"));

        assertEquals(1, countOf("SELECT count(*) FROM command_request"));
        assertEquals(1, countOf("SELECT count(*) FROM command_request WHERE id = " + after));
    }

    @Test
    @DisplayName("a journal actor of more than 32 characters is refused, command and all")
    void theActorColumnIsThirtyTwoCharactersWide() throws SQLException {
        // WHY THIS BOUNDARY IS WRITTEN DOWN RATHER THAN ASSUMED. `audit_log.actor` is varchar(32),
        // and `CommandApi.ask` builds its actor as `name + " (" + discordId + ")"`. A Discord
        // snowflake is 17 to 19 digits, so the parentheses and the id alone are 20 to 22
        // characters: a display name of twelve characters or more does not fit, and - now that the
        // two writes are one statement - does not merely lose its journal line, it loses the
        // command. See the report that came with this test; nothing here is a fix.
        requests.submit(webRequest(), new AuditLine("COMMAND", "T".repeat(32), "x", null, "y"));
        assertEquals(1, countOf("SELECT count(*) FROM audit_log"));

        assertThrows(JdbiException.class, () -> requests.submit(webRequest(),
                new AuditLine("COMMAND", "T".repeat(33), "x", null, "y")));
        assertEquals(1, countOf("SELECT count(*) FROM command_request"),
                "the second submit wrote a request row despite its journal line being impossible");
    }

    // -------------------------------------------------------------------------------------------
    // The wake-up
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the journalled submit still wakes the target on nordtal_command, by name")
    void theNotificationSurvivesTheExtraCte() throws Exception {
        // `notified` is a plain SELECT, and PostgreSQL is under no obligation to run one of those
        // if nothing reads its output - it is joined into the outer query for exactly that reason.
        // Adding a third CTE between the insert and the select is the kind of edit that can quietly
        // drop the join, and a target that is never woken only looks slow: the poll picks the row
        // up on its next tick, seconds later, on a surface where somebody is watching a spinner.
        try (Connection listener = dataSource.getConnection()) {
            try (Statement statement = listener.createStatement()) {
                statement.execute("LISTEN nordtal_command");
            }

            requests.submit(new NewCommandRequest("HUNGER_GAMES", "hg start", "", "WEB", "Till",
                            Optional.of(DISCORD_ID), Optional.empty(), "de",
                            Instant.now().plusSeconds(120)),
                    new AuditLine("COMMAND", "Till", "/hg start", null, "from the web interface"));

            final PGNotification[] sent = awaitNotification(listener);
            assertTrue(sent != null && sent.length > 0,
                    "nothing arrived on nordtal_command - the target waits for its poll instead");
            assertEquals("nordtal_command", sent[0].getName());
            assertEquals("HUNGER_GAMES", sent[0].getParameter(),
                    "the payload is the target's name, which is how three inboxes on one channel"
                            + " each decide whether the signal was about them");
        }
    }

    @Test
    @DisplayName("no notification escapes a submit that was rolled back")
    void aRefusedSubmitWakesNobody() throws Exception {
        // The whole reason every pg_notify in this repository rides inside the statement that
        // writes the row. A notification emitted for work that does not exist sends every inbox to
        // the database for nothing - harmless once, and a loop if the failure is the reproducible
        // kind.
        try (Connection listener = dataSource.getConnection()) {
            try (Statement statement = listener.createStatement()) {
                statement.execute("LISTEN nordtal_command");
            }

            assertThrows(JdbiException.class, () -> requests.submit(webRequest(),
                    new AuditLine("A".repeat(33), "Till", "x", null, "y")));

            final PGConnection pg = listener.unwrap(PGConnection.class);
            final PGNotification[] sent = pg.getNotifications(500);
            assertTrue(sent == null || sent.length == 0,
                    "a notification was delivered for a row that never committed");
        }
    }

    // -------------------------------------------------------------------------------------------
    // The two submits are not the same submit
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the plain submit writes no journal line, and that is the distinction between them")
    void thePlainSubmitIsStillJournalless() throws SQLException {
        // The SMP's announcements and the Discord bot's own commands go through this one. If it
        // ever started journalling, `audit_log` would gain a line per milestone announcement in
        // every language - and the "letzte Aktionen" tile, which is a list of what admins did,
        // would be a log of what the server did instead.
        requests.submit(webRequest());

        assertEquals(1, countOf("SELECT count(*) FROM command_request"));
        assertEquals(0, countOf("SELECT count(*) FROM audit_log"),
                "the un-journalled submit wrote a journal line");
    }

    // -------------------------------------------------------------------------------------------
    // B - the source boundary, from both ends at once
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("every source NewCommandRequest accepts is one the CHECK accepts, and no other")
    void theTwoEndsAgreeOnWhatASourceIs() throws SQLException {
        // CodeRabbit [02]: V18's CHECK and NewCommandRequest's own list are two copies of one
        // decision, in two languages, in two files that are edited by different reasons. A value
        // that only one of them allows is either a row nothing can write (harmless, and a CHECK
        // that has stopped checking) or a row that reaches the database and is refused there -
        // where the message names a constraint instead of naming the adapter that built it.
        //
        // SYSTEM is the one that will be proposed: it is a Surface, it reads like a source, and
        // V18 says in as many words that it is deliberately not one. `announce` travels as CONSOLE.
        for (final String source : List.of("DISCORD", "GAME", "CONSOLE", "WEB")) {
            final boolean needsAnId = "DISCORD".equals(source) || "WEB".equals(source);
            insertRaw(source, needsAnId ? DISCORD_ID : null);
        }
        assertEquals(4, countOf("SELECT count(*) FROM command_request"));

        for (final String refused : List.of("SYSTEM", "web", "Discord", "API", "")) {
            final SQLException failure = assertThrows(SQLException.class,
                    () -> insertRaw(refused, DISCORD_ID),
                    refused + " reached the table - the CHECK no longer pins the four");
            assertTrue(failure.getMessage().contains("command_request_source_check"),
                    "expected command_request_source_check to refuse " + refused + ", got: "
                            + failure.getMessage());

            assertThrows(IllegalArgumentException.class, () -> new NewCommandRequest("SMP",
                            "smp reload", "", refused, "till", Optional.of(DISCORD_ID),
                            Optional.empty(), "de", Instant.now().plusSeconds(30)),
                    refused + " was accepted by NewCommandRequest but refused by the database, so"
                            + " the operator's error message names a constraint rather than the"
                            + " adapter that built the row");
        }
        assertEquals(4, countOf("SELECT count(*) FROM command_request"));
    }

    @Test
    @DisplayName("a WEB row without a Discord id is refused, because nothing could re-check it")
    void theWebRowAlwaysKnowsWho() {
        // V18's second constraint. It is the same rule DISCORD has and it is load-bearing in the
        // same way: CommandInbox re-reads discord_user.admin against the identity on the row when
        // it claims it, and a row with no identity at all cannot be re-authorised. AdminCheck#of
        // fails such a row closed - so without this CHECK a WEB row with no id would not run
        // unauthorised, it would simply never run, which is the quieter half of the same bug.
        final SQLException refused = assertThrows(SQLException.class, () -> insertRaw("WEB", null));
        assertTrue(refused.getMessage().contains("command_request_web_knows_who"),
                refused.getMessage());

        assertThrows(IllegalArgumentException.class,
                () -> new NewCommandRequest("SMP", "smp reload", "", "WEB", "till",
                        Optional.empty(), Optional.empty(), "de", Instant.now().plusSeconds(30)),
                "the record let a WEB request through without an id");
    }

    // -------------------------------------------------------------------------------------------

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
