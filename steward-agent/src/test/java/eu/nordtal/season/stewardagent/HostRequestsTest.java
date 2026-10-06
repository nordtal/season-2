package eu.nordtal.season.stewardagent;

import static eu.nordtal.season.database.AdminTexts.TEXTS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.database.update.UpdateReports;
import eu.nordtal.season.database.update.UpdateStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The host's {@code status} tells a run that kept a local build from any other failure, so the script can ask. */
class HostRequestsTest {

    @Test
    void aRunThatStoppedBeforeReplacingALocalBuildIsTold() {
        final String stored = UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                .withNote(TEXTS.report().localBuildsKept(List.of("steward"), 1)));

        assertTrue(HostRequests.keptLocal(UpdateStatus.FAILED, stored));
    }

    @Test
    void anyOtherFailureOrNoReportIsNot() {
        final String other = UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                .withNote(TEXTS.report().olderRelease("0.16.0", "0.17.0")));

        assertFalse(HostRequests.keptLocal(UpdateStatus.FAILED, other));
        assertFalse(HostRequests.keptLocal(UpdateStatus.FAILED, null));
        assertFalse(HostRequests.keptLocal(UpdateStatus.DONE, other));
    }
}
