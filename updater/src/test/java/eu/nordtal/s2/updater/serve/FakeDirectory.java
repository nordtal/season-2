package eu.nordtal.s2.updater.serve;

import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.common.update.UpdateSource;
import eu.nordtal.s2.common.update.UpdateStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An {@link UpdateDirectory} that lives in a map, for the tests about {@link UpdateServer}'s loop.
 * <p>
 * The real one is exercised against a real PostgreSQL in {@code :common} - the claim is
 * {@code FOR UPDATE SKIP LOCKED} and the countdown is database arithmetic, neither of which has a
 * meaningful in-memory version. What this stands in for is the <em>shape</em> of the answers, so
 * that the loop above it can be driven through cases a real database would take a minute of wall
 * clock to produce.
 * </p>
 */
final class FakeDirectory implements UpdateDirectory {

    private final Map<Long, UpdateRequest> rows = new LinkedHashMap<>();
    private final List<UpdateRequest> finished = new ArrayList<>();

    private long nextId = 1L;
    private Instant now = Instant.parse("2026-09-01T12:00:00Z");

    void at(final Instant instant) {
        this.now = instant;
    }

    List<UpdateRequest> finished() {
        return List.copyOf(finished);
    }

    @Override
    public UpdateRequest submit(final UpdateKind kind, final UpdateSource source,
                                final String requestedBy, final Duration delay) {
        final long id = nextId++;
        final UpdateRequest request = new UpdateRequest(id, kind, UpdateStatus.PENDING, source,
                requestedBy, now, now.plus(delay == null ? Duration.ZERO : delay), null, null, null);
        rows.put(id, request);
        return request;
    }

    @Override
    public Optional<UpdateRequest> find(final long id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<UpdateRequest> claimNext() {
        return rows.values().stream()
                .filter(row -> row.status() == UpdateStatus.PENDING)
                .filter(row -> !row.notBefore().isAfter(now))
                .min((left, right) -> {
                    final int byTime = left.notBefore().compareTo(right.notBefore());
                    return byTime != 0 ? byTime : Long.compare(left.id(), right.id());
                })
                .map(row -> {
                    final UpdateRequest claimed = new UpdateRequest(row.id(), row.kind(),
                            UpdateStatus.RUNNING, row.source(), row.requestedBy(), row.requested(),
                            row.notBefore(), now, null, null);
                    rows.put(row.id(), claimed);
                    return claimed;
                });
    }

    @Override
    public Optional<UpdateRequest> finish(final long id, final UpdateStatus status, final String result) {
        final UpdateRequest row = rows.get(id);
        if (row == null || row.status() != UpdateStatus.RUNNING) {
            return Optional.empty();
        }
        final UpdateRequest done = new UpdateRequest(row.id(), row.kind(), status, row.source(),
                row.requestedBy(), row.requested(), row.notBefore(), row.started(), now, result);
        rows.put(id, done);
        finished.add(done);
        return Optional.of(done);
    }

    @Override
    public Optional<UpdateRequest> pendingRestart() {
        return rows.values().stream()
                .filter(row -> row.status() == UpdateStatus.PENDING && row.kind() == UpdateKind.RESTART)
                .findFirst();
    }

    @Override
    public Optional<UpdateRequest> cancelPendingRestart(final String reason) {
        return pendingRestart().map(row -> {
            final UpdateRequest cancelled = new UpdateRequest(row.id(), row.kind(),
                    UpdateStatus.CANCELLED, row.source(), row.requestedBy(), row.requested(),
                    row.notBefore(), null, now, reason);
            rows.put(row.id(), cancelled);
            return cancelled;
        });
    }

    @Override
    public Optional<Instant> nextDue() {
        return rows.values().stream()
                .filter(row -> row.status() == UpdateStatus.PENDING)
                .map(UpdateRequest::notBefore)
                .min(Instant::compareTo);
    }

    @Override
    public int settleOrphans(final String restarted, final String failed) {
        int settled = 0;
        for (final UpdateRequest row : List.copyOf(rows.values())) {
            if (row.status() != UpdateStatus.RUNNING) {
                continue;
            }
            final boolean isRestart = row.kind() == UpdateKind.RESTART;
            rows.put(row.id(), new UpdateRequest(row.id(), row.kind(),
                    isRestart ? UpdateStatus.DONE : UpdateStatus.FAILED, row.source(),
                    row.requestedBy(), row.requested(), row.notBefore(), row.started(), now,
                    isRestart ? restarted : failed));
            settled++;
        }
        return settled;
    }
}
