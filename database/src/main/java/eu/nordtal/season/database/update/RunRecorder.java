package eu.nordtal.season.database.update;

import org.jspecify.annotations.Nullable;

/**
 * Writes a run's steps and their facts as they happen; {@link #NONE} writes nothing.
 *
 * A step is a row from {@link #begin}, and every fact names the step it belongs to by that row's id.
 */
public interface RunRecorder {

    /** Writes nothing, for a directory without a database; every step it begins is id zero. */
    RunRecorder NONE = new RunRecorder() {};

    /** Writes a step of that run that begins now, and returns its id. */
    default long begin(final long run, final RunStep.Kind step, final @Nullable String service) {
        return 0;
    }

    /** Ends a step of that run that is still open. */
    default void end(
            final long run, final long step, final RunStep.Outcome outcome, final RunStep.@Nullable Reason reason) {}

    /** Ends every step of the run still open, as a run that stops early leaves them. */
    default void endOpen(final long run, final RunStep.Outcome outcome, final RunStep.@Nullable Reason reason) {}

    /** An artefact the step resolves, installs or sees, with what it goes from and to. */
    default void change(
            final long step,
            final String artefact,
            final @Nullable String from,
            final @Nullable String to,
            final RunStep.@Nullable Image image) {}

    default void imageRemoved(final long step, final String image, final long bytes) {}

    default void room(final long step, final long needed, final long free) {}

    default void offsite(final long step, final String snapshot, final long bytesAdded) {}

    /** Adds players the proxy moved off a service during that run, to the move steward-agent began for it. */
    default void moved(final long run, final String from, final String to, final int players) {}

    /** Counts players back on the latest move between those two services. */
    default void returned(final String from, final String to, final int players) {}
}
