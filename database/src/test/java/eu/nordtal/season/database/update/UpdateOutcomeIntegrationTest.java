package eu.nordtal.season.database.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.TestDatabase;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** How a claimed run settles: once, as DONE or FAILED, at the stage it ended in. */
class UpdateOutcomeIntegrationTest {

    private UpdateDirectory updates;

    @BeforeEach
    void freshDatabase() {
        updates = UpdateDirectory.using(TestDatabase.fresh().dataSource());
    }

    @Test
    void finishingWritesTheStageIntoTheSameRow() {
        final UpdateRequest submitted = updates.submit(UpdateKind.UPDATE, Actor.HOST, Duration.ZERO);
        assertTrue(updates.claimNext().isPresent());

        final UpdateRequest finished =
                updates.finish(submitted.id(), RunStage.NOTHING_TO_DO).orElseThrow();

        assertEquals(UpdateStatus.DONE, finished.status());
        assertEquals(RunStage.NOTHING_TO_DO, finished.stage());
        assertNotNull(finished.finished());
    }

    @Test
    void aFailedStageFailsTheRun() {
        final UpdateRequest submitted = updates.submit(UpdateKind.RESTART, Actor.HOST, Duration.ZERO);
        assertTrue(updates.claimNext().isPresent());

        assertEquals(
                UpdateStatus.FAILED,
                updates.finish(submitted.id(), RunStage.FAILED).orElseThrow().status());
    }

    @Test
    void onlyARunningRequestCanBeFinished() {
        final UpdateRequest submitted = updates.submit(UpdateKind.UPDATE, Actor.HOST, Duration.ZERO);

        assertTrue(
                updates.finish(submitted.id(), RunStage.DONE).isEmpty(),
                "it was never claimed, so there is nothing to finish");

        assertTrue(updates.claimNext().isPresent());
        assertTrue(updates.finish(submitted.id(), RunStage.DONE).isPresent());
        assertTrue(
                updates.finish(submitted.id(), RunStage.FAILED).isEmpty(),
                "and a finished run is not finished again by a second steward");
        assertEquals(RunStage.DONE, updates.find(submitted.id()).orElseThrow().stage());
    }

    @Test
    void aClaimedRequestFinishesOnlyAtAStageARunEndsIn() {
        final UpdateRequest submitted = updates.submit(UpdateKind.RESTART, Actor.HOST, Duration.ZERO);
        assertTrue(updates.claimNext().isPresent());

        // CANCELLED is a person withdrawing a run, so steward cannot end its own work as one.
        assertThrows(IllegalArgumentException.class, () -> updates.finish(submitted.id(), RunStage.CANCELLED));
        assertThrows(IllegalArgumentException.class, () -> updates.finish(submitted.id(), RunStage.STOPPING));
        assertEquals(
                UpdateStatus.RUNNING, updates.find(submitted.id()).orElseThrow().status());
    }
}
