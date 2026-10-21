package eu.nordtal.season.stewardagent.run;

import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.database.update.RunLines;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.messages.MessageJson;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.stewardagent.plan.PlanReport;
import eu.nordtal.season.stewardagent.plan.UpdatePlan;
import java.util.List;

/** The whole resolve for the updates page, every row with its status. */
public final class PlanView {

    private PlanView() {}

    public static AgentWire.Resolve of(final UpdatePlan plan) {
        final List<AgentWire.ResolvedChange> changes = plan.changes().stream()
                .map(change -> new AgentWire.ResolvedChange(
                        change.service(),
                        change.artifact(),
                        change.status().name(),
                        change.status().isWork(),
                        change.status().isFailure(),
                        // Another row of the same service failed to check, so a run skips this one.
                        change.status().isWork() && change.service() != null && plan.blocker(change.service()) != null,
                        change.installed(),
                        // Version for a person, filename for the comparison, since some files carry a suffix.
                        change.wanted() == null ? null : change.wanted().version(),
                        change.wanted() == null ? null : change.wanted().fileName(),
                        change.reason() == null ? null : wire(change.reason())))
                .toList();
        return new AgentWire.Resolve(
                plan.resolvedAt(),
                plan.seasonTag(),
                plan.seasonPrerelease(),
                plan.hasWork(),
                plan.hasFailures(),
                changes,
                steps(PlanReport.of(plan)));
    }

    /** The resolve steps an update run writes for this plan, as the run itself computes them. */
    private static List<AgentWire.PlannedStep> steps(final UpdateReport planned) {
        final CollectedSteps collected = new CollectedSteps();
        final Steps record = new Steps(collected, 1);
        Resolved.run(planned).on(record.begin(RunStep.Kind.RESOLVE));
        Resolved.services(record, planned);
        return collected.rows().stream()
                .map(step -> new AgentWire.PlannedStep(
                        step.step().name(),
                        step.service(),
                        step.outcome() == null ? null : step.outcome().name(),
                        step.reason() == null ? null : step.reason().name(),
                        RunLines.of(step).stream().map(PlanView::wire).toList()))
                .toList();
    }

    static AgentWire.Message wire(final MessageRef message) {
        return new AgentWire.Message(
                message.key(),
                Json.tree(Json.encode(MessageJson.encodeArgs(message.args()))).getAsJsonObject());
    }
}
