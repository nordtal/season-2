package eu.nordtal.s2.steward.worker.plan;

import eu.nordtal.s2.database.update.UpdateReport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Turns an {@link UpdatePlan} into the {@link UpdateReport} every surface draws, the only place that happens.
 *
 * The resource pack has no service, so it is a note rather than a line a run would stop a server for.
 */
public final class PlanReport {

    private PlanReport() {}

    /**
     * Builds the report.
     *
     * @param plan what was resolved
     * @return one line per service that has anything to say, in {@link Topology}'s order
     */
    public static UpdateReport of(final UpdatePlan plan) {
        final Map<String, List<UpdateReport.Change>> work = new LinkedHashMap<>();
        final Map<String, String> trouble = new LinkedHashMap<>();
        final List<String> notes = classify(plan, work, trouble);

        UpdateReport report = UpdateReport.at(UpdateReport.Stage.PLANNED);
        for (final Map.Entry<String, List<UpdateReport.Change>> entry : work.entrySet()) {
            final String service = entry.getKey();
            report = report.with(serviceLine(service, entry.getValue(), trouble.get(service), plan));
        }

        for (final UpdatePlan.Unclaimed left : plan.unclaimed()) {
            // Loud rather than tidy: a claimless jar is usually a renamed plugin, loaded twice.
            notes.add(left.service() + " also holds " + left.fileName()
                    + ", which nothing in this plan claims - if that is a renamed jar, the server"
                    + " is loading two versions of one plugin");
        }
        for (final String note : notes) {
            report = report.withNote(note);
        }
        return report;
    }

    /** Sorts the changes into per-service work, per-service trouble, and notes that belong to no service. */
    private static List<String> classify(
            final UpdatePlan plan,
            final Map<String, List<UpdateReport.Change>> work,
            final Map<String, String> trouble) {
        // The resolver's own notes first, copied: this class draws, it does not decide.
        final List<String> notes = new ArrayList<>(plan.notes());

        for (final Change change : plan.changes()) {
            if (change.service() == null) {
                if (change.status().isWork()) {
                    notes.add("the resource pack moves to " + version(change));
                } else if (change.status().isFailure()) {
                    notes.add("the resource pack could not be checked: " + reason(change));
                }
                continue;
            }
            work.computeIfAbsent(change.service(), key -> new ArrayList<>());
            if (change.status().isWork()) {
                work.get(change.service()).add(moving(change));
            } else if (change.status() == Change.Status.UNSUPPORTED) {
                // Listed so a stalled artefact stays named; nothing is fetched or stopped for it.
                work.get(change.service()).add(UpdateReport.Change.unsupported(change.artifact()));
            } else if (change.status() == Change.Status.NOT_IN_RELEASE) {
                // Not work, not a failure: our release carries nothing for this jar, and the installed one stays.
                notes.add(change.service() + ": " + reason(change) + "; " + change.installed() + " stays");
            } else if (change.status().isFailure()) {
                // One unreadable row makes the whole service untrustworthy.
                trouble.putIfAbsent(change.service(), reason(change));
            }
        }
        return notes;
    }

    /** One service's line: FAILED with rows held back, FAILED with its rows still moving, or PLANNED/UNCHANGED. */
    private static UpdateReport.ServiceLine serviceLine(
            final String service,
            final List<UpdateReport.Change> changes,
            final @Nullable String why,
            final UpdatePlan plan) {
        if (why != null && plan.blocker(service) != null) {
            // The run skips a service it could not trust, so nothing on its line may read as moving.
            final List<String> held = changes.stream()
                    .filter(row -> row.state() == UpdateReport.Change.State.MOVING)
                    .map(row -> row.artefact() + " " + row.from() + " -> " + row.to())
                    .toList();
            return new UpdateReport.ServiceLine(
                    service,
                    UpdateReport.State.FAILED,
                    changes.stream()
                            .filter(row -> row.state() != UpdateReport.Change.State.MOVING)
                            .toList(),
                    held.isEmpty() ? why : why + "; held back: " + String.join(", ", held));
        }
        if (why != null) {
            // An unchecked server jar stays in .server/ and the plugins beside it still move.
            return new UpdateReport.ServiceLine(service, UpdateReport.State.FAILED, changes, why);
        }
        // PLANNED means stopped and written into; a service with only no-build artefacts is UNCHANGED.
        final boolean moving = changes.stream().anyMatch(row -> row.state() == UpdateReport.Change.State.MOVING);
        return new UpdateReport.ServiceLine(
                service, moving ? UpdateReport.State.PLANNED : UpdateReport.State.UNCHANGED, changes, null);
    }

    /**
     * One artefact's row as a version jump, never a filename against a version.
     *
     * Where the pair does not split, as for the pack's SHA-1, the filename stays rather than an invented version.
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

    private static String reason(final Change change) {
        return change.note() == null ? change.status().name() : change.note();
    }
}
