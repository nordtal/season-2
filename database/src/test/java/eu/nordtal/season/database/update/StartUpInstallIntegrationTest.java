package eu.nordtal.season.database.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.inbox.StewardRequest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** The install at start-up is written as a settled run, against PostgreSQL. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StartUpInstallIntegrationTest {

    private static DataSource dataSource;

    private UpdateDirectory updates;

    @BeforeAll
    static void startDatabase() {
        dataSource = TestDatabase.fresh().dataSource();
    }

    @BeforeEach
    void freshInbox() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement truncate = connection.createStatement()) {
            truncate.execute("TRUNCATE TABLE service_hold, steward_inbox RESTART IDENTITY CASCADE");
        }
        updates = UpdateDirectory.using(dataSource);
    }

    @Test
    void aStartUpInstallIsWrittenAlreadySettledWithItsReportAndHowLongItTook() {
        final String report = UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.DONE));

        final UpdateRequest written = updates.record(
                new StewardRequest.Install(List.of("smp", "limbo")),
                Actor.STEWARD,
                UpdateStatus.DONE,
                report,
                Duration.ofSeconds(90));

        assertEquals(UpdateKind.INSTALL, written.kind());
        assertEquals(UpdateStatus.DONE, written.status());
        assertEquals(Actor.STEWARD, written.actor());
        assertEquals(List.of("smp", "limbo"), updates.scopeOf(written.id()));
        assertEquals(UpdateReports.parse(report), UpdateReports.parse(written.result()));
        assertNotNull(written.started());
        assertNotNull(written.finished());
        assertEquals(
                90L,
                Duration.between(written.requested(), written.finished()).toSeconds(),
                "the card's duration is finished less requested");
        assertEquals(written.requested(), written.started());
        assertTrue(updates.open().isEmpty(), "nothing is left open for the run loop to claim");
    }

    @Test
    void aStartUpInstallIsWrittenBesideARunLeftOpen() {
        final UpdateRequest open = updates.submit(UpdateKind.UPDATE, Actor.HOST, Duration.ZERO);

        final UpdateRequest failed = updates.record(
                new StewardRequest.Install(List.of()),
                Actor.STEWARD,
                UpdateStatus.FAILED,
                UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)),
                Duration.ZERO);

        assertEquals(UpdateStatus.FAILED, failed.status());
        assertEquals(open.id(), updates.open().orElseThrow().id(), "the open run is still the one open");
    }
}
