package eu.nordtal.s2.common.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UpdateReport#withoutLines} - a service an update run deliberately left alone is not a line
 * in its report (season-2-ops/125).
 *
 * <p>A line is a promise that the run did something to that service. Leaving a held service in the
 * report as {@code PLANNED} would say the run was going to stop it, which is the opposite of what
 * happened; the run says it in a note instead.</p>
 */
class UpdateReportLinesTest {

    private static UpdateReport of(final String... services) {
        UpdateReport report = UpdateReport.at(UpdateReport.Stage.PLANNED);
        for (final String service : services) {
            report = report.with(new UpdateReport.ServiceLine(service, UpdateReport.State.PLANNED,
                    List.of(), null));
        }
        return report;
    }

    @Test
    @DisplayName("a held service leaves the report entirely")
    void theHeldLineIsGone() {
        final UpdateReport left = of("smp", "limbo", "proxy").withoutLines(List.of("limbo"));

        assertEquals(List.of("smp", "proxy"),
                left.services().stream().map(UpdateReport.ServiceLine::service).toList(),
                "the report still promises to stop a service the run is deliberately not touching");
    }

    @Test
    @DisplayName("the notes are not a casualty of dropping a line")
    void theNotesSurvive() {
        final UpdateReport left = of("smp", "limbo")
                .withNote("limbo is being held down")
                .withoutLines(List.of("limbo"));

        assertTrue(left.notes().contains("limbo is being held down"),
                "the sentence explaining why the line is missing was dropped with the line, so the"
                        + " report now silently does less than it says");
        assertEquals(UpdateReport.Stage.PLANNED, left.stage());
    }

    @Test
    @DisplayName("nothing held is the same report, not a copy of it")
    void emptyChangesNothing() {
        final UpdateReport report = of("smp", "limbo");
        assertSame(report, report.withoutLines(List.of()));
    }
}
