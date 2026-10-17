package eu.nordtal.season.database.update;

import eu.nordtal.season.database.Jdbis;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/** The {@link RunRecorder} over the database; it borrows the pool and owns nothing. */
final class JdbiRunRecorder implements RunRecorder {

    private final RunStepDao dao;

    JdbiRunRecorder(final DataSource dataSource) {
        this.dao = Jdbis.over(dataSource).onDemand(RunStepDao.class);
    }

    @Override
    public long begin(final long run, final RunStep.Kind step, final @Nullable String service) {
        return dao.begin(run, step.name(), service);
    }

    @Override
    public void end(
            final long run, final long step, final RunStep.Outcome outcome, final RunStep.@Nullable Reason reason) {
        dao.end(run, step, outcome.name(), reason == null ? null : reason.name());
    }

    @Override
    public void endOpen(final long run, final RunStep.Outcome outcome, final RunStep.@Nullable Reason reason) {
        dao.endOpen(run, outcome.name(), reason == null ? null : reason.name());
    }

    @Override
    public void change(
            final long step,
            final String artefact,
            final @Nullable String from,
            final @Nullable String to,
            final RunStep.@Nullable Image image) {
        dao.change(step, artefact, from, to, image == null ? null : image.name());
    }

    @Override
    public void imageRemoved(final long step, final String image, final long bytes) {
        dao.imageRemoved(step, image, bytes);
    }

    @Override
    public void room(final long step, final long needed, final long free) {
        dao.room(step, needed, free);
    }

    @Override
    public void offsite(final long step, final String snapshot, final long bytesAdded) {
        dao.offsite(step, snapshot, bytesAdded);
    }

    @Override
    public void moved(final long run, final String from, final String to, final int players) {
        dao.moved(run, from, to, players);
    }

    @Override
    public void returned(final String from, final String to, final int players) {
        dao.returned(from, to, players);
    }
}
