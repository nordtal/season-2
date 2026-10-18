package eu.nordtal.season.database.update;

import static eu.nordtal.season.database.DatabaseMessages.MESSAGES;

import com.google.gson.JsonElement;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.Outcome;
import eu.nordtal.season.database.inbox.Request;
import eu.nordtal.season.database.inbox.Schedule;
import eu.nordtal.season.database.inbox.StewardRequest;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.Refused;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

final class JdbiUpdateDirectory implements UpdateDirectory {

    /** The unique index that allows one open run. */
    private static final String ONE_OPEN = "steward_inbox_one_open";

    private final UpdateDao dao;
    private final JdbiRunRecorder recorder;
    private final Inbox<StewardRequest> inbox;

    JdbiUpdateDirectory(final DataSource dataSource, final BooleanSupplier current) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.dao = Jdbis.over(dataSource).onDemand(UpdateDao.class);
        this.recorder = new JdbiRunRecorder(dataSource, Objects.requireNonNull(current, "current"));
        this.inbox = Inbox.over(dataSource, StewardRequest.TABLE);
    }

    /**
     * Writes a request only when no other run is open and none of its services is held.
     * The unique index refuses a second open run. Holds change only while a run is open.
     */
    @Override
    public UpdateRequest submit(final StewardRequest request, final Actor actor, final @Nullable Duration delay) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(actor, "actor");
        final java.util.List<String> scope = request.services();
        if (request instanceof StewardRequest.Down && !scope.isEmpty()) {
            final java.util.List<String> held = dao.holds().stream()
                    .map(ServiceHold::service)
                    .filter(scope::contains)
                    .toList();
            if (!held.isEmpty()) {
                throw new Refused(UpdateRefusal.ALREADY_HELD, MESSAGES.update().alreadyHeld(List.copyOf(held)));
            }
        }
        // A negative delay means now.
        final Duration wait = delay == null || delay.isNegative() ? Duration.ZERO : delay;
        try {
            return named(inbox.submit(request, actor, Schedule.after(wait)));
        } catch (final RuntimeException refused) {
            if (!String.valueOf(refused.getMessage()).contains(ONE_OPEN)) {
                throw refused;
            }
            final UpdateRequest open = dao.open().orElseThrow(() -> refused);
            throw new Refused(UpdateRefusal.RUN_OPEN, MESSAGES.update().runOpen(open.id(), open.kind(), open.status()));
        }
    }

    @Override
    public UpdateRequest record(
            final StewardRequest request,
            final Actor actor,
            final UpdateStatus status,
            final String result,
            final Duration took) {
        if (status != UpdateStatus.DONE && status != UpdateStatus.FAILED) {
            throw new IllegalArgumentException("A run is written as DONE or FAILED, not as " + status);
        }
        final Outcome outcome =
                status == UpdateStatus.DONE ? Outcome.done(answer(result)) : Outcome.failed(answer(result));
        final UpdateRequest recorded =
                named(inbox.record(request, actor, outcome, took.isNegative() ? Duration.ZERO : took));
        staged(recorded.id(), result);
        return recorded;
    }

    private UpdateRequest named(final Request<StewardRequest> request) {
        recorder.named(request.id(), request.payload().services());
        return run(request);
    }

    private void staged(final long id, final String result) {
        UpdateReports.parse(result).ifPresent(report -> recorder.stage(id, report.stage()));
    }

    private UpdateRequest run(final Request<StewardRequest> request) {
        return dao.find(request.id()).orElseThrow(() -> new IllegalStateException("run " + request.id() + " is gone"));
    }

    /**
     * Returns the answer that the outcome of a run stores, which is its report.
     *
     * @throws IllegalArgumentException for a bare sentence, which no reader could translate
     */
    private static JsonElement answer(final String result) {
        final JsonElement report;
        try {
            report = Json.tree(result);
        } catch (final RuntimeException notJson) {
            throw new IllegalArgumentException("A run's outcome is its report, not a sentence", notJson);
        }
        if (!report.isJsonObject()) {
            throw new IllegalArgumentException("A run's outcome is its report, not a sentence");
        }
        return report;
    }

    @Override
    public java.util.List<String> scopeOf(final long id) {
        return dao.services(id);
    }

    @Override
    public java.util.List<ServiceHold> holds() {
        return dao.holds();
    }

    @Override
    public void hold(final String service, final Actor heldBy, final @Nullable Long requestId) {
        dao.hold(service, heldBy.kind().name(), heldBy.id(), requestId);
    }

    @Override
    public void release(final String service) {
        dao.release(service);
    }

    @Override
    public Optional<StewardRequest> requestOf(final long id) {
        return inbox.find(id).map(Request::payload);
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

    /** Returns whether the report of this row proves that a file was saved. An unreadable report counts as no. */
    private static boolean saved(final UpdateRequest request) {
        return UpdateReports.parse(request.result())
                .filter(report -> report.stage() == UpdateReport.Stage.DONE)
                .filter(UpdateReport::savedSomething)
                .isPresent();
    }

    @Override
    public Optional<UpdateRequest> claimNext() {
        return inbox.claim().map(this::run);
    }

    @Override
    public Optional<UpdateRequest> finish(final long id, final UpdateStatus status, final String result) {
        Objects.requireNonNull(status, "status");
        if (!status.isFinished() || status == UpdateStatus.CANCELLED) {
            throw new IllegalArgumentException("A claimed request finishes as DONE or FAILED, not as " + status);
        }
        final Outcome outcome =
                status == UpdateStatus.DONE ? Outcome.done(answer(result)) : Outcome.failed(answer(result));
        final Optional<UpdateRequest> settled = inbox.settle(id, outcome).map(this::run);
        settled.ifPresent(row -> staged(id, result));
        return settled;
    }

    @Override
    public boolean progress(final long id, final String result) {
        final boolean written = inbox.progress(id, answer(result));
        if (written) {
            staged(id, result);
        }
        return written;
    }

    @Override
    public Optional<UpdateRequest> startCountdown(
            final long id, final Duration length, final java.util.Collection<String> moving) {
        Objects.requireNonNull(length, "length");
        final Optional<UpdateRequest> counting =
                dao.startCountdown(id, Math.max(0L, length.toSeconds()), moving.toArray(String[]::new));
        counting.ifPresent(row -> recorder.moving(id, moving));
        return counting;
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
    public Optional<UpdateRequest> cancelCountdown() {
        final Optional<UpdateRequest> cancelled = dao.cancelCountdown();
        cancelled.ifPresent(row -> recorder.stage(row.id(), UpdateReport.Stage.CANCELLED));
        return cancelled;
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
        return inbox.nextDue();
    }

    @Override
    public int settleOrphans(final MessageRef why, final java.util.function.Predicate<String> stillRunning) {
        Objects.requireNonNull(stillRunning, "stillRunning");
        final List<Long> spared = dao.handed().stream()
                .filter(handed -> stillRunning.test(handed.runner()))
                .map(UpdateDao.Handed::id)
                .toList();
        final int settled = inbox.settleOrphans(failedFor(why), spared);
        recorder.failedStages();
        return settled;
    }

    @Override
    public boolean handOver(final long id, final String runner) {
        return dao.handOver(id, Objects.requireNonNull(runner, "runner")) == 1;
    }

    @Override
    public Optional<String> runnerOf(final long id) {
        return dao.runnerOf(id);
    }

    @Override
    public Optional<String> handedTo() {
        return dao.handed().stream().map(UpdateDao.Handed::runner).findFirst();
    }

    @Override
    public Optional<String> carry(final long id) {
        return inbox.carry(id);
    }

    @Override
    public void putBack(final String row, final MessageRef why) {
        inbox.putBack(row, failedFor(why));
        recorder.failedStages();
    }

    @Override
    public RunRecorder recorder() {
        return recorder;
    }

    /** Returns a failed report with one note that says why. */
    private static UpdateReport failedFor(final MessageRef why) {
        return UpdateReport.at(UpdateReport.Stage.FAILED)
                .withNote(UpdateReport.Note.failed(UpdateReport.Step.RUN, Objects.requireNonNull(why, "why")));
    }
}
