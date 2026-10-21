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
 * One run's steps as rows, each written when it begins and ended with its outcome; {@link #NONE} writes nothing.
 *
 * A failed write is logged and the run goes on.
 */
@Slf4j
final class Steps {

    static final Steps NONE = new Steps(RunRecorder.NONE, 0);

    private final RunRecorder recorder;
    private final long run;

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
        return new Step(id);
    }

    void ended(final Kind step, final @Nullable String service, final Outcome outcome, final @Nullable Reason reason) {
        begin(step, service).end(outcome, reason);
    }

    /** Ends every step still open, for a run that stops early. */
    void endOpen(final Outcome outcome, final @Nullable Reason reason) {
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

        private Step(final long id) {
            this.id = id;
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
            } else if (id != 0) {
                write(() -> recorder.end(run, id, Outcome.FAILED, Reason.TOOL_FAILED, result.exitCode()));
            }
        }

        void end(final Outcome outcome, final @Nullable Reason reason) {
            if (id != 0) {
                write(() -> recorder.end(run, id, outcome, reason));
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
