package eu.nordtal.s2.common.update;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.jspecify.annotations.Nullable;

/**
 * The only implementation of {@link UpdateDirectory}.
 *
 * It borrows the pool it is given and owns nothing, so there is no {@code close()}.
 */
final class JdbiUpdateDirectory implements UpdateDirectory {

    /** The transaction advisory lock every submit takes before it looks for an open run. */
    private static final long SUBMIT_LOCK = 0x6E6F726474616C52L;

    private final Jdbi jdbi;
    private final UpdateDao dao;

    JdbiUpdateDirectory(final DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbi = Jdbi.create(dataSource).installPlugin(new SqlObjectPlugin()).installPlugin(new PostgresPlugin());
        this.dao = jdbi.onDemand(UpdateDao.class);
    }

    /**
     * Writes a request only when no other run is pending or running and none of its services is held.
     *
     * The lock is its own statement so the following look is a fresh snapshot under READ COMMITTED.
     */
    private UpdateRequest guarded(
            final UpdateKind kind,
            final java.util.@Nullable List<String> services,
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
    public UpdateRequest submit(
            final UpdateKind kind,
            final UpdateSource source,
            final @Nullable String requestedBy,
            final @Nullable Duration delay) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(source, "source");
        // Clamped rather than rejected: a delay computed from two disagreeing clocks means now.
        final long seconds = delay == null ? 0L : Math.max(0L, delay.toSeconds());
        return guarded(kind, null, locked -> locked.submit(kind.name(), source.name(), requestedBy, seconds));
    }

    @Override
    public UpdateRequest submit(
            final UpdateKind kind,
            final UpdateSource source,
            final @Nullable String requestedBy,
            final @Nullable Duration delay,
            final java.util.@Nullable List<String> services) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(source, "source");
        final long seconds = delay == null ? 0L : Math.max(0L, delay.toSeconds());
        final String scope = scopeText(services);
        // The unscoped statement, so a change to the scoped one cannot affect existing callers.
        return guarded(
                kind,
                services,
                locked -> scope == null
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
    public void hold(final String service, final @Nullable String heldBy, final @Nullable Long requestId) {
        dao.hold(service, heldBy, requestId);
    }

    @Override
    public void release(final String service) {
        dao.release(service);
    }

    /**
     * The services as the column holds them, or {@code null} for the whole network.
     *
     * Blanks are dropped and the order is kept. A list that is empty once the blanks are gone is
     * {@code null} rather than {@code ""}: the CHECK in V27 would refuse the empty string anyway,
     * and turning "the caller passed a list of nothing" into a row that names nothing would be a
     * run that stops nothing while claiming to be scoped.
     */
    static @Nullable String scopeText(final java.util.@Nullable List<String> services) {
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
    static java.util.List<String> parseScope(final @Nullable String scope) {
        if (scope == null || scope.isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(scope.split(",", -1))
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
        // Clamped: a negative window means nothing qualifies, not an exception.
        return dao.backupsDoneWithin(Math.max(0L, within.toSeconds())).stream()
                .filter(JdbiUpdateDirectory::saved)
                .findFirst();
    }

    /**
     * Whether this row's report shows a file, rather than merely a run that did not complain.
     *
     * {@link UpdateReports#parse} answers empty for anything it cannot read, and empty is
     * treated as "no" here. That is the opposite of what every drawing surface does with the same
     * text - they fall back to printing it raw - and deliberately so: a Discord embed failing to
     * parse a report should still show something, while a caller deciding whether a world may be
     * deleted must not accept text it cannot interpret as proof.
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
            // CANCELLED is only for a person withdrawing a countdown, never for work already started.
            throw new IllegalArgumentException("A claimed request finishes as DONE or FAILED, not as " + status);
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
        // Clamped like submit's delay.
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
    public Optional<UpdateRequest> open() {
        return dao.open();
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
