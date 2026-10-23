package eu.nordtal.season.stewardagent.run;

import static eu.nordtal.season.database.AdminTexts.TEXTS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.stewardagent.plan.Change;
import eu.nordtal.season.stewardagent.plan.UpdatePlan;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests that the updates page gets a plan as the steps a run writes, computed and never stored. */
class PlanViewTest {

    @Test
    void aPlanIsTheResolveStepsARunWouldWrite() {
        final UpdatePlan plan = new UpdatePlan(
                Instant.EPOCH,
                "v0.20.0",
                false,
                List.of(new Change("smp", "chunky", Change.Status.NOT_IN_RELEASE, "Chunky-1.4.jar", null, null)),
                List.of(new UpdatePlan.Unclaimed("limbo", "Somebodys-1.0.jar")),
                List.of(TEXTS.report().velocityAhead("4.4.0", "4.3.0")));

        final List<AgentWire.PlannedStep> steps = PlanView.of(plan).steps();

        assertEquals(
                List.of(
                        "RESOLVE - WARNING VELOCITY_AHEAD [run.reason]",
                        "RESOLVE smp WARNING NOT_IN_RELEASE [run.reason]",
                        "RESOLVE limbo WARNING UNCLAIMED [run.reason]"),
                steps.stream()
                        .map(step -> step.step() + " " + (step.service() == null ? "-" : step.service()) + " "
                                + step.outcome() + " " + step.reason() + " "
                                + step.lines().stream()
                                        .map(AgentWire.Message::key)
                                        .toList())
                        .toList());
    }
}
