package eu.nordtal.season.database.update;

import eu.nordtal.season.database.Jdbis;
import java.util.Collection;
import java.util.List;
import java.util.function.BooleanSupplier;
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

    /** Writes the stage of the report on the row of the run. */
    void stage(final long run, final UpdateReport.Stage stage) {
        when(() -> dao.stage(run, stage.name()));
    }

    /** Sets the stage of every failed run to {@code FAILED}. */
    void failedStages() {
        when(dao::failedStages);
    }

    /** Writes the services that a request names as not moving. */
    void named(final long run, final List<String> services) {
        when(() -> dao.named(run, services.toArray(String[]::new)));
    }

    /** Marks the services that a run stops as moving. */
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
