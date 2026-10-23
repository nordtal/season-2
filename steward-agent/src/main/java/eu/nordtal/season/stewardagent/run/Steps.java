package eu.nordtal.season.stewardagent.run;

import eu.nordtal.season.database.update.RunRecorder;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.RunStep.Kind;
import eu.nordtal.season.database.update.RunStep.Outcome;
import eu.nordtal.season.database.update.RunStep.Reason;
import eu.nordtal.season.internalapi.agent.SnapshotResult;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * One run's steps as rows, each written when it begins and ended with its outcome; {@link #none} writes nothing.
 *
 * A failed write is logged and the run goes on. Whether a step failed is kept here too, since it fails the run.
 */
@Slf4j
final class Steps {

    private final RunRecorder recorder;
    private final long run;

    private volatile boolean failed;

    /** Returns steps that write nothing and still tell whether one failed. */
    static Steps none() {
        return new Steps(RunRecorder.NONE, 0);
    }

    /** Returns whether a step of this run ended {@code FAILED}. */
    boolean failed() {
        return failed;
    }

    Steps(final RunRecorder recorder, final long run) {
        this.recorder = recorder;
        this.run = run;
    }

    Step begin(final Kind step) {
        return begin(step, null);
    }

    Step begin(final Kind step, final @Nullable String service) {
        long id = 0;
        try {
            id = recorder.begin(run, step, service);
        } catch (final RuntimeException failure) {
            log.warn("Could not write the step {} {} of run {}", step, service, run, failure);
        }
        return new Step(id, step, service);
    }

    void ended(final Kind step, final @Nullable String service, final Outcome outcome, final @Nullable Reason reason) {
        begin(step, service).end(outcome, reason);
    }

    /** Ends every step still open, for a run that stops early. */
    void endOpen(final Outcome outcome, final @Nullable Reason reason) {
        failed |= outcome == Outcome.FAILED;
        write(() -> recorder.endOpen(run, outcome, reason));
    }

    private void write(final Runnable statement) {
        try {
            statement.run();
        } catch (final RuntimeException failure) {
            log.warn("Could not write a step of run {}", run, failure);
        }
    }

    final class Step {

        private final long id;
        private final Kind kind;
        private final @Nullable String service;

        private Step(final long id, final Kind kind, final @Nullable String service) {
            this.id = id;
            this.kind = kind;
            this.service = service;
        }

        void done() {
            end(Outcome.DONE, null);
        }

        void skipped(final Reason reason) {
            end(Outcome.SKIPPED, reason);
        }

        void warned(final Reason reason) {
            end(Outcome.WARNING, reason);
        }

        void failed(final Reason reason) {
            end(Outcome.FAILED, reason);
        }

        /** Ends the step on what a save or restore came back with, a failure as the tool's with its exit code. */
        void ended(final SnapshotResult result) {
            if (result.ok()) {
                done();
                return;
            }
            failed = true;
            if (id != 0) {
                write(() -> recorder.end(run, id, Outcome.FAILED, Reason.TOOL_FAILED, result.exitCode()));
            } else {
                unkept(Outcome.FAILED, Reason.TOOL_FAILED);
            }
        }

        void end(final Outcome outcome, final @Nullable Reason reason) {
            failed |= outcome == Outcome.FAILED;
            if (id != 0) {
                write(() -> recorder.end(run, id, outcome, reason));
            } else {
                unkept(outcome, reason);
            }
        }

        /** A run ahead of the schema keeps no steps before its migration, so the log keeps how each ended. */
        private void unkept(final Outcome outcome, final @Nullable Reason reason) {
            if (run != 0) {
                log.info("Run {}, step {} {} ended {} {}", run, kind, service, outcome, reason);
            }
        }

        Step change(
                final String artefact,
                final @Nullable String from,
                final @Nullable String to,
                final RunStep.@Nullable Image image) {
            if (id != 0) {
                write(() -> recorder.change(id, artefact, from, to, image));
            }
            return this;
        }

        void imageRemoved(final String image, final long bytes) {
            if (id != 0) {
                write(() -> recorder.imageRemoved(id, image, bytes));
            }
        }

        void room(final long needed, final long free) {
            if (id != 0) {
                write(() -> recorder.room(id, needed, free));
            }
        }

        void offsite(final String snapshot, final long bytesAdded) {
            if (id != 0) {
                write(() -> recorder.offsite(id, snapshot, bytesAdded));
            }
        }

        void cacheFreed(final long bytes) {
            if (id != 0) {
                write(() -> recorder.cacheFreed(id, bytes));
            }
        }

        /** Catalogues the archive a save wrote, if it wrote one. */
        void archive(final SnapshotResult saved, final boolean stopVerified) {
            final String file = saved.file();
            if (id != 0 && saved.ok() && file != null) {
                write(() -> recorder.archive(
                        id,
                        saved.name(),
                        Path.of(file).getFileName().toString(),
                        saved.bytes(),
                        saved.took(),
                        stopVerified));
            }
        }

        void pruned(final String file, final RunStep.PruneRule rule) {
            if (id != 0) {
                write(() -> recorder.pruned(id, file, rule));
            }
        }
    }
}
