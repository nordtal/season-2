package eu.nordtal.season.discordbot.discord;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.database.update.UpdateReports;
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
import java.util.List;
import java.util.Locale;
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
 * An edit notifies nobody, so steward's alert reports a failed run.
 */
@Slf4j
public final class UpdateFeed {

    private static final java.util.regex.Pattern SHA1 = java.util.regex.Pattern.compile("[0-9a-f]{40}");

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

            @Nullable Duration took) {}

    record Service(
            @Example(Mark.PREVIEW) String mark,
            @Example("smp") String service,
            @Takes("admin/run.state") MessageRef state,
            List<MessageRef> changes) {}

    record Failure(
            boolean named,
            @Example("smp") String service,

            @Takes({"admin/*", "database/*"}) @Example("admin/run.no-build")
            MessageRef what) {}

    static final DocumentSpec<Run> RUN = DocumentSpec.message(
            StoredDocuments.NAMESPACE,
            "admin-run",
            "Admin channel",
            Run.class,
            Slot.list("services", Service.class),
            Slot.list("why", Failure.class),
            Slot.button("open").optional());

    private record Drawn(String messageId, @Nullable String showing) {}

    private final UpdateDirectory updates;
    private final Board board;
    private final DocumentRenderer documents;

    /** The highest request id handled. Only the tick thread writes it. */
    private volatile long lastSeen;

    /** The runs in progress, by request id. */
    private final ConcurrentHashMap<Long, Drawn> drawing = new ConcurrentHashMap<>();

    /** The runs in progress that have no card yet, by request id. */
    private final java.util.Set<Long> held = ConcurrentHashMap.newKeySet();

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

    /** Starts from the highest request id and posts the runs that finished within {@link #CATCH_UP}. */
    public void start() {
        try {
            final long mark = updates.latestId();
            for (final UpdateRequest request : updates.finishedWithin(CATCH_UP)) {
                // A row with an id above mark finished between the two reads, and tick() posts it.
                if (request.id() > mark || !shown(request)) {
                    continue;
                }
                board.post(card(request), messageId -> {});
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
                return;
            }
            drawing.put(request.id(), new Drawn(messageId, request.result()));
        });
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
                switch (report(request).stage()) {
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
            // Discord rate-limits edits.
            if (!java.util.Objects.equals(drawn.showing(), request.result())) {
                board.edit(drawn.messageId(), card(request));
                drawing.put(id, new Drawn(drawn.messageId(), request.result()));
            }
            if (request.status().isFinished()) {
                drawing.remove(id);
            }
        }
    }

    /** Draws one run. A row with no parsable report has the stage {@code RESOLVING}. */
    private DocumentRenderer.Drawn card(final UpdateRequest request) {
        return card(
                report(request), request, documents, true, stewardUrl.isBlank() ? null : stewardUrl + request.page());
    }

    private static UpdateReport report(final UpdateRequest request) {
        return UpdateReports.parse(request.result()).orElseGet(() -> UpdateReport.at(UpdateReport.Stage.RESOLVING));
    }

    /**
     * Draws one run: the kind and stage as title, the outcome as mark and one line per service.
     *
     * @param context whether to name who asked
     * @param page the run's page in Steward, which the button opens, or {@code null} for no button
     */
    static DocumentRenderer.Drawn card(
            final UpdateReport report,
            final UpdateRequest request,
            final DocumentRenderer documents,
            final boolean context,
            final @Nullable String page) {
        final List<Failure> why = new ArrayList<>();
        for (final UpdateReport.Failure said : report.failures()) {
            // A multi-line text is not shown.
            final String shown =
                    documents.messages().format(Locales.DEFAULT, said.what()).strip();
            if (!shown.isBlank() && shown.indexOf('\n') < 0) {
                final String service = said.service();
                why.add(new Failure(service != null, service == null ? "" : service, said.what()));
            }
        }
        final Instant requested = request.requested();
        final Instant finished = request.finished();
        return documents.render(
                RUN,
                new Run(
                        glance(report.stage()).symbol(),
                        TEXTS.run().title(request.kind(), report.stage()),
                        context ? TEXTS.journal().who(request.actor()) : null,
                        finished != null && requested != null ? Duration.between(requested, finished) : null),
                Map.of(
                        "services",
                        DocumentRenderer.FilledList.of(report.services().stream()
                                .map(line -> new Service(
                                        marker(line.state()).symbol(),
                                        line.service(),
                                        TEXTS.run().state(line.state()),
                                        changes(line, request.kind())))
                                .toList()),
                        "why",
                        DocumentRenderer.FilledList.of(why),
                        "open",
                        page == null ? new DocumentRenderer.FilledNothing() : DocumentRenderer.FilledButton.link(page)),
                Locales.DEFAULT);
    }

    /** Returns the changes of a service. A told change named after the run's kind has no artefact name. */
    private static List<MessageRef> changes(final UpdateReport.ServiceLine line, final UpdateKind kind) {
        final List<MessageRef> changes = new ArrayList<>();
        for (final UpdateReport.Change change : line.changes()) {
            final MessageRef shown = switch (change.state()) {
                case UNSUPPORTED -> TEXTS.run().noBuild();
                case MOVING -> moving(change);
            };
            changes.add(
                    change.told() != null
                                    && change.artefact().equals(kind.name().toLowerCase(Locale.ROOT))
                            ? shown
                            : TEXTS.run().change(change.artefact(), shown));
        }
        return changes;
    }

    /** Returns the text of a moving change: its told text, else its version or the old and new version. */
    private static MessageRef moving(final UpdateReport.Change change) {
        final MessageRef told = change.told();
        if (told != null) {
            return told;
        }
        final String from = change.from();
        return from == null ? TEXTS.run().version(change.to()) : TEXTS.run().moved(version(from), change.to());
    }

    /** Returns the version, with a SHA-1 cut to its first seven characters. */
    private static String version(final String version) {
        return SHA1.matcher(version).matches() ? version.substring(0, 7) : version;
    }

    private static Mark marker(final UpdateReport.State state) {
        return switch (state) {
            case UNCHANGED -> Mark.UNCHANGED;
            case PLANNED -> Mark.WAITING;
            case STOPPED, INSTALLED, STARTING -> Mark.WORKING;
            case HEALTHY, SAVED, RESTORED -> Mark.DONE;
            case FAILED -> Mark.FAILED;
        };
    }

    /** Returns the mark in front of a stage. It is the only place that shows the outcome. */
    private static Mark glance(final UpdateReport.Stage stage) {
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
