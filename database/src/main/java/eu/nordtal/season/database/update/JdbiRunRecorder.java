package eu.nordtal.season.database.update;

import eu.nordtal.season.database.Jdbis;
import java.util.Collection;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * The {@link RunRecorder} over the database and the only writer of a run's rows; it borrows the pool.
 *
 * Until the schema has their tables it writes nothing, so a one-shot ahead of the database starts at its migration.
 */
final class JdbiRunRecorder implements RunRecorder {

    private final RunStepDao dao;
    private final BooleanSupplier current;

    /** @param current whether the database is at the schema this code was built for */
    JdbiRunRecorder(final DataSource dataSource, final BooleanSupplier current) {
        this.dao = Jdbis.over(dataSource).onDemand(RunStepDao.class);
        this.current = current;
    }

    /** Writes the stage of the report on the run's row, which a reader of the row needs no report for. */
    void stage(final long run, final UpdateReport.Stage stage) {
        when(() -> dao.stage(run, stage.name()));
    }

    /** Marks every run settled as failed by a statement that wrote no stage. */
    void failedStages() {
        when(dao::failedStages);
    }

    /** The services a request names, none of them moving yet. */
    void named(final long run, final List<String> services) {
        when(() -> dao.named(run, services.toArray(String[]::new)));
    }

    /** Marks the services a run stops as moving. */
    void moving(final long run, final Collection<String> services) {
        when(() -> dao.moving(run, services.toArray(String[]::new)));
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
            final long run, final long step, final RunStep.Outcome outcome, final RunStep.@Nullable Reason reason) {
        when(() -> dao.end(run, step, outcome.name(), reason == null ? null : reason.name()));
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
    public void moved(final long run, final String from, final String to, final int players) {
        when(() -> dao.moved(run, from, to, players));
    }

    @Override
    public void returned(final String from, final String to, final int players) {
        when(() -> dao.returned(from, to, players));
    }
}
