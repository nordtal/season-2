package eu.nordtal.s2.steward.worker.serve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.steward.worker.backup.DatabaseDump;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A backup refuses to save anything when a service it asked to stop is still running.
 *
 * The question this answers is which lines of the report are services that were asked.
 *
 * It is not all of them. The database dump is taken first, with everything still up, and it writes a line of its own
 * - and that line named a thing no container is. Counting it made every backup abort before saving a single volume,
 * quietly, with a message blaming "database" for not stopping.
 */
class StopVerificationTest {

    private static UpdateReport planned(final String... services) {
        UpdateReport report = UpdateReport.at(UpdateReport.Stage.STOPPING)
                .with(new UpdateReport.ServiceLine(DatabaseDump.NAME, UpdateReport.State.SAVED, List.of(), null));
        for (final String service : services) {
            report = report.with(new UpdateReport.ServiceLine(service, UpdateReport.State.PLANNED, List.of(), null));
        }
        return report;
    }

    @Test
    void theDatabaseDumpIsNotAContainerAndNeverCountsAsOneThatRefusedToStop() {
        assertEquals(List.of(), Runner.servicesThatRefused(planned("smp", "limbo"), Set.of("smp", "limbo")));
    }

    @Test
    void aServiceThatReallyDidNotStopIsStillReported() {
        assertEquals(List.of("limbo"), Runner.servicesThatRefused(planned("smp", "limbo"), Set.of("smp")));
    }

    @Test
    void stewardWorkerIsNeverCountedItIsTheProcessAsking() {
        assertTrue(
                Runner.servicesThatRefused(planned("steward-worker"), Set.of()).isEmpty());
    }
}
