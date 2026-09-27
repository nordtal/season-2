package eu.nordtal.s2.steward.worker.plan;

import eu.nordtal.s2.common.update.UpdateReport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * An {@link UpdatePlan} as the {@link UpdateReport} every surface draws.
 *
 * This is where "nothing is decided twice" is kept: The old rule was that steward-worker wrote one finished text and
 * everybody printed it. What replaced it says the same thing about the part that mattered: the worker is still the
 * only thing that resolves versions and compares volumes, and this class is the only place its answer is turned into
 * the shape everything else reads. Discord draws that shape as one field per service, chat prints
 * {@link UpdateReport#render()}, and neither of them decides anything.
 *
 * The pack has no service, and that is why notes exist: {@link Change#service()} is null for the resource pack: it
 * is written to the proxy's {@code pack.yml} rather than installed into a server's {@code plugins/}. It is a note
 * rather than a service line, because a line would invite the run to stop a server for it.
 */
public final class PlanReport {

    private PlanReport() {}

    /**
     * @param plan what was resolved
     * @return one line per service that has anything to say, in {@link Topology}'s order so that
     *         the reader always sees the same servers in the same places
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
            // Loud rather than tidy: a claimless jar is usually a renamed plugin, loaded twice by mistake.
            notes.add(left.service() + " also holds " + left.fileName()
                    + ", which nothing in this plan claims - if that is a renamed jar, the server"
                    + " is loading two versions of one plugin");
        }
        for (final String note : notes) {
            report = report.withNote(note);
        }
        return report;
    }

    /**
     * Sorts the plan's changes into per-service work, per-service trouble, and the notes that belong to no service.
     */
    private static List<String> classify(
            final UpdatePlan plan,
            final Map<String, List<UpdateReport.Change>> work,
            final Map<String, String> trouble) {
        // The resolver's own notes come first, copied rather than composed: this class draws, it does not decide.
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
                // In the report, not in the run: nothing fetched or stopped, listed so a stalled artefact stays named.
                work.get(change.service()).add(UpdateReport.Change.unsupported(change.artifact()));
            } else if (change.status() == Change.Status.NOT_IN_RELEASE) {
                // Not work, not a failure: our release carries nothing for this jar, and the one installed stays.
                notes.add(change.service() + ": " + reason(change) + "; " + change.installed() + " stays");
            } else if (change.status().isFailure()) {
                // One unreadable row makes the whole service untrustworthy: only one answer is safe to act on.
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
            // A server jar that could not be checked: the build in .server/ stays and the plugins beside it still move.
            return new UpdateReport.ServiceLine(service, UpdateReport.State.FAILED, changes, why);
        }
        // PLANNED means stopped and written into; a service with only no-build artefacts is UNCHANGED regardless.
        final boolean moving = changes.stream().anyMatch(row -> row.state() == UpdateReport.Change.State.MOVING);
        return new UpdateReport.ServiceLine(
                service, moving ? UpdateReport.State.PLANNED : UpdateReport.State.UNCHANGED, changes, null);
    }

    /**
     * One artefact's row, as a version jump wherever the two filenames allow one.
     *
     * THE FILENAME IS NOT THE LINE. A row shows the version jump alone - not the installed filename against the
     * available version. The report - which is what Discord, the chat follower and the run's own page draw - must
     * never print {@code proxy proxy-0.9.3.jar -> 0.9.4}, a filename against a version.
     *
     * Derived here rather than by each surface, and written into the report rather than beside it: the worker is the
     * only process that holds both filenames, and a new key in the report's JSON would be a key every older reader
     * throws on ( {@code UpdateReports} says why). So the shape does not change - {@code from} simply carries the
     * version it always claimed to.
     *
     * When the pair does not come apart - the resource pack, whose installed side is a SHA-1 - the filename
     * stays, which is the ticket's own fallback: an invented version is worse than an ugly name.
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
