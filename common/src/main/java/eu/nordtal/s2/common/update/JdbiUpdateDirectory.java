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

    /**
     * The transaction lock every submit takes before it looks for an open run. The worker's own
     * advisory locks spell {@code nordtalS} and {@code nordtal1}; this one is {@code nordtalR}.
     */
    private static final long SUBMIT_LOCK = 0x6E6F726474616C52L;

    private final Jdbi jdbi;
    private final UpdateDao dao;

    JdbiUpdateDirectory(final DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbi = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new PostgresPlugin());
        this.dao = jdbi.onDemand(UpdateDao.class);
    }

    /**
     * One run in the whole network: the write happens only when no other run is pending or
     * running, and a take-down only when none of its services is already held.
     *
     * <h2>A lock first, then a fresh look</h2>
     * The lock is taken in its own statement so that the look after it is a new snapshot: under
     * READ COMMITTED a single statement would read the table as it was before it waited, and two
     * presses a millisecond apart would both find it empty. Not a unique index, because a queue of
     * several rows is still a legal state for everything that reads the table - it is only
     * submitting into one that is refused.
     */
    private UpdateRequest guarded(final UpdateKind kind, final java.util.List<String> services,
                                  final java.util.function.Function<UpdateDao, UpdateRequest> write) {
        return jdbi.inTransaction(handle -> {
            handle.execute("SELECT pg_advisory_xact_lock(?)", SUBMIT_LOCK);
            final UpdateDao locked = handle.attach(UpdateDao.class);
            final Optional<UpdateRequest> open = locked.open();
            if (open.isPresent()) {
                throw RunRefused.runOpen(open.get());
            }
            if (kind == UpdateKind.DOWN && services != null && !services.isEmpty()) {
                final java.util.List<String> held = locked.holds().stream()
                        .map(ServiceHold::service)
                        .filter(services::contains)
                        .toList();
                if (!held.isEmpty()) {
                    throw RunRefused.alreadyHeld(held);
                }
            }
            return write.apply(locked);
        });
    }

    @Override
    public UpdateRequest submit(final UpdateKind kind, final UpdateSource source,
                                final String requestedBy, final Duration delay) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(source, "source");
        // Clamped rather than rejected: a caller computing a delay from two clocks that disagree
        // should get "now", not an exception on a path that is asking for a restart.
        final long seconds = delay == null ? 0L : Math.max(0L, delay.toSeconds());
        return guarded(kind, null, locked -> locked.submit(kind.name(), source.name(), requestedBy, seconds));
    }

    @Override
    public UpdateRequest submit(final UpdateKind kind, final UpdateSource source,
                                final String requestedBy, final Duration delay,
                                final java.util.List<String> services) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(source, "source");
        final long seconds = delay == null ? 0L : Math.max(0L, delay.toSeconds());
        final String scope = scopeText(services);
        // The unscoped statement, not the scoped one with a NULL bind. They are the same row today;
        // keeping "everything" on the path every existing caller already takes means a change to
        // one can never quietly become a change to the other.
        return guarded(kind, services, locked -> scope == null
                ? locked.submit(kind.name(), source.name(), requestedBy, seconds)
                : locked.submitScoped(kind.name(), source.name(), requestedBy, seconds, scope));
    }

    @Override
    public java.util.List<String> scopeOf(final long id) {
        return parseScope(dao.scope(id));
    }

    @Override
    public java.util.List<ServiceHold> holds() {
        return dao.holds();
    }

    @Override
    public void hold(final String service, final String heldBy, final Long requestId) {
        dao.hold(service, heldBy, requestId);
    }

    @Override
    public void release(final String service) {
        dao.release(service);
    }

    /**
     * The services as the column holds them, or {@code null} for the whole network.
     *
     * <p>Blanks are dropped and the order is kept. A list that is empty once the blanks are gone is
     * {@code null} rather than {@code ""}: the CHECK in V27 would refuse the empty string anyway,
     * and turning "the caller passed a list of nothing" into a row that names nothing would be a
     * run that stops nothing while claiming to be scoped.</p>
     */
    static String scopeText(final java.util.List<String> services) {
        if (services == null || services.isEmpty()) {
            return null;
        }
        final String joined = services.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::strip)
                .filter(service -> !service.isEmpty())
                .distinct()
                .collect(java.util.stream.Collectors.joining(","));
        return joined.isEmpty() ? null : joined;
    }

    /** The inverse. {@code null} and blank both mean the whole network - see {@code scopeOf}. */
    static java.util.List<String> parseScope(final String scope) {
        if (scope == null || scope.isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(scope.split(","))
                .map(String::strip)
                .filter(service -> !service.isEmpty())
                .toList();
    }

    @Override
    public Optional<UpdateRequest> find(final long id) {
        return dao.find(id);
    }

    @Override
    public java.util.List<UpdateRequest> recent(final int limit) {
        return dao.recent(Math.max(1, limit));
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
