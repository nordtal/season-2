package eu.nordtal.season.stewardagent.run;

import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.stewardagent.apply.ApplyResult;
import eu.nordtal.season.stewardagent.plan.Work;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Writes the result of an apply as install steps, for an update run and for the start-up install. */
final class Installed {

    private Installed() {}

    /**
     * Writes one install step per service the apply touched and one for the pack.
     *
     * Each step names the artefacts with the versions the plan moved them from and to.
     */
    static void record(
            final Steps record, final Work planned, final ApplyResult result, final Collection<String> services) {
        final Map<@Nullable String, List<ApplyResult.Outcome>> bySource = new LinkedHashMap<>();
        for (final ApplyResult.Outcome outcome : result.outcomes()) {
            if (outcome.service() == null || services.contains(outcome.service())) {
                bySource.computeIfAbsent(outcome.service(), service -> new ArrayList<>())
                        .add(outcome);
            }
        }
        bySource.forEach((service, outcomes) -> {
            final Steps.Step step = record.begin(RunStep.Kind.INSTALL, service);
            for (final ApplyResult.Outcome outcome : outcomes) {
                final RunStep.Change moved = service == null
                        ? null
                        : planned.of(service).stream()
                                .filter(change -> change.artefact().equals(outcome.artifact()))
                                .findFirst()
                                .orElse(null);
                step.change(
                        outcome.artifact(),
                        moved == null ? null : moved.from(),
                        moved == null ? null : moved.to(),
                        null);
            }
            // A skipped file did not go in either.
            if (outcomes.stream()
                    .anyMatch(outcome -> outcome.status() == ApplyResult.Status.FAILED
                            || outcome.status() == ApplyResult.Status.SKIPPED)) {
                step.failed(RunStep.Reason.TOOL_FAILED);
            } else {
                step.done();
            }
        });
    }
}
