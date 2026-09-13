package eu.nordtal.s2.common.update;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * The only implementation of {@link UpdateDirectory}. Package-private: consumers get it from the
 * factory method on the interface and never name JDBI themselves.
 * <p>
 * It borrows the pool it is given and owns nothing, which is why there is no {@code close()} here
 * and none on the interface - the process that built the pool closes the pool.
 * </p>
 */
final class JdbiUpdateDirectory implements UpdateDirectory {

    private final UpdateDao dao;

    JdbiUpdateDirectory(final DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.dao = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new PostgresPlugin())
                .onDemand(UpdateDao.class);
    }

    @Override
    public UpdateRequest submit(final UpdateKind kind, final UpdateSource source,
                                final String requestedBy, final Duration delay) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(source, "source");
        // Clamped rather than rejected: a caller computing a delay from two clocks that disagree
        // should get "now", not an exception on a path that is asking for a restart.
        final long seconds = delay == null ? 0L : Math.max(0L, delay.toSeconds());
        return dao.submit(kind.name(), source.name(), requestedBy, seconds);
    }

    @Override
    public Optional<UpdateRequest> find(final long id) {
        return dao.find(id);
    }

    @Override
    public java.util.List<UpdateRequest> since(final long id) {
        return dao.since(id);
    }

    @Override
    public long latestId() {
        return dao.latestId();
    }

    @Override
    public java.util.List<UpdateRequest> finishedWithin(final Duration window) {
        Objects.requireNonNull(window, "window");
        return dao.finishedWithin(Math.max(0L, window.toSeconds()));
    }

    @Override
    public Optional<UpdateRequest> lastSuccessfulBackup(final Duration within) {
        Objects.requireNonNull(within, "within");
        // Clamped like every other duration on this class: a caller computing a window from a
        // configured number should get "nothing qualifies", not an exception on the path that is
        // deciding whether to delete a world.
        return dao.backupsDoneWithin(Math.max(0L, within.toSeconds())).stream()
                .filter(JdbiUpdateDirectory::saved)
                .findFirst();
    }

    /**
     * Whether this row's report shows a file, rather than merely a run that did not complain.
     *
     * <p>{@link UpdateReports#parse} answers empty for anything it cannot read, and empty is
     * treated as "no" here. That is the opposite of what every drawing surface does with the same
     * text - they fall back to printing it raw - and deliberately so: a Discord embed failing to
     * parse a report should still show something, while a caller deciding whether a world may be
     * deleted must not accept text it cannot interpret as proof.</p>
     */
    private static boolean saved(final UpdateRequest request) {
        return UpdateReports.parse(request.result())
                .filter(report -> report.stage() == UpdateReport.Stage.DONE)
                .filter(UpdateReport::savedSomething)
                .isPresent();
    }

    @Override
    public Optional<UpdateRequest> claimNext() {
        return dao.claimNext();
    }

    @Override
    public Optional<UpdateRequest> finish(final long id, final UpdateStatus status, final String result) {
        Objects.requireNonNull(status, "status");
        if (!status.isFinished() || status == UpdateStatus.CANCELLED) {
            // CANCELLED is reachable only through cancelCountdown, which is a person withdrawing
            // a countdown. Letting it in here would mean a worker could report work it had
            // already started as somebody else's cancellation.
            throw new IllegalArgumentException(
                    "A claimed request finishes as DONE or FAILED, not as " + status);
        }
        return dao.finish(id, status.name(), result);
    }

    @Override
    public boolean progress(final long id, final String result) {
        return dao.progress(id, result) > 0;
    }

    @Override
    public Optional<UpdateRequest> startCountdown(final long id, final Duration length) {
        Objects.requireNonNull(length, "length");
        // Clamped like submit's delay, and for the same reason: a caller computing a countdown from
        // two clocks should get "now", not an exception on the path that is taking servers down.
        return dao.startCountdown(id, Math.max(0L, length.toSeconds()));
    }

    @Override
    public boolean commitCountdown(final long id) {
        return dao.commitCountdown(id).isPresent();
    }

    @Override
    public Optional<UpdateRequest> countingDown() {
        return dao.countingDown();
    }

    @Override
    public Optional<UpdateRequest> cancelCountdown(final String reason) {
        return dao.cancelCountdown(reason);
    }

    @Override
    public Optional<UpdateRequest> running() {
        return dao.running();
    }

    @Override
    public Optional<Instant> nextDue() {
        return dao.nextDue().map(OffsetDateTime::toInstant);
    }

    @Override
    public int settleOrphans(final String failed) {
        return dao.failOrphans(failed);
    }
}
