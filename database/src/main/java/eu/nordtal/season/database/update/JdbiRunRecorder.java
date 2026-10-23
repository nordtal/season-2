package eu.nordtal.season.database.update;

import eu.nordtal.season.database.Jdbis;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/** Implements {@link RunRecorder} over the database. It is the only writer of the rows of a run. */
final class JdbiRunRecorder implements RunRecorder {

    private final RunStepDao dao;
    private final BooleanSupplier current;

    /** @param current whether the database has the schema that this code was built for */
    JdbiRunRecorder(final DataSource dataSource, final BooleanSupplier current) {
        this.dao = Jdbis.over(dataSource).onDemand(RunStepDao.class);
        this.current = current;
    }

    /** Returns the steps of the run, none on a schema this code was not built for. */
    List<RunProgress.Step> steps(final long run) {
        return current.getAsBoolean() ? dao.steps(run) : List.of();
    }

    /** Returns the stage and the steps of the run with their facts, nothing on a schema this code was not built for. */
    RunRecord record(final long run) {
        if (!current.getAsBoolean()) {
            return RunRecord.EMPTY;
        }
        final Map<Long, List<RunStep.Change>> changes = byStep(
                dao.changes(run),
                RunStepDao.ChangeRow::step,
                row -> new RunStep.Change(row.artefact(), row.versionFrom(), row.versionTo(), row.image()));
        final Map<Long, List<RunStep.Move>> moves = byStep(
                dao.moves(run),
                RunStepDao.MoveRow::step,
                row -> new RunStep.Move(row.fromService(), row.toService(), row.moved(), row.returned()));
        final Map<Long, List<RunStep.ImageRemoved>> removed = byStep(
                dao.imagesRemoved(run),
                RunStepDao.RemovedRow::step,
                row -> new RunStep.ImageRemoved(row.image(), row.bytes()));
        final Map<Long, RunStepDao.NumbersRow> numbers = new HashMap<>();
        dao.numbers(run).forEach(row -> numbers.put(row.step(), row));
        final List<RunStepDao.ArchiveRow> archives = dao.archives(run);
        final Map<Long, List<RunStep.Archive>> saved =
                byStep(archives, RunStepDao.ArchiveRow::savedBy, JdbiRunRecorder::archive);
        final Map<Long, List<RunStep.Archive>> pruned = byStep(
                archives.stream().filter(row -> row.prunedBy() != null).toList(),
                row -> Objects.requireNonNull(row.prunedBy()),
                JdbiRunRecorder::archive);
        final List<RunStep.Row> steps = dao.rows(run).stream()
                .map(row -> {
                    final RunStepDao.NumbersRow number = numbers.get(row.id());
                    return new RunStep.Row(
                            row.seq(),
                            row.step(),
                            row.service(),
                            row.started(),
                            row.finished(),
                            row.outcome(),
                            row.reason(),
                            row.exitCode(),
                            changes.getOrDefault(row.id(), List.of()),
                            moves.getOrDefault(row.id(), List.of()),
                            removed.getOrDefault(row.id(), List.of()),
                            number == null ? null : number.cacheFreed(),
                            number == null || number.bytesNeeded() == null
                                    ? null
                                    : new RunStep.Room(
                                            number.bytesNeeded(), Objects.requireNonNull(number.bytesFree())),
                            number == null || number.snapshot() == null
                                    ? null
                                    : new RunStep.Offsite(
                                            number.snapshot(), Objects.requireNonNull(number.bytesAdded())),
                            saved.getOrDefault(row.id(), List.of()),
                            pruned.getOrDefault(row.id(), List.of()));
                })
                .toList();
        return new RunRecord(dao.stage(run).map(UpdateReport.Stage::valueOf).orElse(null), steps);
    }

    private static RunStep.Archive archive(final RunStepDao.ArchiveRow row) {
        return new RunStep.Archive(
                row.volume(), row.file(), row.bytes(), row.tookMillis(), row.stopVerified(), row.pruneRule());
    }

    private static <R, V> Map<Long, List<V>> byStep(
            final List<R> rows, final Function<R, Long> step, final Function<R, V> fact) {
        final Map<Long, List<V>> facts = new HashMap<>();
        rows.forEach(row ->
                facts.computeIfAbsent(step.apply(row), key -> new ArrayList<>()).add(fact.apply(row)));
        return facts;
    }

    /** Writes the stage of the report on the row of the run, on any schema since the column came. */
    void stage(final long run, final UpdateReport.Stage stage) {
        dao.stage(run, stage.name());
    }

    /** Sets the stage of every failed run to {@code FAILED}. */
    void failedStages() {
        dao.failedStages();
    }

    /** Writes the services that a request names as not moving. */
    void named(final long run, final List<String> services) {
        dao.named(run, services.toArray(String[]::new));
    }

    /** Marks the services that a run stops as moving. */
    void moving(final long run, final Collection<String> services) {
        dao.moving(run, services.toArray(String[]::new));
    }

    private void when(final Runnable write) {
        if (current.getAsBoolean()) {
            write.run();
        }
    }

    @Override
    public long begin(final long run, final RunStep.Kind step, final @Nullable String service) {
        return current.getAsBoolean() ? dao.begin(run, step.name(), service) : 0;
    }

    @Override
    public void end(
            final long run,
            final long step,
            final RunStep.Outcome outcome,
            final RunStep.@Nullable Reason reason,
            final @Nullable Integer exitCode) {
        when(() -> dao.end(run, step, outcome.name(), reason == null ? null : reason.name(), exitCode));
    }

    @Override
    public void endOpen(final long run, final RunStep.Outcome outcome, final RunStep.@Nullable Reason reason) {
        when(() -> dao.endOpen(run, outcome.name(), reason == null ? null : reason.name()));
    }

    @Override
    public void change(
            final long step,
            final String artefact,
            final @Nullable String from,
            final @Nullable String to,
            final RunStep.@Nullable Image image) {
        when(() -> dao.change(step, artefact, from, to, image == null ? null : image.name()));
    }

    @Override
    public void imageRemoved(final long step, final String image, final long bytes) {
        when(() -> dao.imageRemoved(step, image, bytes));
    }

    @Override
    public void room(final long step, final long needed, final long free) {
        when(() -> dao.room(step, needed, free));
    }

    @Override
    public void offsite(final long step, final String snapshot, final long bytesAdded) {
        when(() -> dao.offsite(step, snapshot, bytesAdded));
    }

    @Override
    public void cacheFreed(final long step, final long bytes) {
        when(() -> dao.cacheFreed(step, bytes));
    }

    @Override
    public void archive(
            final long step,
            final String volume,
            final String file,
            final long bytes,
            final Duration took,
            final boolean stopVerified) {
        when(() -> dao.archive(step, volume, file, bytes, took.toMillis(), stopVerified));
    }

    @Override
    public void pruned(final long step, final String file, final RunStep.PruneRule rule) {
        when(() -> dao.pruned(step, file, rule.name()));
    }

    @Override
    public void moved(final long run, final String from, final String to, final int players) {
        when(() -> dao.moved(run, from, to, players));
    }

    @Override
    public void returned(final String from, final String to, final int players) {
        when(() -> dao.returned(from, to, players));
    }
}
