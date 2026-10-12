package eu.nordtal.season.stewardagent.plan;

import static eu.nordtal.season.database.AdminTexts.TEXTS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.internalapi.agent.AgentWire;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The updates page gets a plan's notes as the records a run writes, with step, outcome and service. */
class PlanViewTest {

    @Test
    void aPlansNotesAreRecordsWithTheirStepOutcomeAndService() {
        final UpdatePlan plan = new UpdatePlan(
                Instant.EPOCH,
                "v0.20.0",
                false,
                List.of(new Change("smp", "chunky", Change.Status.NOT_IN_RELEASE, "Chunky-1.4.jar", null, null)),
                List.of(new UpdatePlan.Unclaimed("limbo", "Somebodys-1.0.jar")),
                List.of(TEXTS.report().words("Velocity is newer than its API")));

        final List<AgentWire.Note> notes = PlanView.of(plan).notes();

        assertEquals(
                List.of(
                        "SOURCES WARNING - report.words",
                        "SOURCES WARNING smp report.not-in-release",
                        "SOURCES WARNING limbo report.unclaimed"),
                notes.stream()
                        .map(note -> note.step() + " " + note.outcome() + " "
                                + (note.service() == null ? "-" : note.service()) + " "
                                + note.what().key())
                        .toList());
    }
}
