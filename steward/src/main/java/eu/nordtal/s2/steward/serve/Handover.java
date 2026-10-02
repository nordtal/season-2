package eu.nordtal.s2.steward.serve;

import eu.nordtal.s2.database.update.UpdateReport;
import eu.nordtal.s2.database.update.UpdateReports;
import eu.nordtal.s2.internalapi.agent.JarName;
import eu.nordtal.s2.steward.plan.Change;
import eu.nordtal.s2.steward.plan.Topology;
import eu.nordtal.s2.steward.plan.UpdatePlan;
import org.jspecify.annotations.Nullable;

/**
 * Whether an update is run by this process, or handed to the steward it is about to install.
 *
 * An older steward would migrate to an older schema than its services, so it hands the run over with {@link #note}.
 */
final class Handover {

    /** The start of the note; the version follows it up to the comma. */
    private static final String NOTE = "Handed over to steward ";

    private Handover() {}

    /** What {@link #decide} came to. */
    sealed interface Decision permits Proceed, HandOver, Refuse {}

    /** This process runs the update itself. */
    record Proceed() implements Decision {}

    /**
     * Hand the run to {@code version}.
     *
     * @param install whether its jar has to be placed first; {@code false} when it is already in the volume
     */
    record HandOver(String version, boolean install) implements Decision {}

    /** Refuse the run, with nothing stopped and nothing installed. */
    record Refuse(String reason) implements Decision {}

    /**
     * Decides for one update.
     *
     * @param plan     the resolved plan, already cut to the run's scope
     * @param own      this process's version, or {@code null} when unknown, which never hands over
     * @param previous the request's {@code result} as it was claimed, carrying any handover note
     */
    static Decision decide(final UpdatePlan plan, final @Nullable String own, final @Nullable String previous) {
        final String handedTo = target(previous);
        if (handedTo != null) {
            if (handedTo.equals(own)) {
                return new Proceed();
            }
            return new Refuse("This run was handed to steward " + handedTo + ", but steward "
                    + (own == null ? "of an unknown version" : own) + " started instead, so nothing was"
                    + " stopped and nothing was installed. Handing it over again would restart steward in a loop."
                    + " The first line of steward's log names the jar the entrypoint chose.");
        }
        if (own == null) {
            return new Proceed();
        }
        final Change row = plan.changes().stream()
                .filter(change -> Topology.STEWARD.equals(change.service()))
                .filter(change -> Topology.STEWARD.equals(change.artifact()))
                .findFirst()
                .orElse(null);
        if (row == null) {
            return new Proceed();
        }
        if (row.status().isWork() && row.wanted() != null) {
            final String wanted = row.wanted().version();
            // This process is that version but its jar is missing from the volume: the run places it.
            return wanted.equals(own) ? new Proceed() : new HandOver(wanted, true);
        }
        if (row.status() == Change.Status.UP_TO_DATE && row.installed() != null) {
            final String installed = JarName.versionOf(row.installed());
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
     * {@code null} from an IDE or a test.
     */
    static @Nullable String ownVersion() {
        return Handover.class.getPackage().getImplementationVersion();
    }
}
