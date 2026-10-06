package eu.nordtal.season.stewardagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.stewardagent.Told;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** An update removes the images nothing uses once everything it stopped is back, and says what that freed. */
class ImagePruneTest {

    private final FakeContainers containers = new FakeContainers().running("smp");
    private final List<UpdateReport> progress = new ArrayList<>();

    @Test
    void theImagesArePrunedOnlyOnceEverythingIsBackAndTheReportSaysWhatWent() {
        final UpdateRun steps = new UpdateRun(containers, new FakeSnapshots(), progress::add);
        final UpdateRun.Stopped stopped =
                new UpdateRun.Stopped(UpdateReport.at(UpdateReport.Stage.STOPPING), List.of(), containers.runtime());

        final Run.Done done = Kinds.pruningImages(containers, Run.Payload.NONE).carryOut(steps, stopped);

        assertEquals(List.of(), containers.calls, "nothing is pruned while the servers are down");

        final UpdateReport back = done.afterwards().apply(done.report());

        assertEquals(List.of("prune-images"), containers.calls);
        final String english = Told.notes(back).toString();
        assertTrue(
                english.contains("removed 3 unused images and the unused build cache, which freed 1.9 GiB"), english);
    }
}
