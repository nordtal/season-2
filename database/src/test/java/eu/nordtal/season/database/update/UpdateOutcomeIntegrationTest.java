package eu.nordtal.season.database.update;

import static eu.nordtal.season.database.AdminTexts.TEXTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.TestDatabase;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** How a claimed run settles: once, as DONE or FAILED, and always with a report. */
class UpdateOutcomeIntegrationTest {

    private static final String DONE = UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.DONE));
    private static final String FAILED = UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED));

    private UpdateDirectory updates;

    @BeforeEach
    void freshDatabase() {
        updates = UpdateDirectory.using(TestDatabase.fresh().dataSource());
    }

    @Test
    void finishingWritesTheReportIntoTheSameRow() {
        final UpdateRequest submitted = updates.submit(UpdateKind.UPDATE, Actor.HOST, Duration.ZERO);
        assertTrue(updates.claimNext().isPresent());

        final UpdateReport report = UpdateReport.at(UpdateReport.Stage.DONE)
                .withNote(UpdateReport.Note.done(
                        UpdateReport.Step.INSTALL, TEXTS.report().words("smp  0.1.0 -> 0.2.0")));
        final UpdateRequest finished = updates.finish(submitted.id(), UpdateStatus.DONE, UpdateReports.toJson(report))
                .orElseThrow();

        assertEquals(UpdateStatus.DONE, finished.status());
        assertEquals(report, UpdateReports.parse(finished.result()).orElseThrow());
        assertNotNull(finished.finished());
        assertTrue(finished.status().isFinished());
    }

    @Test
    void onlyARunningRequestCanBeFinished() {
        final UpdateRequest submitted = updates.submit(UpdateKind.UPDATE, Actor.HOST, Duration.ZERO);

        assertTrue(
                updates.finish(submitted.id(), UpdateStatus.DONE, DONE).isEmpty(),
                "it was never claimed, so there is no answer to write");

        assertTrue(updates.claimNext().isPresent());
        assertTrue(updates.finish(submitted.id(), UpdateStatus.DONE, DONE).isPresent());
        assertTrue(
                updates.finish(submitted.id(), UpdateStatus.FAILED, FAILED).isEmpty(),
                "and an answer that is already there is not overwritten by a second steward");
    }

    @Test
    void aClaimedRequestCannotBeFinishedAsCancelled() {
        final UpdateRequest submitted = updates.submit(UpdateKind.RESTART, Actor.HOST, Duration.ZERO);
        assertTrue(updates.claimNext().isPresent());

        // CANCELLED is a person withdrawing a run, so steward cannot report its own work as one.
        assertThrows(
                IllegalArgumentException.class, () -> updates.finish(submitted.id(), UpdateStatus.CANCELLED, FAILED));
        assertThrows(
                IllegalArgumentException.class, () -> updates.finish(submitted.id(), UpdateStatus.RUNNING, FAILED));
    }

    @Test
    void anOutcomeIsAReportAndNeverABareSentence() {
        final UpdateRequest submitted = updates.submit(UpdateKind.RESTART, Actor.HOST, Duration.ZERO);
        assertTrue(updates.claimNext().isPresent());

        // A sentence in the column is a text no reader can put into another language.
        assertThrows(
                IllegalArgumentException.class,
                () -> updates.finish(submitted.id(), UpdateStatus.FAILED, "the download timed out"));
        assertThrows(IllegalArgumentException.class, () -> updates.progress(submitted.id(), "stopping smp"));
        assertEquals(
                UpdateStatus.RUNNING, updates.find(submitted.id()).orElseThrow().status());
    }
}
