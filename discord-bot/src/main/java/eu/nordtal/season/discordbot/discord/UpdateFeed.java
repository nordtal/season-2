package eu.nordtal.season.discordbot.discord;

import static eu.nordtal.season.database.AdminTexts.TEXTS;
import static eu.nordtal.season.discordbot.AccessMessages.MESSAGES;

import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.database.update.UpdateReports;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.discordbot.AdminLog;
import eu.nordtal.season.discordbot.LayoutRenderer;
import eu.nordtal.season.discordbot.Mark;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.Tone;
import eu.nordtal.season.messages.spec.layout.Element;
import eu.nordtal.season.messages.spec.layout.Layout;
import eu.nordtal.season.messages.spec.layout.Line;
import eu.nordtal.season.messages.spec.layout.Part;
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
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import org.jspecify.annotations.Nullable;

/**
 * Draws every run in the admin channel, in English, but a start-up install that went well.
 *
 * An edit notifies nobody, so a failed run reaches the admins as steward's alert, not from here.
 */
@Slf4j
public final class UpdateFeed {

    private static final java.util.regex.Pattern SHA1 = java.util.regex.Pattern.compile("[0-9a-f]{40}");

    /** How far back a start posts runs that ended while this bot was down. */
    public static final Duration CATCH_UP = Duration.ofMinutes(12);

    /** Where a run is drawn; {@link #of(AdminLog)} is the only implementation that ships. */
    public interface Board {

        /** Posts a card and hands over the message id once Discord has it. */
        void post(LayoutRenderer.Drawn card, java.util.function.Consumer<String> sentId);

        void edit(String messageId, LayoutRenderer.Drawn card);

        static Board of(final AdminLog admin) {
            return new Board() {
                @Override
                public void post(final LayoutRenderer.Drawn card, final java.util.function.Consumer<String> sentId) {
                    admin.post(card, sentId);
                }

                @Override
                public void edit(final String messageId, final LayoutRenderer.Drawn card) {
                    admin.edit(messageId, card);
                }
            };
        }
    }

    /** A run: its mark and title, who asked and how long it took, a line per service and per failure, its page. */
    static final Layout RUN = Layout.discord(
            "access",
            "admin-run",
            "Admin channel",
            List.of(
                    Element.list("by", "access/admin.by"),
                    Element.list("took", "access/admin.duration"),
                    Element.list("services", "access/admin.service"),
                    Element.list("why", "access/admin.failure"),
                    Element.button("open", "admin/run.open")),
            Part.container(
                    Tone.BRAND,
                    Part.text(Line.title("access/admin.title")),
                    Part.text(Line.list("by"), Line.list("took")),
                    Part.text(Line.heading("admin/run.services"), Line.list("services")),
                    Part.text(Line.heading("admin/run.why"), Line.list("why"))),
            Part.row("open"));

    private record Drawn(String messageId, @Nullable String showing) {}

    private final UpdateDirectory updates;
    private final Board board;
    private final LayoutRenderer layouts;

    /** The highest id already handled; only the tick thread writes it. */
    private volatile long lastSeen;

    /** The runs still moving, by request id; the message id arrives later on a JDA thread. */
    private final ConcurrentHashMap<Long, Drawn> drawing = new ConcurrentHashMap<>();

    /** Whether a pass is running; see {@link #submit} for why this is a flag and not a lock. */
    private final java.util.concurrent.atomic.AtomicBoolean ticking = new java.util.concurrent.atomic.AtomicBoolean();

    /** Set by every signal, so one that arrives during a pass is followed by exactly one more. */
    private final java.util.concurrent.atomic.AtomicBoolean again = new java.util.concurrent.atomic.AtomicBoolean();

    private final String stewardUrl;

    /** @param stewardUrl Steward's address, which each card links its run's page below; empty links nothing */
    public UpdateFeed(
            final UpdateDirectory updates, final Board board, final LayoutRenderer layouts, final String stewardUrl) {
        this.stewardUrl = stewardUrl.endsWith("/") ? stewardUrl.substring(0, stewardUrl.length() - 1) : stewardUrl;
        this.updates = Objects.requireNonNull(updates, "updates");
        this.board = Objects.requireNonNull(board, "board");
        this.layouts = Objects.requireNonNull(layouts, "layouts");
    }

    /** Starts from the highest id, posting finished runs inside {@link #CATCH_UP} once so a restart loses none. */
    public void start() {
        try {
            final long mark = updates.latestId();
            for (final UpdateRequest request : updates.finishedWithin(CATCH_UP)) {
                // A row with id > mark finished between the two reads above; tick() finds it.
                if (request.id() > mark || !shown(request)) {
                    continue;
                }
                // Posted and forgotten: it is over.
                board.post(card(request), messageId -> {});
            }
            // A run still going falls through both loops above; registering it here makes tick() follow it.
            for (final UpdateRequest request : updates.since(0L)) {
                if (request.status().isFinished() || request.id() > mark) {
                    continue;
                }
                board.post(
                        card(request), messageId -> drawing.put(request.id(), new Drawn(messageId, request.result())));
            }
            lastSeen = mark;
        } catch (final RuntimeException failure) {
            // Not fatal: the feed starts from whatever it managed to read.
            log.error("Could not read the update history; the feed starts from {}", lastSeen, failure);
        }
    }

    /** Runs one pass on the calling thread, unless one is running. */
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
     * Hands a pass to {@code worker} on every update signal; one arriving during a pass runs one more after it.
     *
     * The flag is released if the submission is rejected.
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
                // A signal between the last pass and the release above found the flag taken.
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
            if (!shown(request)) {
                continue;
            }
            final boolean over = request.status().isFinished();
            board.post(card(request), messageId -> {
                if (over) {
                    return;
                }
                // Only now is there a message id to edit.
                drawing.put(request.id(), new Drawn(messageId, request.result()));
            });
        }
        redraw();
    }

    /** Whether a run is drawn here: every run but a start-up install that went well, which is written settled. */
    static boolean shown(final UpdateRequest request) {
        return request.kind() != UpdateKind.INSTALL || request.status() == UpdateStatus.FAILED;
    }

    /** Re-reads the runs still moving and rewrites their messages when they have changed. */
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
                // Deleted by hand: nothing to draw.
                drawing.remove(id);
                continue;
            }
            final UpdateRequest request = row.get();
            final Drawn drawn = entry.getValue();
            // Discord rate-limits edits, and this polls every two seconds.
            if (!java.util.Objects.equals(drawn.showing(), request.result())) {
                board.edit(drawn.messageId(), card(request));
                drawing.put(id, new Drawn(drawn.messageId(), request.result()));
            }
            if (request.status().isFinished()) {
                drawing.remove(id);
            }
        }
    }

    /** Draws one run; a row with no parsable report reads as {@code RESOLVING}. */
    private LayoutRenderer.Drawn card(final UpdateRequest request) {
        final UpdateReport report =
                UpdateReports.parse(request.result()).orElseGet(() -> UpdateReport.at(UpdateReport.Stage.RESOLVING));
        return card(report, request, layouts, true, stewardUrl.isBlank() ? null : stewardUrl + request.page());
    }

    /**
     * Draws one run as data: its kind and stage as title, the outcome as its mark, one line per service.
     *
     * @param context whether to say who asked; only the admin channel's feed does
     * @param page    the run's page in Steward, which its button opens, or {@code null} for no button
     */
    static LayoutRenderer.Drawn card(
            final UpdateReport report,
            final UpdateRequest request,
            final LayoutRenderer layouts,
            final boolean context,
            final @Nullable String page) {
        final List<Map<String, Object>> why = new ArrayList<>();
        for (final UpdateReport.Failure said : report.failures()) {
            // A multi-line text only reads in monospace and repeats the lines.
            final String shown =
                    layouts.messages().format(Locales.DEFAULT, said.what()).strip();
            if (!shown.isBlank() && shown.indexOf('\n') < 0) {
                final String service = said.service();
                why.add(MESSAGES.admin()
                        .failure(service != null, service == null ? "" : service, said.what())
                        .args());
            }
        }
        final Instant requested = request.requested();
        final Instant finished = request.finished();
        return layouts.render(
                RUN,
                LayoutRenderer.Filling.of(
                        Map.of(
                                "by",
                                new LayoutRenderer.FilledList(
                                        context
                                                ? List.of(MESSAGES.admin()
                                                        .by(TEXTS.journal().who(request.actor()))
                                                        .args())
                                                : List.of()),
                                "took",
                                new LayoutRenderer.FilledList(
                                        finished != null && requested != null
                                                ? List.of(MESSAGES.admin()
                                                        .duration(Duration.between(requested, finished))
                                                        .args())
                                                : List.of()),
                                "services",
                                new LayoutRenderer.FilledList(report.services().stream()
                                        .map(line -> MESSAGES.admin()
                                                .service(
                                                        marker(line.state()).symbol(),
                                                        line.service(),
                                                        TEXTS.run().state(line.state()),
                                                        changes(line, request.kind()))
                                                .args())
                                        .toList()),
                                "why",
                                new LayoutRenderer.FilledList(why),
                                "open",
                                page == null
                                        ? new LayoutRenderer.FilledNothing()
                                        : new LayoutRenderer.FilledButton(page, ButtonStyle.LINK)),
                        MESSAGES.admin()
                                .title(
                                        glance(report.stage()).symbol(),
                                        TEXTS.run().title(request.kind(), report.stage()))),
                Locales.DEFAULT);
    }

    /** What changed of a service; a told change named after the run's kind leaves out the name the title says. */
    private static List<MessageRef> changes(final UpdateReport.ServiceLine line, final UpdateKind kind) {
        final List<MessageRef> changes = new ArrayList<>();
        for (final UpdateReport.Change change : line.changes()) {
            final MessageRef shown = switch (change.state()) {
                // No build for this Minecraft version, which stops no server.
                case UNSUPPORTED -> TEXTS.run().noBuild();
                case MOVING -> moving(change);
            };
            changes.add(
                    change.told() != null
                                    && change.artefact().equals(kind.name().toLowerCase(Locale.ROOT))
                            ? shown
                            : MESSAGES.admin().change(change.artefact(), shown));
        }
        return changes;
    }

    /** A change that moves: in words when no version names it, else its version or the pair of them. */
    private static MessageRef moving(final UpdateReport.Change change) {
        final MessageRef told = change.told();
        if (told != null) {
            return told;
        }
        final String from = change.from();
        return from == null
                ? MESSAGES.admin().version(change.to())
                : MESSAGES.admin().moved(version(from), change.to());
    }

    /** A version as it reads, but a SHA-1, which only the resource pack is known by, cut to its first seven. */
    private static String version(final String version) {
        return SHA1.matcher(version).matches() ? version.substring(0, 7) : version;
    }

    /** Returns the mark of one service's state, from the same set as {@link #glance}. */
    private static Mark marker(final UpdateReport.State state) {
        return switch (state) {
            case UNCHANGED -> Mark.UNCHANGED;
            case PLANNED -> Mark.WAITING;
            case STOPPED, INSTALLED, STARTING -> Mark.WORKING;
            // A finished snapshot, an archive put back and a service that came back are the same news.
            case HEALTHY, SAVED, RESTORED -> Mark.DONE;
            case FAILED -> Mark.FAILED;
        };
    }

    /** Returns the mark in front of a stage: the only place an outcome shows, since every card has one colour. */
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
