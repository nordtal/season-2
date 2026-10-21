package eu.nordtal.season.stewardagent.run;

import static eu.nordtal.season.database.AdminTexts.TEXTS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateReport;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Checks the resolve steps an update run writes for its plan. */
class ResolvedTest {

    @Test
    void anOutOfDateImageResolvesAsPulled() {
        final UpdateReport planned = UpdateReport.at(UpdateReport.Stage.PLANNED)
                .with(new UpdateReport.ServiceLine(
                        "proxy",
                        UpdateReport.State.PLANNED,
                        List.of(
                                new UpdateReport.Change("proxy", "0.51.0", "0.52.0"),
                                UpdateReport.Change.told("image", TEXTS.report().imageOutdated())),
                        null));
        final CollectedSteps collected = new CollectedSteps();

        Resolved.services(new Steps(collected, 1), planned);

        assertEquals(
                List.of(
                        new RunStep.Change("proxy", "0.51.0", "0.52.0", null),
                        new RunStep.Change("image", null, null, RunStep.Image.PULLED)),
                collected.rows().getFirst().changes());
    }
}
