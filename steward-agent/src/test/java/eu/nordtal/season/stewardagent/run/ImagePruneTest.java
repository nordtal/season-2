package eu.nordtal.season.stewardagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.stewardagent.Told;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** An update removes the images nothing uses once everything it stopped is back, and says what that freed. */
class ImagePruneTest {

    /** The run that a mark beside an archive names. */
    private static final long RUN = 312L;

    private final FakeContainers containers = new FakeContainers().running("smp");
    private final List<UpdateReport> progress = new ArrayList<>();

    @Test
    void theImagesArePrunedOnlyOnceEverythingIsBackAndTheReportSaysWhatWent() {
        final UpdateRun steps = new UpdateRun(RUN, containers, new FakeSnapshots(), progress::add);
        final UpdateRun.Stopped stopped =
                new UpdateRun.Stopped(UpdateReport.at(UpdateReport.Stage.STOPPING), List.of(), containers.runtime());

        final Run.Done done =
                Kinds.pruningImages(containers, Run.Payload.NONE, Steps.NONE).carryOut(steps, stopped);

        assertEquals(List.of(), containers.calls, "nothing is pruned while the servers are down");

        final UpdateReport back = done.afterwards().apply(done.report());

        assertEquals(List.of("prune-images"), containers.calls);
        assertEquals(List.of("CLEANUP DONE -: removed 3 unused images, freed 1.9 GiB"), Told.notes(back));
    }
}
