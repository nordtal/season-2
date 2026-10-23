package eu.nordtal.season.database.update;

import static eu.nordtal.season.database.DatabaseMessages.MESSAGES;

import com.google.gson.JsonObject;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.database.DatabaseJson;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.Outcome;
import eu.nordtal.season.database.inbox.Request;
import eu.nordtal.season.database.inbox.Schedule;
import eu.nordtal.season.database.inbox.StewardRequest;
import eu.nordtal.season.messages.Refused;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.Nullable;

final class JdbiUpdateDirectory implements UpdateDirectory {

    /** The unique index that allows one open run. */
    private static final String ONE_OPEN = "steward_inbox_one_open";

    private final Jdbi jdbi;
    private final UpdateDao dao;
    private final JdbiRunRecorder recorder;
    private final RunStepDao steps;
    private final Inbox<StewardRequest> inbox;
    private final BooleanSupplier current;

    JdbiUpdateDirectory(final DataSource dataSource, final BooleanSupplier current) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbi = Jdbis.over(dataSource);
        this.dao = jdbi.onDemand(UpdateDao.class);
        this.steps = jdbi.onDemand(RunStepDao.class);
        this.current = current;
        this.recorder = new JdbiRunRecorder(dataSource, Objects.requireNonNull(current, "current"));
        this.inbox = Inbox.over(dataSource, StewardRequest.TABLE);
    }

    /**
     * Writes a request only when no other run is open and none of its services is held.
     * The unique index refuses a second open run. Holds change only while a run is open.
     */
    @Override
    public UpdateRequest submit(
            final StewardRequest request,
            final @Nullable List<String> services,
            final Actor actor,
            final @Nullable Duration delay) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(actor, "actor");
        final List<String> scope = UpdateDirectory.cleaned(services);
        if (request instanceof StewardRequest.RemovePlugin && scope.size() != 1) {
            throw new IllegalArgumentException("a plugin is removed from exactly one server, not " + scope);
        }
        if (request instanceof StewardRequest.Down && !scope.isEmpty()) {
            final List<String> held = dao.holds().stream()
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
            // The services are written with the row, so the agent never claims a run without its scope.
            return run(jdbi.inTransaction(handle -> {
                final long id = inbox.submitWithin(handle, request, actor, Schedule.after(wait))
                        .id();
                handle.attach(UpdateDao.class).named(id, scope.toArray(String[]::new));
                return id;
            }));
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
            final List<String> services,
            final Actor actor,
            final UpdateStatus status,
            final Duration took) {
        if (status != UpdateStatus.DONE && status != UpdateStatus.FAILED) {
            throw new IllegalArgumentException("A run is written as DONE or FAILED, not as " + status);
        }
        final Outcome outcome = status == UpdateStatus.DONE ? Outcome.done(null) : Outcome.failed(null);
        final Request<StewardRequest> recorded =
                inbox.record(request, actor, outcome, took.isNegative() ? Duration.ZERO : took);
        dao.named(recorded.id(), UpdateDirectory.cleaned(services).toArray(String[]::new));
        dao.recorded(recorded.id(), (status == UpdateStatus.DONE ? RunStage.DONE : RunStage.FAILED).name());
        return run(recorded.id());
    }

    private UpdateRequest run(final long id) {
        return dao.find(id).orElseThrow(() -> new IllegalStateException("run " + id + " is gone"));
    }

    @Override
    public List<String> scopeOf(final long id) {
        return current.getAsBoolean() ? dao.scope(id) : dao.payloadScope(id);
    }

    @Override
    public List<ServiceHold> holds() {
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
        if (current.getAsBoolean()) {
            return inbox.find(id).map(Request::payload);
        }
        // shortcut: a schema before the rows of a request still holds it as JSON; the next release drops this.
        return dao.payload(id).map(row -> DatabaseJson.decode(row.payload(), kindClass(row.kind())));
    }

    private static Class<? extends StewardRequest> kindClass(final String kind) {
        for (final Class<?> type : StewardRequest.class.getPermittedSubclasses()) {
            final Class<? extends StewardRequest> request = type.asSubclass(StewardRequest.class);
            if (StewardRequest.TABLE.kindOf(request).equals(kind)) {
                return request;
            }
        }
        throw new IllegalStateException("steward_inbox holds a kind this build does not know: " + kind);
    }

    @Override
    public Optional<UpdateRequest> find(final long id) {
        return dao.find(id);
    }

    @Override
    public List<UpdateRequest> recent(final int limit) {
        return dao.recent(Math.max(1, limit));
    }

    @Override
    public List<UpdateRequest> since(final long id) {
        return dao.since(id);
    }

    @Override
    public long latestId() {
        return dao.latestId();
    }

    @Override
    public List<UpdateRequest> finishedWithin(final Duration window) {
        Objects.requireNonNull(window, "window");
        return dao.finishedWithin(Math.max(0L, window.toSeconds()));
    }

    @Override
    public Optional<UpdateRequest> lastSuccessfulBackup(final Duration within) {
        Objects.requireNonNull(within, "within");
        return dao.lastSavingBackup(Math.max(0L, within.toSeconds()));
    }

    @Override
    public Optional<UpdateRequest> claimNext() {
        return inbox.claim().map(request -> run(request.id()));
    }

    /** Writes the stage first, so a reader woken by the settled row finds the stage it ended in. */
    @Override
    public Optional<UpdateRequest> finish(final long id, final RunStage stage) {
        if (!Objects.requireNonNull(stage, "stage").isFinished() || stage == RunStage.CANCELLED) {
            throw new IllegalArgumentException(
                    "A claimed request finishes done, with nothing to do or failed, not " + stage);
        }
        if (!progress(id, stage)) {
            return Optional.empty();
        }
        return inbox.settle(id, stage == RunStage.FAILED ? Outcome.failed(null) : Outcome.done(null))
                .map(row -> run(id));
    }

    @Override
    public boolean progress(final long id, final RunStage stage) {
        return dao.progress(id, Objects.requireNonNull(stage, "stage").name()).isPresent();
    }

    @Override
    public Optional<UpdateRequest> startCountdown(
            final long id, final Duration length, final java.util.Collection<String> moving) {
        Objects.requireNonNull(length, "length");
        return dao.startCountdown(id, Math.max(0L, length.toSeconds()), moving.toArray(String[]::new))
                .map(this::run);
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
        return dao.cancelCountdown().map(this::run);
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
    public int settleOrphans(final java.util.function.Predicate<String> stillRunning) {
        Objects.requireNonNull(stillRunning, "stillRunning");
        final List<Long> spared = dao.handed().stream()
                .filter(handed -> stillRunning.test(handed.runner()))
                .map(UpdateDao.Handed::id)
                .toList();
        final int settled = inbox.settleOrphans(null, spared);
        recorder.failed();
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
        return inbox.carry(id).map(row -> {
            final JsonObject carried = new JsonObject();
            carried.add("row", Json.tree(row));
            carried.add("services", Json.tree(steps.services(id)));
            return carried.toString();
        });
    }

    @Override
    public void putBack(final String carried) {
        final JsonObject row =
                Json.tree(Objects.requireNonNull(carried, "carried")).getAsJsonObject();
        inbox.putBack(row.get("row").toString());
        steps.services(row.get("services").toString());
        recorder.failed();
    }

    @Override
    public RunRecorder recorder() {
        return recorder;
    }

    @Override
    public RunRecord recordOf(final long run) {
        return recorder.record(run);
    }

    @Override
    public List<RunProgress.Step> stepsOf(final UpdateRequest run) {
        return recorder.steps(run.id());
    }
}
