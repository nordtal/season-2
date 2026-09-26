package eu.nordtal.s2.steward.worker.plan;

import eu.nordtal.s2.common.update.UpdateReport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;

/**
 * An {@link UpdatePlan} as the {@link UpdateReport} every surface draws.
 *
 * <h2>This is where "nothing is decided twice" is kept</h2>
 * The old rule was that steward-worker wrote one finished text and everybody printed it. What
 * replaced it says the same thing about the part that mattered: the worker is still the only thing
 * that resolves versions and compares volumes, and this class is the only place its answer is
 * turned into the shape everything else reads. Discord draws that shape as one field per service,
 * chat prints {@link UpdateReport#render()}, and neither of them decides anything.
 *
 * <h2>The pack has no service, and that is why notes exist</h2>
 * {@link Change#service()} is null for the resource pack: it is written to the proxy's
 * {@code pack.yml} rather than installed into a server's {@code plugins/}. It is a note rather than
 * a service line, because a line would invite the run to stop a server for it.
 */
public final class PlanReport {

    private PlanReport() {}

    /**
     * @param plan what was resolved
     * @return one line per service that has anything to say, in {@link Topology}'s order so that
     *         the reader always sees the same servers in the same places
     */
    public static @NotNull UpdateReport of(final @NotNull UpdatePlan plan) {
        final Map<String, List<UpdateReport.Change>> work = new LinkedHashMap<>();
        final Map<String, String> trouble = new LinkedHashMap<>();

        // The resolver's own notes come first, and they are copied rather than composed: this class
        // draws, it does not decide. A note there is something worked out while resolving that
        // belongs to no service - the proxy moving past the Velocity API proxy was built
        // against is the one that exists.
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
                // In the report and not in the run. It is the only kind of change that stops a
                // service being work: nothing is fetched for it, nothing is stopped for it, and it
                // is listed so that an artefact waiting on somebody else's release schedule stays
                // named instead of quietly not existing.
                work.get(change.service()).add(UpdateReport.Change.unsupported(change.artifact()));
            } else if (change.status() == Change.Status.NOT_IN_RELEASE) {
                // Not work and not a failure: our own release answered, carries nothing for this
                // jar, and the one installed stays. Said out loud so a module missing from a
                // release is noticed without taking the service's other updates down with it.
                notes.add(change.service() + ": " + reason(change) + "; " + change.installed() + " stays");
            } else if (change.status().isFailure()) {
                // One unreadable row makes the whole service untrustworthy: "nothing to do" and
                // "I could not look" are the same picture, and only one of them is safe to act on.
                trouble.putIfAbsent(change.service(), reason(change));
            }
        }

        UpdateReport report = UpdateReport.at(UpdateReport.Stage.PLANNED);
        for (final Map.Entry<String, List<UpdateReport.Change>> entry : work.entrySet()) {
            final String service = entry.getKey();
            final List<UpdateReport.Change> changes = entry.getValue();
            final String why = trouble.get(service);
            if (why != null && plan.blocker(service) != null) {
                // The run skips a service it could not trust, so nothing on its line may read as
                // moving: a MOVING row is what the countdown, the stop and the evacuation count.
                // What would have moved is named in the detail instead.
                final List<String> held = changes.stream()
                        .filter(row -> row.state() == UpdateReport.Change.State.MOVING)
                        .map(row -> row.artefact() + " " + row.from() + " -> " + row.to())
                        .toList();
                report = report.with(new UpdateReport.ServiceLine(
                        service,
                        UpdateReport.State.FAILED,
                        changes.stream()
                                .filter(row -> row.state() != UpdateReport.Change.State.MOVING)
                                .toList(),
                        held.isEmpty() ? why : why + "; held back: " + String.join(", ", held)));
            } else if (why != null) {
                // A server jar that could not be checked: the build in .server/ stays and the
                // plugins beside it still move, so the line keeps its MOVING rows.
                report = report.with(new UpdateReport.ServiceLine(service, UpdateReport.State.FAILED, changes, why));
            } else {
                // PLANNED means "this one is going to be stopped and written into". A service whose
                // only rows are artefacts with no build is UNCHANGED, however many of them there
                // are - it says something and it does nothing.
                final boolean moving =
                        changes.stream().anyMatch(row -> row.state() == UpdateReport.Change.State.MOVING);
                report = report.with(new UpdateReport.ServiceLine(
                        service, moving ? UpdateReport.State.PLANNED : UpdateReport.State.UNCHANGED, changes, null));
            }
        }

        for (final UpdatePlan.Unclaimed left : plan.unclaimed()) {
            // Loud rather than tidy: a jar nothing claims is usually a plugin whose publisher
            // renamed its file, which means the old one is still being loaded alongside the new.
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
     * One artefact's row, as a version jump wherever the two filenames allow one.
     *
     * <p>THE FILENAME IS NOT THE LINE (season-2-ops/142). Till asked on 2026-09-20 that a row show
     * the version jump alone - not the installed filename against the available version. The
     * Available card was given that on the same day; the report - which is what
     * Discord, the chat follower and the run's own page draw - kept printing
     * {@code proxy proxy-0.9.3.jar -> 0.9.4}, a filename against a version.</p>
     *
     * <p>Derived here rather than by each surface, and written into the report rather than beside
     * it: the worker is the only process that holds both filenames, and a new key in the report's
     * JSON would be a key every older reader throws on ({@code UpdateReports} says why). So the
     * shape does not change - {@code from} simply carries the version it always claimed to.</p>
     *
     * <p>When the pair does not come apart - the resource pack, whose installed side is a SHA-1 -
     * the filename stays, which is the ticket's own fallback: an invented version is worse than an
     * ugly name.</p>
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
