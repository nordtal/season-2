package eu.nordtal.season.stewardagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.database.update.UpdateReports;
import eu.nordtal.season.internalapi.agent.ImageResult;
import eu.nordtal.season.internalapi.agent.Topology;
import eu.nordtal.season.stewardagent.Told;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    @Test
    void anImageNobodyCouldCompareIsANoteNamingItsService() {
        final List<String> notes = notes(ImageResult.of(
                Map.of(Topology.SMP, ImageResult.State.UP_TO_DATE, "steward", ImageResult.State.UNKNOWN),
                Set.of("steward")));

        assertEquals(List.of("report.images-unverifiable"), notes);
    }

    @Test
    void aLocalBuildIsANoteNamingWhatTheNextRunReplaces() {
        final UpdateReport report = with(ImageResult.of(Map.of(
                Topology.SMP,
                ImageResult.State.UP_TO_DATE,
                "steward",
                ImageResult.State.LOCAL,
                "steward-agent",
                ImageResult.State.LOCAL)));

        assertEquals(List.of("report.images-local"), keys(report));
        assertEquals(
                List.of("Built on this host and never published: steward and steward-agent. The next real update run"
                        + " replaces them with whatever the last release actually contains, without asking."),
                Told.notes(report));
    }

    @Test
    void imagesNobodyCouldReadAreOneNoteCarryingTheRuntimesOwnAnswer() {
        final UpdateReport report = with(ImageResult.unreachable("no docker socket"));

        assertEquals(List.of("report.images-unread"), keys(report));
        assertTrue(
                Told.notes(report).getFirst().endsWith(": no docker socket"),
                Told.notes(report).toString());
    }

    @Test
    void aProjectWhereNothingWasComparedSaysSoRatherThanReadingAsCurrent() {
        assertEquals(List.of("report.images-uncompared"), notes(ImageResult.of(Map.of())));
    }

    @Test
    void aCheckedCurrentProjectHasNoNoteAboutItsImages() {
        assertEquals(List.of(), notes(ImageResult.of(Map.of(Topology.SMP, ImageResult.State.UP_TO_DATE))));
    }

    private static UpdateReport with(final ImageResult images) {
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        return ForeignImages.withImages(
                UpdateReport.at(UpdateReport.Stage.PLANNED), images, containers.topology(), List.of());
    }

    private static List<String> notes(final ImageResult images) {
        return keys(with(images));
    }

    private static List<String> keys(final UpdateReport report) {
        return report.notes().stream().map(note -> note.key()).toList();
    }
}
