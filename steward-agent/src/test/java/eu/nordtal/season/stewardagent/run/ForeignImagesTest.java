package eu.nordtal.season.stewardagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.internalapi.agent.ImageResult;
import eu.nordtal.season.internalapi.agent.Topology;
import eu.nordtal.season.stewardagent.plan.Work;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Tests that an image with a newer one in the registry is work, and what a run finds about the images. */
class ForeignImagesTest {

    @Test
    void anOutdatedImageIsPulledWork() {
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP).imageOutdated(Topology.SMP);

        final Work work = ForeignImages.withImages(none(), containers.images(), containers.topology(), List.of());

        assertEquals(
                List.of(new RunStep.Change(UpdateRun.IMAGE, null, null, RunStep.Image.PULLED)),
                work.of(Topology.SMP),
                "an outdated image is work, or the run never stops smp");
    }

    @Test
    void anImageNobodyCouldCompareIsAWarningAboutItsService() {
        final Work work = with(ImageResult.of(
                Map.of(Topology.SMP, ImageResult.State.UP_TO_DATE, "steward", ImageResult.State.UNKNOWN),
                Set.of("steward")));

        assertEquals(
                List.of(new Work.Finding("steward", RunStep.Outcome.WARNING, RunStep.Reason.UNVERIFIABLE)),
                work.findings());
    }

    @Test
    void aLocalBuildIsAWarningForEachService() {
        final Work work = with(ImageResult.of(Map.of(
                Topology.SMP,
                ImageResult.State.UP_TO_DATE,
                "steward",
                ImageResult.State.LOCAL,
                "steward-agent",
                ImageResult.State.LOCAL)));

        assertEquals(
                Set.of(
                        new Work.Finding("steward", RunStep.Outcome.WARNING, RunStep.Reason.LOCAL_BUILD),
                        new Work.Finding("steward-agent", RunStep.Outcome.WARNING, RunStep.Reason.LOCAL_BUILD)),
                Set.copyOf(work.findings()),
                "one finding per service, so each can be found by its name");
    }

    @Test
    void imagesNobodyCouldReadAreAWarningOfTheRun() {
        assertEquals(
                List.of(new Work.Finding(null, RunStep.Outcome.WARNING, RunStep.Reason.UNVERIFIABLE)),
                with(ImageResult.unreachable("no docker socket")).findings());
    }

    @Test
    void aProjectWhereNothingWasComparedSaysSoRatherThanReadingAsCurrent() {
        assertEquals(
                List.of(new Work.Finding(null, RunStep.Outcome.WARNING, RunStep.Reason.UNVERIFIABLE)),
                with(ImageResult.of(Map.of())).findings());
    }

    @Test
    void aCheckedCurrentProjectHasNoFindingAboutItsImages() {
        assertEquals(
                List.of(),
                with(ImageResult.of(Map.of(Topology.SMP, ImageResult.State.UP_TO_DATE)))
                        .findings());
    }

    private static Work none() {
        return new Work(Map.of(), List.of());
    }

    private static Work with(final ImageResult images) {
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        return ForeignImages.withImages(none(), images, containers.topology(), List.of());
    }
}
