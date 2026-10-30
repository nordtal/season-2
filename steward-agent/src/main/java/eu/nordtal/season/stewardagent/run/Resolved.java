package eu.nordtal.season.stewardagent.run;

import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.stewardagent.plan.Work;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Writes what the sources answered as resolve steps, one per service with artefacts that move or a finding.
 *
 * The findings give each step its outcome and reason; a finding about no service is the run's.
 */
final class Resolved {

    private Resolved() {}

    /**
     * How a resolve step ended: the worst finding about it, done when there is none.
     *
     * @param reason why, or none when it is done
     */
    record Ended(RunStep.Outcome outcome, RunStep.@Nullable Reason reason) {

        static final Ended DONE = new Ended(RunStep.Outcome.DONE, null);

        void on(final Steps.Step step) {
            step.end(outcome, reason);
        }
    }

    /**
     * Writes one resolve step for each service with work or a finding, and one for each finding that names things.
     * A finding that names things keeps its own reason, which another finding about the service cannot outweigh.
     */
    static void services(final Steps record, final Work planned) {
        planned.services().forEach((service, moving) -> {
            final List<Work.Finding> about = unnamed(planned.about(service));
            if (!moving.isEmpty() || !about.isEmpty()) {
                final Steps.Step step = record.begin(RunStep.Kind.RESOLVE, service);
                moving.forEach(change -> step.change(change.artefact(), change.from(), change.to(), change.image()));
                worst(about).on(step);
            }
            named(record, planned.about(service));
        });
        planned.findings().stream()
                .map(Work.Finding::service)
                .filter(Objects::nonNull)
                .distinct()
                .filter(service -> !planned.services().containsKey(service))
                .forEach(service -> {
                    final List<Work.Finding> about = unnamed(planned.about(service));
                    if (!about.isEmpty()) {
                        worst(about).on(record.begin(RunStep.Kind.RESOLVE, service));
                    }
                    named(record, planned.about(service));
                });
    }

    private static List<Work.Finding> unnamed(final List<Work.Finding> findings) {
        return findings.stream().filter(finding -> finding.names().isEmpty()).toList();
    }

    /** Writes each finding that names something as a resolve step of its own, a line for each name alone. */
    private static void named(final Steps record, final List<Work.Finding> findings) {
        findings.stream().filter(finding -> !finding.names().isEmpty()).forEach(finding -> {
            final Steps.Step step = record.begin(RunStep.Kind.RESOLVE, finding.service());
            finding.names().stream().distinct().forEach(name -> step.change(name, null, null, null));
            new Ended(finding.outcome(), finding.reason()).on(step);
        });
    }

    /** How the run's own resolve step ends, by the findings about no service. */
    static Ended run(final Work planned) {
        return worst(planned.about(null));
    }

    private static Ended worst(final List<Work.Finding> findings) {
        return findings.stream()
                .max(Comparator.comparing(Work.Finding::outcome))
                .filter(found -> found.outcome() != RunStep.Outcome.DONE)
                .map(found -> new Ended(found.outcome(), found.reason()))
                .orElse(Ended.DONE);
    }
}
