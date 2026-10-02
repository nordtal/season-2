package eu.nordtal.s2.stewardagent.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The whole resolve, flattened for the updates page; every row is carried with its status. */
public final class PlanView {

    private PlanView() {}

    /** Every row with its status, so outdated and unresolved never look alike. */
    public static Map<String, Object> of(final UpdatePlan plan) {
        final List<Map<String, Object>> changes = new ArrayList<>();
        for (final Change change : plan.changes()) {
            final Map<String, Object> row = new LinkedHashMap<>();
            // The pack has no service, so the key is left off rather than sent empty.
            if (change.service() != null) {
                row.put("service", change.service());
            }
            row.put("artifact", change.artifact());
            row.put("status", change.status().name());
            row.put("work", change.status().isWork());
            row.put("failure", change.status().isFailure());
            // Work a run would not do: another row of the same service failed to check, so the applier skips it.
            row.put(
                    "held",
                    change.status().isWork() && change.service() != null && plan.blocker(change.service()) != null);
            if (change.installed() != null) {
                row.put("installed", change.installed());
            }
            if (change.wanted() != null) {
                // Version for a person, filename for the comparison, since some files carry a suffix.
                row.put("version", change.wanted().version());
                row.put("fileName", change.wanted().fileName());
            }
            if (change.note() != null) {
                row.put("note", change.note());
            }
            changes.add(row);
        }

        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("resolvedAt", plan.resolvedAt().toString());
        if (plan.seasonTag() != null) {
            answer.put("seasonTag", plan.seasonTag());
        }
        answer.put("seasonPrerelease", plan.seasonPrerelease());
        answer.put("hasWork", plan.hasWork());
        answer.put("hasFailures", plan.hasFailures());
        answer.put("changes", changes);
        answer.put(
                "unclaimed",
                plan.unclaimed().stream()
                        .map(one -> Map.of("service", one.service(), "fileName", one.fileName()))
                        .toList());
        answer.put("notes", plan.notes());
        return answer;
    }
}
