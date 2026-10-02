package eu.nordtal.s2.stewardagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.s2.database.update.UpdateReport;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A backup refuses to save anything when a service it asked to stop is still running.
 *
 * The database dump's own line names no container, so it is not counted as a service that was asked.
 */
class StopVerificationTest {

    private static UpdateReport planned(final String... services) {
        UpdateReport report = UpdateReport.at(UpdateReport.Stage.STOPPING)
                .with(new UpdateReport.ServiceLine(Snapshots.DATABASE, UpdateReport.State.SAVED, List.of(), null));
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
    void stewardIsCountedLikeAnyOtherServiceSinceTheRunNoLongerRunsInsideIt() {
        assertEquals(List.of("steward"), Runner.servicesThatRefused(planned("steward"), Set.of()));
    }
}
