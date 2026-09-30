package eu.nordtal.s2.database.update;

import static eu.nordtal.s2.database.DatabaseMessages.MESSAGES;

import eu.nordtal.s2.messages.Refused;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.jspecify.annotations.Nullable;

/** The only implementation of {@link UpdateDirectory}; it borrows the pool and owns nothing. */
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
     * Writes a request only when no other run is open and none of its services is held.
     *
     * The lock is its own statement, so the check after it reads a fresh snapshot under READ COMMITTED.
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
                throw new Refused(
                        UpdateRefusal.RUN_OPEN,
                        MESSAGES.update()
                                .runOpen(
                                        open.get().id(),
                                        open.get().kind(),
                                        open.get().status().name().toLowerCase(Locale.ROOT)));
            }
            if (kind == UpdateKind.DOWN && services != null && !services.isEmpty()) {
                final java.util.List<String> held = locked.holds().stream()
                        .map(ServiceHold::service)
                        .filter(services::contains)
                        .toList();
                if (!held.isEmpty()) {
                    throw new Refused(
                            UpdateRefusal.ALREADY_HELD, MESSAGES.update().alreadyHeld(String.join(", ", held)));
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

    /** Returns the services as the column holds them, blanks dropped, or {@code null} for the whole network. */
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

    /** Returns the services of a scope column; {@code null} and blank both mean the whole network. */
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
        return dao.backupsDoneWithin(Math.max(0L, within.toSeconds())).stream()
                .filter(JdbiUpdateDirectory::saved)
                .findFirst();
    }

    /** Returns whether this row's report proves a file was saved; an unreadable report counts as no. */
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
            throw new IllegalArgumentException("A claimed request finishes as DONE or FAILED, not as " + status);
        }
        return dao.finish(id, status.name(), result);
    }

    @Override
    public boolean handOver(final long id, final String result) {
        return dao.handOver(id, result) > 0;
    }

    @Override
    public boolean progress(final long id, final String result) {
        return dao.progress(id, result) > 0;
    }

    @Override
    public Optional<UpdateRequest> startCountdown(final long id, final Duration length) {
        Objects.requireNonNull(length, "length");
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
