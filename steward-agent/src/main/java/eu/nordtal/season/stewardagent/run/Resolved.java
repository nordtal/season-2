package eu.nordtal.season.stewardagent.run;

import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateReport;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Writes what the sources answered as resolve steps, one per service with artefacts that move.
 *
 * The plan's records of the sources give each step its outcome and reason; a record of no service is the run's.
 */
final class Resolved {

    /**
     * Maps each kind of source record to a reason. Any other record that is not done failed its source.
     */
    private static final Map<String, RunStep.Reason> REASONS = Map.of(
            "report.images-unread", RunStep.Reason.UNVERIFIABLE,
            "report.images-uncompared", RunStep.Reason.UNVERIFIABLE,
            "report.images-unverifiable", RunStep.Reason.UNVERIFIABLE,
            "report.images-local", RunStep.Reason.LOCAL_BUILD,
            "report.foreign-newer", RunStep.Reason.FOREIGN_NEWER,
            "report.not-in-release", RunStep.Reason.NOT_IN_RELEASE,
            "report.unclaimed", RunStep.Reason.UNCLAIMED,
            "report.velocity-ahead", RunStep.Reason.VELOCITY_AHEAD);

    private Resolved() {}

    /**
     * How a resolve step ended: the worst record about it, done when there is none.
     *
     * @param reason why, or none when it is done
     */
    record Ended(RunStep.Outcome outcome, RunStep.@Nullable Reason reason) {

        static final Ended DONE = new Ended(RunStep.Outcome.DONE, null);

        void on(final Steps.Step step) {
            step.end(outcome, reason);
        }
    }

    /** Writes one resolve step for each service that has work or a record. */
    static void services(final Steps record, final UpdateReport planned) {
        for (final UpdateReport.ServiceLine line : planned.services()) {
            final List<UpdateReport.Change> moving = line.changes().stream()
                    .filter(change -> change.state() == UpdateReport.Change.State.MOVING)
                    .toList();
            final List<UpdateReport.Note> about = about(planned, line.service());
            if (moving.isEmpty() && about.isEmpty()) {
                continue;
            }
            final Steps.Step step = record.begin(RunStep.Kind.RESOLVE, line.service());
            for (final UpdateReport.Change change : moving) {
                step.change(change.artefact(), change.from(), versionOf(change), null);
            }
            worst(about).on(step);
        }
        planned.notes().stream()
                .map(UpdateReport.Note::service)
                .filter(Objects::nonNull)
                .distinct()
                .filter(service -> planned.services().stream()
                        .noneMatch(line -> line.service().equals(service)))
                .forEach(service -> worst(about(planned, service)).on(record.begin(RunStep.Kind.RESOLVE, service)));
    }

    /** How the run's own resolve step ends, by the records of the sources about no service. */
    static Ended run(final UpdateReport planned) {
        return worst(about(planned, null));
    }

    private static List<UpdateReport.Note> about(final UpdateReport planned, final @Nullable String service) {
        return planned.notes().stream()
                .filter(note -> note.step() == UpdateReport.Step.SOURCES)
                .filter(note -> Objects.equals(note.service(), service))
                .toList();
    }

    private static Ended worst(final List<UpdateReport.Note> notes) {
        return notes.stream()
                .max(Comparator.comparing(UpdateReport.Note::outcome))
                .filter(note -> note.outcome() != UpdateReport.Outcome.DONE)
                .map(note -> new Ended(
                        RunStep.Outcome.valueOf(note.outcome().name()),
                        REASONS.getOrDefault(note.what().key(), RunStep.Reason.SOURCE_FAILED)))
                .orElse(Ended.DONE);
    }

    private static @Nullable String versionOf(final UpdateReport.Change change) {
        return UpdateReport.Change.NONE.equals(change.to()) ? null : change.to();
    }
}
