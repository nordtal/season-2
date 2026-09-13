package eu.nordtal.s2.steward.worker.serve;

import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.steward.worker.backup.DatabaseDump;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A backup refuses to save anything when a service it asked to stop is still running. The question
 * this answers is which lines of the report are services that were asked.
 *
 * <p>It is not all of them. The database dump is taken first, with everything still up, and it
 * writes a line of its own - and that line named a thing no container is. Counting it made every
 * backup abort before saving a single volume, quietly, with a message blaming "database" for not
 * stopping.</p>
 */
class StopVerificationTest {

    private static UpdateReport planned(final String... services) {
        UpdateReport report = UpdateReport.at(UpdateReport.Stage.STOPPING)
                .with(new UpdateReport.ServiceLine(DatabaseDump.NAME, UpdateReport.State.SAVED,
                        List.of(), null));
        for (final String service : services) {
            report = report.with(new UpdateReport.ServiceLine(service, UpdateReport.State.PLANNED,
                    List.of(), null));
        }
        return report;
    }

    @Test
    @DisplayName("the database dump is not a container and never counts as one that refused to stop")
    void theDatabaseLineIsNotAService() {
        assertEquals(List.of(),
                Runner.servicesThatRefused(planned("smp", "limbo"), Set.of("smp", "limbo")));
    }

    @Test
    @DisplayName("a service that really did not stop is still reported")
    void aServiceThatRefusedIsReported() {
        assertEquals(List.of("limbo"),
                Runner.servicesThatRefused(planned("smp", "limbo"), Set.of("smp")));
    }

    @Test
    @DisplayName("steward-worker is never counted: it is the process asking")
    void theWorkerIsNeverCounted() {
        assertTrue(Runner.servicesThatRefused(planned("steward-worker"), Set.of()).isEmpty());
    }
}
