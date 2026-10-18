package eu.nordtal.season.stewardagent.plan;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.database.update.UpdateReport.Note;
import eu.nordtal.season.database.update.UpdateReport.Step;
import eu.nordtal.season.internalapi.agent.Topology;
import eu.nordtal.season.messages.MessageRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Turns an {@link UpdatePlan} into the {@link UpdateReport} that every surface draws.
 *
 * The resource pack has no service, so it is a note and no line.
 */
public final class PlanReport {

    private PlanReport() {}

    /**
     * Builds the report.
     *
     * @return one line per service that has anything to report, in the order of {@link Topology}
     */
    public static UpdateReport of(final UpdatePlan plan) {
        final Map<String, List<UpdateReport.Change>> work = new LinkedHashMap<>();
        final Map<String, MessageRef> trouble = new LinkedHashMap<>();
        final List<Note> notes = classify(plan, work, trouble);

        UpdateReport report = UpdateReport.at(UpdateReport.Stage.PLANNED);
        for (final Map.Entry<String, List<UpdateReport.Change>> entry : work.entrySet()) {
            final String service = entry.getKey();
            final MessageRef why = trouble.get(service);
            report = report.with(serviceLine(service, entry.getValue(), why, plan));
            if (why != null) {
                final List<String> held = plan.blocker(service) == null ? List.of() : held(entry.getValue());
                notes.add(Note.failed(
                                Step.SOURCES,
                                held.isEmpty() ? why : TEXTS.report().heldBack(why, held))
                        .on(service));
            }
        }

        for (final UpdatePlan.Unclaimed left : plan.unclaimed()) {
            // Loud rather than tidy: a claimless jar is usually a renamed plugin, loaded twice.
            notes.add(Note.warning(Step.SOURCES, TEXTS.report().unclaimed(left.fileName()))
                    .on(left.service()));
        }
        return report.withNotes(notes);
    }

    private static List<Note> classify(
            final UpdatePlan plan,
            final Map<String, List<UpdateReport.Change>> work,
            final Map<String, MessageRef> trouble) {
        // The notes of the resolver come first as worded there.
        final List<Note> notes = new ArrayList<>(plan.notes().stream()
                .map(note -> Note.warning(Step.SOURCES, note))
                .toList());

        for (final Change change : plan.changes()) {
            if (change.service() == null) {
                if (change.status().isWork()) {
                    notes.add(Note.done(Step.SOURCES, TEXTS.report().packMoves(version(change))));
                } else if (change.status().isFailure()) {
                    notes.add(Note.failed(Step.SOURCES, TEXTS.report().packUnchecked(reason(change))));
                }
                continue;
            }
            work.computeIfAbsent(change.service(), key -> new ArrayList<>());
            if (change.status().isWork()) {
                work.get(change.service()).add(moving(change));
            } else if (change.status() == Change.Status.UNSUPPORTED) {
                // The artefact stays named. Nothing is fetched or stopped for it.
                work.get(change.service()).add(UpdateReport.Change.unsupported(change.artifact()));
            } else if (change.status() == Change.Status.NOT_IN_RELEASE) {
                // Our release has no file for this jar, and the installed one stays.
                notes.add(Note.warning(
                                Step.SOURCES,
                                TEXTS.report().notInRelease(reason(change), String.valueOf(change.installed())))
                        .on(change.service()));
            } else if (change.status().isFailure()) {
                // One unreadable row makes the whole service untrustworthy.
                trouble.putIfAbsent(change.service(), reason(change));
            }
        }
        return notes;
    }

    /**
     * Returns the line of one service: FAILED with held rows, FAILED with moving rows, or PLANNED or UNCHANGED.
     */
    private static UpdateReport.ServiceLine serviceLine(
            final String service,
            final List<UpdateReport.Change> changes,
            final @Nullable MessageRef why,
            final UpdatePlan plan) {
        if (why != null && plan.blocker(service) != null) {
            // The run skips a service it cannot trust, so no row of its line reads as moving.
            return new UpdateReport.ServiceLine(
                    service,
                    UpdateReport.State.FAILED,
                    changes.stream()
                            .filter(row -> row.state() != UpdateReport.Change.State.MOVING)
                            .toList(),
                    null);
        }
        if (why != null) {
            // An unchecked server jar stays in .server/ and the plugins beside it still move.
            return new UpdateReport.ServiceLine(service, UpdateReport.State.FAILED, changes, null);
        }
        // PLANNED means stopped and written into; a service with only no-build artefacts is UNCHANGED.
        final boolean moving = changes.stream().anyMatch(row -> row.state() == UpdateReport.Change.State.MOVING);
        return new UpdateReport.ServiceLine(
                service, moving ? UpdateReport.State.PLANNED : UpdateReport.State.UNCHANGED, changes, null);
    }

    private static List<String> held(final List<UpdateReport.Change> changes) {
        return changes.stream()
                .filter(row -> row.state() == UpdateReport.Change.State.MOVING)
                .map(row -> row.artefact() + " " + row.from() + " -> " + row.to())
                .toList();
    }

    /**
     * Returns the row of an artefact as a version jump. Where the pair does not split, the row keeps the filename.
     */
    private static UpdateReport.Change moving(final Change change) {
        final String wantedFile =
                change.wanted() == null ? null : change.wanted().fileName();
        return VersionPair.of(change.installed(), wantedFile)
                .map(pair -> new UpdateReport.Change(change.artifact(), pair.from(), pair.to()))
                .orElseGet(() -> new UpdateReport.Change(change.artifact(), change.installed(), version(change)));
    }

    private static String version(final Change change) {
        return change.wanted() == null ? "?" : change.wanted().version();
    }

    private static MessageRef reason(final Change change) {
        return change.reason() == null ? TEXTS.report().words(change.status().name()) : change.reason();
    }
}
