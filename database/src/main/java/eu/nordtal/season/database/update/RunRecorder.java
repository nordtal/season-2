package eu.nordtal.season.database.update;

import java.time.Duration;
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
            final long run, final long step, final RunStep.Outcome outcome, final RunStep.@Nullable Reason reason) {
        end(run, step, outcome, reason, null);
    }

    /**
     * Ends a step of that run that is open.
     *
     * @param exitCode what the tool exited with, only with {@link RunStep.Reason#TOOL_FAILED}
     */
    default void end(
            final long run,
            final long step,
            final RunStep.Outcome outcome,
            final RunStep.@Nullable Reason reason,
            final @Nullable Integer exitCode) {}

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

    /** The bytes a cleanup freed in the build cache. */
    default void cacheFreed(final long step, final long bytes) {}

    /**
     * An archive the step wrote, into the catalogue.
     *
     * @param volume the volume it holds, or {@code database} for a dump
     * @param stopVerified false when a service it holds stopped and nobody could read how it ended
     */
    default void archive(
            final long step,
            final String volume,
            final String file,
            final long bytes,
            final Duration took,
            final boolean stopVerified) {}

    /** An archive of the catalogue that the step removed. A file the catalogue does not hold records nothing. */
    default void pruned(final long step, final String file, final RunStep.PruneRule rule) {}

    /** Adds players that the proxy moved off a service to the move that steward-agent began for it. */
    default void moved(final long run, final String from, final String to, final int players) {}

    /** Counts players back on the latest move between those two services. */
    default void returned(final String from, final String to, final int players) {}
}
