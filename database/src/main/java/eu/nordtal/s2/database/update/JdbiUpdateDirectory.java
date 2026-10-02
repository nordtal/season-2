package eu.nordtal.s2.database.update;

import static eu.nordtal.s2.database.DatabaseMessages.MESSAGES;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import eu.nordtal.s2.common.json.Json;
import eu.nordtal.s2.database.Actor;
import eu.nordtal.s2.database.Jdbis;
import eu.nordtal.s2.database.inbox.Inbox;
import eu.nordtal.s2.database.inbox.Outcome;
import eu.nordtal.s2.database.inbox.Request;
import eu.nordtal.s2.database.inbox.Schedule;
import eu.nordtal.s2.database.inbox.StewardRequest;
import eu.nordtal.s2.messages.Refused;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/** The only implementation of {@link UpdateDirectory}; it borrows the pool and owns nothing. */
final class JdbiUpdateDirectory implements UpdateDirectory {

    /** The unique index that keeps a second run from being asked for while one is open. */
    private static final String ONE_OPEN = "steward_inbox_one_open";

    private final UpdateDao dao;
    private final Inbox<StewardRequest> inbox;

    JdbiUpdateDirectory(final DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.dao = Jdbis.over(dataSource).onDemand(UpdateDao.class);
        this.inbox = Inbox.over(dataSource, StewardRequest.TABLE);
    }

    /**
     * Writes a request only when no other run is open and none of its services is held.
     * The database refuses a second open run by its unique index; holds only change while a run is open.
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
                throw new Refused(UpdateRefusal.ALREADY_HELD, MESSAGES.update().alreadyHeld(String.join(", ", held)));
            }
        }
        // Clamped rather than rejected: a delay computed from two disagreeing clocks means now.
        final Duration wait = delay == null || delay.isNegative() ? Duration.ZERO : delay;
        try {
            return run(inbox.submit(request, actor, Schedule.after(wait)));
        } catch (final RuntimeException refused) {
            if (!String.valueOf(refused.getMessage()).contains(ONE_OPEN)) {
                throw refused;
            }
            final UpdateRequest open = dao.open().orElseThrow(() -> refused);
            throw new Refused(
                    UpdateRefusal.RUN_OPEN,
                    MESSAGES.update()
                            .runOpen(
                                    open.id(), open.kind(), open.status().name().toLowerCase(Locale.ROOT)));
        }
    }

    /** Returns the run as the table holds it now, which the inbox's own row does not carry all of. */
    private UpdateRequest run(final Request<StewardRequest> request) {
        return dao.find(request.id()).orElseThrow(() -> new IllegalStateException("run " + request.id() + " is gone"));
    }

    /** Returns the answer a run's outcome stores: its report as JSON, or a plain reason as a JSON string. */
    private static JsonElement answer(final String result) {
        try {
            final JsonElement report = Json.tree(result);
            return report.isJsonObject() ? report : new JsonPrimitive(result);
        } catch (final RuntimeException notJson) {
            return new JsonPrimitive(result);
        }
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

    /** Returns the services with blanks and repeats dropped; empty is the whole network. */
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

    /** Returns whether this row's report proves a file was saved; an unreadable report counts as no. */
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
        return inbox.settle(id, outcome).map(this::run);
    }

    @Override
    public boolean progress(final long id, final String result) {
        return inbox.progress(id, answer(result));
    }

    @Override
    public Optional<UpdateRequest> startCountdown(
            final long id, final Duration length, final java.util.Collection<String> moving) {
        Objects.requireNonNull(length, "length");
        return dao.startCountdown(id, Math.max(0L, length.toSeconds()), moving.toArray(String[]::new));
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
        return inbox.nextDue();
    }

    @Override
    public int settleOrphans(final String failed, final java.util.function.Predicate<String> stillRunning) {
        Objects.requireNonNull(stillRunning, "stillRunning");
        final List<Long> spared = dao.handed().stream()
                .filter(handed -> stillRunning.test(handed.runner()))
                .map(UpdateDao.Handed::id)
                .toList();
        return inbox.settleOrphans(answer(failed), spared);
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
    public Optional<String> carry(final long id) {
        return inbox.carry(id);
    }

    @Override
    public void putBack(final String row, final String failed) {
        inbox.putBack(row, answer(failed));
    }
}
