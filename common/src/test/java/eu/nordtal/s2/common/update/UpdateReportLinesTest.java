package eu.nordtal.s2.common.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link UpdateReport#withoutLines}, which drops services a run deliberately left alone.
 *
 * A line promises the run did something to that service.
 */
class UpdateReportLinesTest {

    private static UpdateReport of(final String... services) {
        UpdateReport report = UpdateReport.at(UpdateReport.Stage.PLANNED);
        for (final String service : services) {
            report = report.with(new UpdateReport.ServiceLine(service, UpdateReport.State.PLANNED, List.of(), null));
        }
        return report;
    }

    @Test
    void aHeldServiceLeavesTheReportEntirely() {
        final UpdateReport left = of("smp", "limbo", "proxy").withoutLines(List.of("limbo"));

        assertEquals(
                List.of("smp", "proxy"),
                left.services().stream().map(UpdateReport.ServiceLine::service).toList(),
                "the report still promises to stop a service the run is deliberately not touching");
    }

    @Test
    void theNotesAreNotACasualtyOfDroppingALine() {
        final UpdateReport left =
                of("smp", "limbo").withNote("limbo is being held down").withoutLines(List.of("limbo"));

        assertTrue(
                left.notes().contains("limbo is being held down"),
                "the sentence explaining why the line is missing was dropped with the line, so the"
                        + " report now silently does less than it says");
        assertEquals(UpdateReport.Stage.PLANNED, left.stage());
    }

    @Test
    void nothingHeldIsTheSameReportNotACopyOfIt() {
        final UpdateReport report = of("smp", "limbo");
        assertSame(report, report.withoutLines(List.of()));
    }
}
