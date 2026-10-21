package eu.nordtal.season.stewardagent.run;

import eu.nordtal.season.database.update.RunRecorder;
import eu.nordtal.season.database.update.RunStep;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Keeps the steps it is told in memory, for a plan that is computed and never stored. */
final class CollectedSteps implements RunRecorder {

    private final List<Collected> steps = new ArrayList<>();

    @Override
    public long begin(final long run, final RunStep.Kind step, final @Nullable String service) {
        steps.add(new Collected(step, service));
        return steps.size();
    }

    @Override
    public void end(
            final long run,
            final long step,
            final RunStep.Outcome outcome,
            final RunStep.@Nullable Reason reason,
            final @Nullable Integer exitCode) {
        final Collected ended = of(step);
        ended.outcome = outcome;
        ended.reason = reason;
        ended.exitCode = exitCode;
    }

    @Override
    public void change(
            final long step,
            final String artefact,
            final @Nullable String from,
            final @Nullable String to,
            final RunStep.@Nullable Image image) {
        of(step).changes.add(new RunStep.Change(artefact, from, to, image));
    }

    @Override
    public void imageRemoved(final long step, final String image, final long bytes) {
        of(step).imagesRemoved.add(new RunStep.ImageRemoved(image, bytes));
    }

    @Override
    public void room(final long step, final long needed, final long free) {
        of(step).room = new RunStep.Room(needed, free);
    }

    @Override
    public void offsite(final long step, final String snapshot, final long bytesAdded) {
        of(step).offsite = new RunStep.Offsite(snapshot, bytesAdded);
    }

    @Override
    public void cacheFreed(final long step, final long bytes) {
        of(step).cacheFreed = bytes;
    }

    @Override
    public void archive(
            final long step,
            final String volume,
            final String file,
            final long bytes,
            final Duration took,
            final boolean stopVerified) {
        of(step).saved.add(new RunStep.Archive(volume, file, bytes, took.toMillis(), stopVerified, null));
    }

    @Override
    public void pruned(final long step, final String file, final RunStep.PruneRule rule) {
        steps.stream()
                .flatMap(one -> one.saved.stream())
                .filter(archive -> archive.file().equals(file))
                .findFirst()
                .ifPresent(archive -> of(step).pruned
                        .add(new RunStep.Archive(
                                archive.volume(),
                                file,
                                archive.bytes(),
                                archive.tookMillis(),
                                archive.stopVerified(),
                                rule)));
    }

    /** Returns the steps in the order they began. */
    List<RunStep.Row> rows() {
        final List<RunStep.Row> rows = new ArrayList<>();
        for (int seq = 1; seq <= steps.size(); seq++) {
            final Collected step = steps.get(seq - 1);
            rows.add(new RunStep.Row(
                    seq,
                    step.kind,
                    step.service,
                    null,
                    null,
                    step.outcome,
                    step.reason,
                    step.exitCode,
                    List.copyOf(step.changes),
                    List.of(),
                    List.copyOf(step.imagesRemoved),
                    step.cacheFreed,
                    step.room,
                    step.offsite,
                    List.copyOf(step.saved),
                    List.copyOf(step.pruned)));
        }
        return List.copyOf(rows);
    }

    /**
     * Writes the steps again, each ended as it ended here.
     *
     * shortcut: only the changes are written, all the start-up install collects; add the other facts when needed.
     */
    void into(final Steps record) {
        for (final Collected step : steps) {
            final Steps.Step written = record.begin(step.kind, step.service);
            step.changes.forEach(
                    change -> written.change(change.artefact(), change.from(), change.to(), change.image()));
            if (step.outcome != null) {
                written.end(step.outcome, step.reason);
            }
        }
    }

    private Collected of(final long step) {
        return steps.get((int) step - 1);
    }

    private static final class Collected {

        private final RunStep.Kind kind;
        private final @Nullable String service;
        private RunStep.@Nullable Outcome outcome;
        private RunStep.@Nullable Reason reason;
        private @Nullable Integer exitCode;
        private final List<RunStep.Change> changes = new ArrayList<>();
        private final List<RunStep.ImageRemoved> imagesRemoved = new ArrayList<>();
        private @Nullable Long cacheFreed;
        private RunStep.@Nullable Room room;
        private RunStep.@Nullable Offsite offsite;
        private final List<RunStep.Archive> saved = new ArrayList<>();
        private final List<RunStep.Archive> pruned = new ArrayList<>();

        private Collected(final RunStep.Kind kind, final @Nullable String service) {
            this.kind = kind;
            this.service = service;
        }
    }
}
