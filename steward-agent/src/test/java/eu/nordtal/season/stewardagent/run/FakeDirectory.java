package eu.nordtal.season.stewardagent.run;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.database.update.UpdateReports;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.messages.MessageRef;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** An {@link UpdateDirectory} that lives in a map, for the tests about the loop of {@link UpdateServer}. */
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

    /** A copy of {@code row} with what a statement changes, and everything it leaves alone kept. */
    private static UpdateRequest copy(
            final UpdateRequest row,
            final UpdateStatus status,
            final Instant countdownEnd,
            final List<String> moving,
            final Instant started,
            final Instant finished,
            final String result) {
        return new UpdateRequest(
                row.id(),
                row.kind(),
                status,
                row.actor(),
                row.requested(),
                row.scheduledFor(),
                countdownEnd,
                moving,
                started,
                finished,
                result);
    }

    @Override
    public UpdateRequest submit(
            final eu.nordtal.season.database.inbox.StewardRequest asked, final Actor actor, final Duration delay) {
        final long id = nextId++;
        final UpdateRequest request = new UpdateRequest(
                id,
                UpdateKind.of(asked),
                UpdateStatus.PENDING,
                actor,
                now,
                now.plus(delay == null ? Duration.ZERO : delay),
                null,
                List.of(),
                null,
                null,
                null);
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
                .filter(row -> !row.scheduledFor().isAfter(now))
                .min((left, right) -> {
                    final int byTime = left.scheduledFor().compareTo(right.scheduledFor());
                    return byTime != 0 ? byTime : Long.compare(left.id(), right.id());
                })
                .map(row -> {
                    // The report is kept, as the real claim keeps it.
                    final UpdateRequest claimed =
                            copy(row, UpdateStatus.RUNNING, row.countdownEnd(), row.moving(), now, null, row.result());
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
        final UpdateRequest done = copy(row, status, row.countdownEnd(), row.moving(), row.started(), now, result);
        rows.put(id, done);
        finished.add(done);
        return Optional.of(done);
    }

    @Override
    public boolean progress(final long id, final String result) {
        // The stage-by-stage writes the real directory makes, recorded so a test can assert progress was reported.
        progressWrites.add(result);
        final UpdateRequest row = rows.get(id);
        return row != null && row.status() == UpdateStatus.RUNNING;
    }

    @Override
    public java.util.List<UpdateRequest> since(final long id) {
        return rows.values().stream()
                .filter(row -> row.id() > id)
                .sorted(java.util.Comparator.comparingLong(UpdateRequest::id))
                .toList();
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
                .sorted(java.util.Comparator.comparingLong(UpdateRequest::id))
                .toList();
    }

    @Override
    public Optional<UpdateRequest> lastSuccessfulBackup(final java.time.Duration within) {
        throw new UnsupportedOperationException("nothing in a run asks whether a backup exists");
    }

    @Override
    public Optional<UpdateRequest> startCountdown(
            final long id, final java.time.Duration seconds, final java.util.Collection<String> moving) {
        final UpdateRequest row = rows.get(id);
        if (row == null || row.status() != UpdateStatus.RUNNING) {
            return Optional.empty();
        }
        final UpdateRequest counting = copy(
                row, row.status(), now.plus(seconds), List.copyOf(moving), row.started(), row.finished(), row.result());
        rows.put(id, counting);
        return Optional.of(counting);
    }

    @Override
    public boolean commitCountdown(final long id) {
        final UpdateRequest row = rows.get(id);
        if (row == null || row.status() != UpdateStatus.RUNNING) {
            return false;
        }
        rows.put(id, copy(row, row.status(), now, row.moving(), row.started(), row.finished(), row.result()));
        return true;
    }

    @Override
    public Optional<UpdateRequest> countingDown() {
        return rows.values().stream()
                .filter(row -> row.status() == UpdateStatus.PENDING || row.status() == UpdateStatus.RUNNING)
                .filter(row -> row.kind() == UpdateKind.RESTART || row.kind() == UpdateKind.UPDATE)
                .filter(row -> row.due().isAfter(now))
                .findFirst();
    }

    @Override
    public Optional<UpdateRequest> cancelCountdown() {
        return countingDown().map(row -> {
            final String report = UpdateReports.toJson(UpdateReports.parse(row.result())
                    .orElseGet(() -> UpdateReport.at(UpdateReport.Stage.CANCELLED))
                    .withStage(UpdateReport.Stage.CANCELLED));
            final UpdateRequest cancelled =
                    copy(row, UpdateStatus.CANCELLED, row.countdownEnd(), row.moving(), null, now, report);
            rows.put(row.id(), cancelled);
            return cancelled;
        });
    }

    @Override
    public Optional<Instant> nextDue() {
        return rows.values().stream()
                .filter(row -> row.status() == UpdateStatus.PENDING)
                .map(UpdateRequest::scheduledFor)
                .min(Instant::compareTo);
    }

    /** request id -> the one-shot it was handed to. */
    private final Map<Long, String> runners = new LinkedHashMap<>();

    @Override
    public boolean handOver(final long id, final String runner) {
        final UpdateRequest row = rows.get(id);
        if (row == null || row.status() != UpdateStatus.RUNNING) {
            return false;
        }
        runners.put(id, runner);
        return true;
    }

    @Override
    public java.util.Optional<String> runnerOf(final long id) {
        final UpdateRequest row = rows.get(id);
        return row == null || row.status() != UpdateStatus.RUNNING
                ? java.util.Optional.empty()
                : java.util.Optional.ofNullable(runners.get(id));
    }

    @Override
    public java.util.Optional<String> handedTo() {
        return runners.keySet().stream()
                .map(this::runnerOf)
                .flatMap(java.util.Optional::stream)
                .findFirst();
    }

    @Override
    public int settleOrphans(final MessageRef why, final java.util.function.Predicate<String> stillRunning) {
        final String failed =
                UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED).withNote(why));
        int settled = 0;
        for (final UpdateRequest row : List.copyOf(rows.values())) {
            if (row.status() != UpdateStatus.RUNNING) {
                continue;
            }
            final String runner = runners.get(row.id());
            if (runner != null && stillRunning.test(runner)) {
                continue;
            }
            // Every kind, a RESTART included, since a redeploy takes steward down mid-call.
            rows.put(
                    row.id(),
                    copy(row, UpdateStatus.FAILED, row.countdownEnd(), row.moving(), row.started(), now, failed));
            settled++;
        }
        return settled;
    }

    @Override
    public java.util.Optional<eu.nordtal.season.database.update.UpdateRequest> running() {
        return java.util.Optional.empty();
    }

    @Override
    public java.util.List<UpdateRequest> recent(final int limit) {
        // Nothing in this fake ever lists: the list is a page in the interface, not a decision anything here makes.
        return java.util.List.of();
    }
}
