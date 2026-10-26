package eu.nordtal.season.discordbot.discord;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.database.update.RunLines;
import eu.nordtal.season.database.update.RunRecord;
import eu.nordtal.season.database.update.RunStage;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.discordbot.AdminLog;
import eu.nordtal.season.discordbot.Mark;
import eu.nordtal.season.discordbot.StoredDocuments;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.messages.spec.Takes;
import eu.nordtal.season.messages.value.Example;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Posts a card in the admin channel, in English, for every update run that {@link #shown} admits.
 *
 * An edit notifies nobody, so steward's alert reports a failed run and takes the place of its card.
 */
@Slf4j
public final class UpdateFeed {

    /** The age limit of a finished run that {@link #start} posts. */
    public static final Duration CATCH_UP = Duration.ofMinutes(12);

    public interface Board {

        /** Posts a card and passes the message id to {@code sentId} when Discord has the message. */
        void post(DocumentRenderer.Drawn card, java.util.function.Consumer<String> sentId);

        void edit(String messageId, DocumentRenderer.Drawn card);

        static Board of(final AdminLog admin) {
            return new Board() {
                @Override
                public void post(final DocumentRenderer.Drawn card, final java.util.function.Consumer<String> sentId) {
                    admin.post(card, sentId);
                }

                @Override
                public void edit(final String messageId, final DocumentRenderer.Drawn card) {
                    admin.edit(messageId, card);
                }
            };
        }
    }

    /**
     * @param by who asked, or {@code null} where the card does not name it
     * @param took how long a finished run took, or {@code null}
     */
    record Run(
            @Example(Mark.PREVIEW) String mark,
            @Takes("admin/run.title") MessageRef title,

            @Takes({"admin/journal.actor", "admin/journal.person"}) @Nullable
            MessageRef by,

            @Nullable @Example("PT4M30S") Duration took) {}

    record Service(
            @Example(Mark.PREVIEW) String mark,
            @Example("smp") String service,
            @Takes("admin/run.step-kind") MessageRef state,

            @Takes({"admin/*", "database/*"}) @Example("admin/run.moved")
            List<MessageRef> changes) {}

    record Failure(
            boolean named,
            @Example("smp") String service,

            @Takes({"admin/*", "database/*"}) @Example("admin/run.exit-code")
            MessageRef what) {}

    static final DocumentSpec<Run> RUN = DocumentSpec.message(
                    StoredDocuments.NAMESPACE,
                    "admin-run",
                    "Admin channel",
                    Run.class,
                    Slot.list("services", Service.class),
                    Slot.list("why", Failure.class),
                    Slot.button("open").optional())
            .titled("Update run");

    private record Drawn(String messageId, @Nullable RunRecord showing) {}

    private final UpdateDirectory updates;
    private final Board board;
    private final DocumentRenderer documents;

    /** The highest request id handled. Only the tick thread writes it. */
    private volatile long lastSeen;

    /** The runs in progress, by request id. */
    private final ConcurrentHashMap<Long, Drawn> drawing = new ConcurrentHashMap<>();

    /** The runs in progress that have no card yet, by request id. */
    private final java.util.Set<Long> held = ConcurrentHashMap.newKeySet();

    /** The card of each failed run, by request id, until its alert takes its place. */
    private final ConcurrentHashMap<Long, String> failed = new ConcurrentHashMap<>();

    private final java.util.concurrent.atomic.AtomicBoolean ticking = new java.util.concurrent.atomic.AtomicBoolean();

    /** Set by every signal. A signal during a pass causes exactly one more pass. */
    private final java.util.concurrent.atomic.AtomicBoolean again = new java.util.concurrent.atomic.AtomicBoolean();

    private final String stewardUrl;

    /** @param stewardUrl Steward's address, which the link on each card starts with. An empty value shows no link. */
    public UpdateFeed(
            final UpdateDirectory updates,
            final Board board,
            final DocumentRenderer documents,
            final String stewardUrl) {
        this.stewardUrl = stewardUrl.endsWith("/") ? stewardUrl.substring(0, stewardUrl.length() - 1) : stewardUrl;
        this.updates = Objects.requireNonNull(updates, "updates");
        this.board = Objects.requireNonNull(board, "board");
        this.documents = Objects.requireNonNull(documents, "documents");
    }

    /** Returns the card of a failed run once, for the alert that takes its place, or {@code null}. */
    public @Nullable String failedCard(final long run) {
        return failed.remove(run);
    }

    /** Starts from the highest request id and posts the runs that finished within {@link #CATCH_UP}. */
    public void start() {
        try {
            final long mark = updates.latestId();
            for (final UpdateRequest request : updates.finishedWithin(CATCH_UP)) {
                // A row with an id above mark finished between the two reads, and tick() posts it.
                if (request.id() > mark || !shown(request)) {
                    continue;
                }
                board.post(card(request), messageId -> ended(request, messageId));
            }
            // A run in progress is registered, so tick() follows it.
            for (final UpdateRequest request : updates.since(0L)) {
                if (request.status().isFinished() || request.id() > mark) {
                    continue;
                }
                follow(request);
            }
            lastSeen = mark;
        } catch (final RuntimeException failure) {
            log.error("Could not read the update history; the feed starts from {}", lastSeen, failure);
        }
    }

    public void tick() {
        if (!ticking.compareAndSet(false, true)) {
            return;
        }
        try {
            pass();
        } finally {
            ticking.set(false);
        }
    }

    /**
     * Runs a pass on {@code worker}. A signal during a pass causes one more pass after it.
     *
     * The flag is released if {@code worker} rejects the pass.
     */
    public void submit(final Executor worker) {
        Objects.requireNonNull(worker, "worker");
        again.set(true);
        if (!ticking.compareAndSet(false, true)) {
            return;
        }
        try {
            worker.execute(() -> {
                try {
                    while (again.getAndSet(false)) {
                        pass();
                    }
                } catch (final RuntimeException failure) {
                    log.error("The update feed pass failed; it runs again on the next signal", failure);
                } finally {
                    ticking.set(false);
                }
                // A signal between the last pass and the release found the flag set.
                if (again.get()) {
                    submit(worker);
                }
            });
        } catch (final RuntimeException rejected) {
            ticking.set(false);
            throw rejected;
        }
    }

    private void pass() {
        final List<UpdateRequest> fresh;
        try {
            fresh = updates.since(lastSeen);
        } catch (final RuntimeException failure) {
            log.warn("Could not read new update requests; nothing was posted this pass", failure);
            return;
        }

        for (final UpdateRequest request : fresh) {
            lastSeen = Math.max(lastSeen, request.id());
            follow(request);
        }
        release();
        redraw();
    }

    /** Posts a run that is shown and follows it until it ends, or holds a run in progress that is not shown yet. */
    private void follow(final UpdateRequest request) {
        if (!shown(request)) {
            if (!request.status().isFinished()) {
                held.add(request.id());
            }
            return;
        }
        final boolean over = request.status().isFinished();
        board.post(card(request), messageId -> {
            if (over) {
                ended(request, messageId);
                return;
            }
            drawing.put(request.id(), new Drawn(messageId, shownOf(request)));
        });
    }

    private void ended(final UpdateRequest request, final String messageId) {
        if (request.status() == UpdateStatus.FAILED) {
            failed.put(request.id(), messageId);
        }
    }

    /** Reads the held runs again and posts each one that is shown now. */
    private void release() {
        for (final long id : List.copyOf(held)) {
            final Optional<UpdateRequest> row;
            try {
                row = updates.find(id);
            } catch (final RuntimeException failure) {
                log.warn("Could not re-read update request {} for the admin channel", id, failure);
                continue;
            }
            if (row.isEmpty() || shown(row.get()) || row.get().status().isFinished()) {
                held.remove(id);
                row.ifPresent(this::follow);
            }
        }
    }

    /**
     * Returns whether a run gets a card now.
     *
     * Not a start-up install that went well, nor an update that is still resolving or found nothing to do.
     */
    static boolean shown(final UpdateRequest request) {
        return switch (request.kind()) {
            case INSTALL -> request.status() == UpdateStatus.FAILED;
            case UPDATE ->
                switch (stageOf(request)) {
                    case RESOLVING -> request.status().isFinished();
                    case NOTHING_TO_DO -> false;
                    default -> true;
                };
            default -> true;
        };
    }

    /** Reads the runs in progress again and edits the message of each run that changed. */
    private void redraw() {
        for (final Map.Entry<Long, Drawn> entry : drawing.entrySet()) {
            final long id = entry.getKey();
            final Optional<UpdateRequest> row;
            try {
                row = updates.find(id);
            } catch (final RuntimeException failure) {
                log.warn("Could not re-read update request {} for the admin channel", id, failure);
                continue;
            }
            if (row.isEmpty()) {
                drawing.remove(id);
                continue;
            }
            final UpdateRequest request = row.get();
            final Drawn drawn = entry.getValue();
            final RunRecord showing = shownOf(request);
            // Discord rate-limits edits.
            if (!java.util.Objects.equals(drawn.showing(), showing)) {
                board.edit(drawn.messageId(), card(request));
                drawing.put(id, new Drawn(drawn.messageId(), showing));
            }
            if (request.status().isFinished()) {
                drawing.remove(id);
                ended(request, drawn.messageId());
            }
        }
    }

    /** Draws one run from its rows. */
    private DocumentRenderer.Drawn card(final UpdateRequest request) {
        return card(
                updates.recordOf(request.id()),
                request,
                documents,
                true,
                stewardUrl.isBlank() ? null : stewardUrl + request.page());
    }

    /** What a card shows of a run: its steps, with the stage of the row it was read with. */
    private RunRecord shownOf(final UpdateRequest request) {
        return new RunRecord(request.stage(), updates.recordOf(request.id()).steps());
    }

    /** Returns the stage of a run. A row no statement gave one yet is resolving. */
    private static RunStage stageOf(final UpdateRequest request) {
        return request.stage() == null ? RunStage.RESOLVING : request.stage();
    }

    /**
     * Draws one run: the kind and stage as title, the outcome as mark and one line per service its steps name.
     *
     * @param context whether to name who asked
     * @param page the run's page in Steward, which the button opens, or {@code null} for no button
     */
    static DocumentRenderer.Drawn card(
            final RunRecord record,
            final UpdateRequest request,
            final DocumentRenderer documents,
            final boolean context,
            final @Nullable String page) {
        final RunStage stage = stageOf(request);
        final Map<String, List<RunStep.Row>> byService = new LinkedHashMap<>();
        final List<Failure> why = new ArrayList<>();
        for (final RunStep.Row step : record.steps()) {
            if (step.service() != null) {
                byService
                        .computeIfAbsent(step.service(), service -> new ArrayList<>())
                        .add(step);
            }
            // The card is edited once the run settles, and only a failed run says why.
            if (stage == RunStage.FAILED && step.outcome() == RunStep.Outcome.FAILED && step.reason() != null) {
                final String service = step.service();
                why.add(new Failure(
                        service != null,
                        service == null ? "" : service,
                        TEXTS.run().reason(step.reason())));
            }
        }
        final Instant requested = request.requested();
        final Instant finished = request.finished();
        return documents.render(
                RUN,
                new Run(
                        glance(stage).symbol(),
                        TEXTS.run().title(request.kind(), stage),
                        context ? TEXTS.journal().who(request.actor()) : null,
                        finished != null && requested != null ? Duration.between(requested, finished) : null),
                Map.of(
                        "services",
                        DocumentRenderer.FilledList.of(byService.entrySet().stream()
                                .map(service -> line(service.getKey(), service.getValue()))
                                .toList()),
                        "why",
                        DocumentRenderer.FilledList.of(why),
                        "open",
                        page == null ? new DocumentRenderer.FilledNothing() : DocumentRenderer.FilledButton.link(page)),
                Locales.DEFAULT);
    }

    /** One service: the worst of its steps as mark, its latest step, and the artefacts of the latest step with any. */
    private static Service line(final String service, final List<RunStep.Row> steps) {
        final RunStep.Row latest = steps.getLast();
        final List<MessageRef> changes = steps.reversed().stream()
                .filter(step -> !step.changes().isEmpty())
                .findFirst()
                .map(step -> step.changes().stream().map(RunLines::change).toList())
                .orElse(List.of());
        return new Service(marker(steps).symbol(), service, TEXTS.run().stepKind(latest.step()), changes);
    }

    private static Mark marker(final List<RunStep.Row> steps) {
        if (steps.stream().anyMatch(step -> step.outcome() == RunStep.Outcome.FAILED)) {
            return Mark.FAILED;
        }
        if (steps.stream().anyMatch(step -> step.outcome() == null)) {
            return Mark.WORKING;
        }
        if (steps.stream().anyMatch(step -> step.outcome() == RunStep.Outcome.WARNING)) {
            return Mark.WARNING;
        }
        return steps.stream().allMatch(step -> step.outcome() == RunStep.Outcome.SKIPPED) ? Mark.UNCHANGED : Mark.DONE;
    }

    /** Returns the mark in front of a stage. It is the only place that shows the outcome. */
    private static Mark glance(final RunStage stage) {
        return switch (stage) {
            case RESOLVING -> Mark.LOOKING;
            case PLANNED -> Mark.PLANNED;
            case COUNTDOWN -> Mark.WAITING;
            case STOPPING, BACKING_UP, INSTALLING, STARTING, VERIFYING -> Mark.WORKING;
            case DONE, NOTHING_TO_DO -> Mark.DONE;
            case FAILED -> Mark.FAILED;
            case CANCELLED -> Mark.STOPPED;
        };
    }
}
