package eu.nordtal.season.stewardagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.stewardagent.plan.Work;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Checks the resolve steps an update run writes for its plan. */
class ResolvedTest {

    @Test
    void eachServiceWithWorkOrAFindingIsAStepThatEndsOnItsWorstFinding() {
        final List<RunStep.Change> proxy = List.of(
                new RunStep.Change("proxy", "0.51.0", "0.52.0", null),
                new RunStep.Change("image", null, null, RunStep.Image.PULLED));
        final Work planned = new Work(
                Map.of("proxy", proxy, "smp", List.of()),
                List.of(
                        new Work.Finding("proxy", RunStep.Outcome.WARNING, RunStep.Reason.UNVERIFIABLE),
                        new Work.Finding("limbo", RunStep.Outcome.WARNING, RunStep.Reason.UNCLAIMED),
                        new Work.Finding("limbo", RunStep.Outcome.FAILED, RunStep.Reason.SOURCE_FAILED)));
        final CollectedSteps collected = new CollectedSteps();

        Resolved.services(new Steps(collected, 1), planned);

        final List<RunStep.Row> rows = collected.rows();
        assertEquals(2, rows.size(), "a service with neither work nor a finding is no step: " + rows);
        assertEquals("proxy", rows.get(0).service());
        assertEquals(proxy, rows.get(0).changes());
        assertEquals(RunStep.Outcome.WARNING, rows.get(0).outcome());
        assertEquals(RunStep.Reason.UNVERIFIABLE, rows.get(0).reason());
        assertEquals("limbo", rows.get(1).service());
        assertEquals(RunStep.Outcome.FAILED, rows.get(1).outcome());
        assertEquals(RunStep.Reason.SOURCE_FAILED, rows.get(1).reason());
    }
}
