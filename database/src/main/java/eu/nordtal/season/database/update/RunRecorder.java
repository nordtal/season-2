package eu.nordtal.season.database.update;

import org.jspecify.annotations.Nullable;

/**
 * Writes the steps of a run and their facts as they happen. {@link #NONE} writes nothing.
 * A step is a row from {@link #begin}, and every fact names its step by the id of that row.
 */
public interface RunRecorder {

    /** Writes nothing, for a directory without a database. Every step it begins has id 0. */
    RunRecorder NONE = new RunRecorder() {};

    /** Writes a step of that run that begins now and returns its id. */
    default long begin(final long run, final RunStep.Kind step, final @Nullable String service) {
        return 0;
    }

    /** Ends a step of that run that is open. */
    default void end(
            final long run, final long step, final RunStep.Outcome outcome, final RunStep.@Nullable Reason reason) {}

    /** Ends every open step of the run, as a run that stops early leaves them. */
    default void endOpen(final long run, final RunStep.Outcome outcome, final RunStep.@Nullable Reason reason) {}

    /** An artefact that the step resolves, installs or sees, with what it goes from and to. */
    default void change(
            final long step,
            final String artefact,
            final @Nullable String from,
            final @Nullable String to,
            final RunStep.@Nullable Image image) {}

    default void imageRemoved(final long step, final String image, final long bytes) {}

    default void room(final long step, final long needed, final long free) {}

    default void offsite(final long step, final String snapshot, final long bytesAdded) {}

    /** Adds players that the proxy moved off a service to the move that steward-agent began for it. */
    default void moved(final long run, final String from, final String to, final int players) {}

    /** Counts players back on the latest move between those two services. */
    default void returned(final String from, final String to, final int players) {}
}
