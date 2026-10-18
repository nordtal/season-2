package eu.nordtal.season.stewardagent.plan;

import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.messages.MessageJson;
import eu.nordtal.season.messages.MessageRef;
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
                // The page draws the notes with the view of the run page.
                PlanReport.of(plan).notes().stream()
                        .map(note -> new AgentWire.Note(
                                note.step().name(), note.outcome().name(), note.service(), wire(note.what())))
                        .toList());
    }

    static AgentWire.Message wire(final MessageRef message) {
        return new AgentWire.Message(
                message.key(),
                Json.tree(Json.encode(MessageJson.encodeArgs(message.args()))).getAsJsonObject());
    }
}
