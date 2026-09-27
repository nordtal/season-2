package eu.nordtal.s2.steward.worker.serve;

import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.common.update.UpdateReports;
import eu.nordtal.s2.steward.worker.plan.Change;
import eu.nordtal.s2.steward.worker.plan.JarName;
import eu.nordtal.s2.steward.worker.plan.Topology;
import eu.nordtal.s2.steward.worker.plan.UpdatePlan;
import org.jspecify.annotations.Nullable;

/**
 * Whether an update is run by this process, or handed to the steward-worker it is about to install.
 *
 * Why an update is never carried out by an older worker than the release: the schema is migrated from the
 * migrations on this process's classpath, and the services started afterwards validate against theirs. A worker one
 * release behind migrates to the schema it knows and then starts a bot that knows one more, which refuses the
 * database and restarts until somebody restarts the worker by hand. The update logic is the same case in another
 * shape: a fix to the choreography would otherwise take effect one release late.
 *
 * So a run that finds a newer worker places only that jar - before the standbys, the countdown or any stop - puts the
 * request back into the inbox with {@link #note(String)} on its report, and exits. {@code restart: unless-stopped}
 * starts the container again, the entrypoint picks the newest jar in the volume, that worker migrates at startup and
 * claims the request as its first. This is still no update on a schedule: the restart is part of a run somebody asked
 * for.
 *
 * The note is what stops a loop: a worker that finds a run handed to a version it is not - the entrypoint started
 * something other than the jar that was placed - refuses the run instead of handing it over again.
 */
final class Handover {

    /** The start of the note; the version follows it up to the comma. */
    private static final String NOTE = "Handed over to steward-worker ";

    private Handover() {}

    /** What {@link #decide} came to. */
    sealed interface Decision permits Proceed, HandOver, Refuse {}

    /** This process runs the update itself. */
    record Proceed() implements Decision {}

    /**
     * Hand the run to {@code version}.
     *
     * @param install whether its jar has to be placed first; {@code false} when it is already in the volume and was
     *                only never started
     */
    record HandOver(String version, boolean install) implements Decision {}

    /** Refuse the run, with nothing stopped and nothing installed. */
    record Refuse(String reason) implements Decision {}

    /**
     * Decides for one update.
     *
     * @param plan     the resolved plan, already cut to the run's scope, so a run that does not name the worker has
     *                 no row for it
     * @param own      this process's version, or {@code null} when it cannot tell - it then never hands over, since
     *                 nothing could check afterwards that the right process took the run
     * @param previous the request's {@code result} as it was claimed, which carries the note of a handover
     */
    static Decision decide(final UpdatePlan plan, final @Nullable String own, final @Nullable String previous) {
        final String handedTo = target(previous);
        if (handedTo != null) {
            if (handedTo.equals(own)) {
                return new Proceed();
            }
            return new Refuse("This run was handed to steward-worker " + handedTo + ", but steward-worker "
                    + (own == null ? "of an unknown version" : own) + " started instead, so nothing was"
                    + " stopped and nothing was installed. Handing it over again would restart the worker in a loop."
                    + " The first line of steward-worker's log names the jar the entrypoint chose.");
        }
        if (own == null) {
            return new Proceed();
        }
        final Change worker = plan.changes().stream()
                .filter(change -> Topology.STEWARD_WORKER.equals(change.service()))
                .filter(change -> Topology.STEWARD_WORKER.equals(change.artifact()))
                .findFirst()
                .orElse(null);
        if (worker == null) {
            return new Proceed();
        }
        if (worker.status().isWork() && worker.wanted() != null) {
            final String wanted = worker.wanted().version();
            // The jar missing from the volume while this process already is that version: the run places it as ever.
            return wanted.equals(own) ? new Proceed() : new HandOver(wanted, true);
        }
        if (worker.status() == Change.Status.UP_TO_DATE && worker.installed() != null) {
            final String installed = JarName.versionOf(worker.installed());
            if (installed != null && !installed.equals(own)) {
                return new HandOver(installed, false);
            }
        }
        return new Proceed();
    }

    /** The note a handed-over report carries, naming the version that finishes the run. */
    static String note(final String version) {
        return NOTE + version + ", which finishes this run once it has started.";
    }

    /** The version a report says its run was handed to, or {@code null} when it was not handed over. */
    static @Nullable String target(final @Nullable String result) {
        final UpdateReport report = UpdateReports.parse(result).orElse(null);
        if (report == null) {
            return null;
        }
        for (final String note : report.notes()) {
            final int comma = note.indexOf(',', NOTE.length());
            if (note.startsWith(NOTE) && comma > NOTE.length()) {
                return note.substring(NOTE.length(), comma);
            }
        }
        return null;
    }

    /**
     * The version of the jar this process was started from, as its manifest names it.
     *
     * {@code null} from an IDE or a test, where there is no jar and no manifest.
     */
    static @Nullable String ownVersion() {
        return Handover.class.getPackage().getImplementationVersion();
    }
}
