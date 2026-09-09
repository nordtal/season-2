package eu.nordtal.s2.updater.plan;

import eu.nordtal.s2.common.update.UpdateReport;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An {@link UpdatePlan} as the {@link UpdateReport} every surface draws.
 *
 * <h2>This is where "nothing is decided twice" is kept</h2>
 * The old rule was that the updater wrote one finished text and everybody printed it. What replaced
 * it says the same thing about the part that mattered: the updater is still the only thing that
 * resolves versions and compares volumes, and this class is the only place its answer is turned
 * into the shape everything else reads. Discord draws that shape as one field per service, chat
 * prints {@link UpdateReport#render()}, and neither of them decides anything.
 *
 * <h2>The pack has no service, and that is why notes exist</h2>
 * {@link Change#service()} is null for the resource pack: it is written to the proxy's
 * {@code pack.yml} rather than installed into a server's {@code plugins/}. It is a note rather than
 * a service line, because a line would invite the run to stop a server for it.
 */
public final class PlanReport {

    private PlanReport() {
    }

    /**
     * @param plan what was resolved
     * @return one line per service that has anything to say, in {@link Topology}'s order so that
     *         the reader always sees the same servers in the same places
     */
    public static @NotNull UpdateReport of(final @NotNull UpdatePlan plan) {
        final Map<String, List<UpdateReport.Change>> work = new LinkedHashMap<>();
        final Map<String, String> trouble = new LinkedHashMap<>();
        final List<String> notes = new ArrayList<>();

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
                work.get(change.service()).add(new UpdateReport.Change(
                        change.artifact(), change.installed(), version(change)));
            } else if (change.status() == Change.Status.UNSUPPORTED) {
                // In the report and not in the run. It is the only kind of change that stops a
                // service being work: nothing is fetched for it, nothing is stopped for it, and it
                // is listed so that an artefact waiting on somebody else's release schedule stays
                // named instead of quietly not existing.
                work.get(change.service()).add(UpdateReport.Change.unsupported(change.artifact()));
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
            if (why != null) {
                report = report.with(new UpdateReport.ServiceLine(service,
                        UpdateReport.State.FAILED, changes, why));
            } else {
                // PLANNED means "this one is going to be stopped and written into". A service whose
                // only rows are artefacts with no build is UNCHANGED, however many of them there
                // are - it says something and it does nothing.
                final boolean moving = changes.stream()
                        .anyMatch(row -> row.state() == UpdateReport.Change.State.MOVING);
                report = report.with(new UpdateReport.ServiceLine(service,
                        moving ? UpdateReport.State.PLANNED : UpdateReport.State.UNCHANGED,
                        changes, null));
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

    private static String version(final Change change) {
        return change.wanted() == null ? "?" : change.wanted().version();
    }

    private static String reason(final Change change) {
        return change.note() == null ? change.status().name() : change.note();
    }
}
