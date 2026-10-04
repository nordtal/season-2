package eu.nordtal.s2.stewardagent.run;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.database.update.UpdateReport;
import eu.nordtal.s2.database.update.UpdateReports;
import eu.nordtal.s2.internalapi.agent.Topology;
import eu.nordtal.s2.stewardagent.Told;
import java.util.List;
import org.junit.jupiter.api.Test;

/** An image the registry has a newer one of is work, and the report tells it in a message, like every other line. */
class ForeignImagesTest {

    @Test
    void anOutdatedImageIsToldAsAMessageOfTheReportNotInEnglishWords() {
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP).imageOutdated(Topology.SMP);

        final UpdateReport report = ForeignImages.withImages(
                UpdateReport.at(UpdateReport.Stage.PLANNED), containers.images(), containers.topology(), List.of());

        assertTrue(report.line(Topology.SMP).isMoving(), "an outdated image is work, or the run never stops smp");
        final String stored = UpdateReports.toJson(report);
        assertTrue(stored.contains("\"key\":\"report.image-outdated\""), stored);
        assertFalse(
                stored.contains("out of date"),
                "the words belong to the bundle, where every surface renders them and an admin can change them: "
                        + stored);
        assertTrue(Told.report(stored).contains("out of date"), "the host's terminal still reads them in English");
    }
}
