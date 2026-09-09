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

    /** Every progress write a run made, in order. See {@link #progress(long, String)}. */
    final List<String> progressWrites = new ArrayList<>();

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
    public boolean progress(final long id, final String result) {
        // The stage-by-stage writes the real directory makes. Recorded rather than ignored so a
        // test can assert that a run reported its progress at all - a run that only writes its
        // answer at the end is the thing the live embed exists to stop being.
        progressWrites.add(result);
        final UpdateRequest row = rows.get(id);
        return row != null && row.status() == UpdateStatus.RUNNING;
    }

    @Override
    public java.util.List<UpdateRequest> since(final long id) {
        return rows.values().stream().filter(row -> row.id() > id)
                .sorted(java.util.Comparator.comparingLong(UpdateRequest::id)).toList();
    }

    @Override
    public long latestId() {
        return rows.keySet().stream().mapToLong(Long::longValue).max().orElse(0L);
    }

    @Override
    public java.util.List<UpdateRequest> finishedWithin(final java.time.Duration window) {
        final java.time.Instant from = now.minus(window);
        return rows.values().stream()
                .filter(row -> row.finished() != null && row.finished().isAfter(from))
                .sorted(java.util.Comparator.comparingLong(UpdateRequest::id)).toList();
    }

    @Override
    public Optional<UpdateRequest> startCountdown(final long id, final java.time.Duration seconds) {
        final UpdateRequest row = rows.get(id);
        if (row == null || row.status() != UpdateStatus.RUNNING) {
            return Optional.empty();
        }
        final UpdateRequest counting = new UpdateRequest(row.id(), row.kind(), row.status(),
                row.source(), row.requestedBy(), row.requested(), now.plus(seconds), row.started(),
                row.finished(), row.result());
        rows.put(id, counting);
        return Optional.of(counting);
    }

    @Override
    public boolean commitCountdown(final long id) {
        final UpdateRequest row = rows.get(id);
        if (row == null || row.status() != UpdateStatus.RUNNING) {
            return false;
        }
        rows.put(id, new UpdateRequest(row.id(), row.kind(), row.status(), row.source(),
                row.requestedBy(), row.requested(), now, row.started(), row.finished(),
                row.result()));
        return true;
    }

    @Override
    public Optional<UpdateRequest> countingDown() {
        return rows.values().stream()
                .filter(row -> row.status() == UpdateStatus.PENDING
                        || row.status() == UpdateStatus.RUNNING)
                .filter(row -> row.kind() == UpdateKind.RESTART || row.kind() == UpdateKind.UPDATE)
                .filter(row -> row.notBefore().isAfter(now))
                .findFirst();
    }

    @Override
    public Optional<UpdateRequest> cancelCountdown(final String reason) {
        return countingDown().map(row -> {
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
    public int settleOrphans(final String failed) {
        int settled = 0;
        for (final UpdateRequest row : List.copyOf(rows.values())) {
            if (row.status() != UpdateStatus.RUNNING) {
                continue;
            }
            // Every kind, since 2026-09-08. A RESTART used to be closed as DONE here, because a
            // redeploy of the whole project took the updater down mid-call; it does not any more.
            rows.put(row.id(), new UpdateRequest(row.id(), row.kind(), UpdateStatus.FAILED,
                    row.source(), row.requestedBy(), row.requested(), row.notBefore(),
                    row.started(), now, failed));
            settled++;
        }
        return settled;
    }

    @Override
    public java.util.Optional<eu.nordtal.s2.common.update.UpdateRequest> running() {
        return java.util.Optional.empty();
    }
}
