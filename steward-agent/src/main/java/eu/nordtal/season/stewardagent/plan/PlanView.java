package eu.nordtal.season.stewardagent.plan;

import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.messages.MessageJson;
import eu.nordtal.season.messages.MessageRef;
import java.util.List;

/** The whole resolve, flattened for the updates page; every row is carried with its status. */
public final class PlanView {

    private PlanView() {}

    /** Every row with its status, so outdated and unresolved never look alike. */
    public static AgentWire.Resolve of(final UpdatePlan plan) {
        final List<AgentWire.ResolvedChange> changes = plan.changes().stream()
                .map(change -> new AgentWire.ResolvedChange(
                        change.service(),
                        change.artifact(),
                        change.status().name(),
                        change.status().isWork(),
                        change.status().isFailure(),
                        // Work a run would not do: another row of the same service failed to check, so it is skipped.
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
                plan.unclaimed().stream()
                        .map(one -> new AgentWire.Unclaimed(one.service(), one.fileName()))
                        .toList(),
                plan.notes().stream().map(PlanView::wire).toList());
    }

    /** A message as the wire carries it: each value typed, as the message system writes it. */
    static AgentWire.Message wire(final MessageRef message) {
        return new AgentWire.Message(
                message.key(),
                Json.tree(Json.encode(MessageJson.encodeArgs(message.args()))).getAsJsonObject());
    }
}
