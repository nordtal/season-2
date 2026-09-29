package eu.nordtal.s2.discordbot.discord;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import eu.nordtal.s2.common.message.Locales;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.common.update.UpdateReports;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.common.update.UpdateStatus;
import eu.nordtal.s2.discordbot.AdminLog;
import eu.nordtal.s2.discordbot.Card;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.jspecify.annotations.Nullable;

/**
 * Draws every update run in the admin channel, in English.
 */
@Slf4j
public final class UpdateFeed {

    /** How often the table is asked what is new. */
    public static final Duration INTERVAL = Duration.ofSeconds(2);

    /** How far back a start posts runs that ended while this bot was down. */
    public static final Duration CATCH_UP = Duration.ofMinutes(12);

    /** Where a run is drawn; {@link #of(AdminLog)} is the only implementation that ships. */
    public interface Board {

        void post(MessageEmbed embed, java.util.function.Consumer<String> sentId);

        void edit(String messageId, MessageEmbed embed);

        /** Posts a line mentioning the admin role, used only for a failed run since an edit notifies nobody. */
        void alert(String title, String text);

        static Board of(final AdminLog admin) {
            return new Board() {
                @Override
                public void post(final MessageEmbed embed, final java.util.function.Consumer<String> sentId) {
                    admin.post(embed, sentId);
                }

                @Override
                public void edit(final String messageId, final MessageEmbed embed) {
                    admin.edit(messageId, embed);
                }

                @Override
                public void alert(final String title, final String text) {
                    admin.alert(title, text);
                }
            };
        }
    }

    /** A bare Discord id, or one in parentheses at the end of a name. */
    private static final java.util.regex.Pattern DISCORD_ID =
            java.util.regex.Pattern.compile("^(?:.*\\()?(\\d{17,20})\\)?\\s*$");

    private record Drawn(String messageId, @Nullable String showing) {}

    private final UpdateDirectory updates;
    private final Board board;
    private final Messages messages;

    /** The highest id already handled; only the tick thread writes it. */
    private volatile long lastSeen;

    /** The runs still moving, by request id; the message id arrives later on a JDA thread. */
    private final ConcurrentHashMap<Long, Drawn> drawing = new ConcurrentHashMap<>();

    /** Whether a pass is running; see {@link #submit} for why this is a flag and not a lock. */
    private final java.util.concurrent.atomic.AtomicBoolean ticking = new java.util.concurrent.atomic.AtomicBoolean();

    public UpdateFeed(final UpdateDirectory updates, final Board board, final Messages messages) {
        this.updates = Objects.requireNonNull(updates, "updates");
        this.board = Objects.requireNonNull(board, "board");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    /** Starts from the highest id, posting finished runs inside {@link #CATCH_UP} once so a restart loses none. */
    public void start() {
        try {
            final long mark = updates.latestId();
            for (final UpdateRequest request : updates.finishedWithin(CATCH_UP)) {
                // A row with id > mark finished between the two reads above; tick() finds it.
                if (request.id() > mark) {
                    continue;
                }
                // Posted and forgotten: it is over.
                board.post(embed(request), messageId -> {});
            }
            // A run still going falls through both loops above; registering it here makes tick() follow it.
            for (final UpdateRequest request : updates.since(0L)) {
                if (request.status().isFinished() || request.id() > mark) {
                    continue;
                }
                board.post(
                        embed(request), messageId -> drawing.put(request.id(), new Drawn(messageId, request.result())));
            }
            lastSeen = mark;
        } catch (final RuntimeException failure) {
            // Not fatal: the feed starts from whatever it managed to read.
            log.error("Could not read the update history; the feed starts from {}", lastSeen, failure);
        }
    }

    /** Runs one pass, scheduled every {@link #INTERVAL}. */
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

    /** Hands one pass to {@code worker} unless one is outstanding, releasing the flag if the submission is rejected. */
    public void submit(final Executor worker) {
        Objects.requireNonNull(worker, "worker");
        if (!ticking.compareAndSet(false, true)) {
            return;
        }
        try {
            worker.execute(() -> {
                try {
                    pass();
                } catch (final RuntimeException failure) {
                    log.error("The update feed pass failed; it runs again on schedule", failure);
                } finally {
                    ticking.set(false);
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
            final boolean over = request.status().isFinished();
            if (over) {
                alertIfFailed(request);
            }
            board.post(embed(request), messageId -> {
                if (over) {
                    return;
                }
                // Only now is there a message id to edit.
                drawing.put(request.id(), new Drawn(messageId, request.result()));
            });
        }
        redraw();
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
                board.edit(drawn.messageId(), embed(request));
                drawing.put(id, new Drawn(drawn.messageId(), request.result()));
            }
            if (request.status().isFinished()) {
                alertIfFailed(request);
                drawing.remove(id);
            }
        }
    }

    /** Mentions the admin role once when a run failed; a cancellation is not a failure. */
    private void alertIfFailed(final UpdateRequest request) {
        if (request.status() != UpdateStatus.FAILED) {
            return;
        }
        try {
            board.alert(
                    "🛑 " + request.kind().name().toLowerCase(java.util.Locale.ROOT) + " failed",
                    asker(request) + ", " + request.source().name().toLowerCase(java.util.Locale.ROOT));
        } catch (final RuntimeException failure) {
            // The embed is already posted; losing the mention must not lose the pass.
            log.warn("Could not alert admins about failed update request {}", request.id(), failure);
        }
    }

    /** Draws one run; a row with no parsable report reads as {@code RESOLVING}. */
    private MessageEmbed embed(final UpdateRequest request) {
        final UpdateReport report =
                UpdateReports.parse(request.result()).orElseGet(() -> UpdateReport.at(UpdateReport.Stage.RESOLVING));
        return fields(report, request, messages, Locales.DEFAULT, true);
    }

    static MessageEmbed fields(
            final UpdateReport report,
            final UpdateRequest request,
            final Messages messages,
            final java.util.Locale locale) {
        return fields(report, request, messages, locale, false);
    }

    /**
     * Draws one run as data: the stage as title, the outcome as its emoji, one line per service.
     *
     * @param context whether to say who asked, and from where; only the admin channel's feed does
     */
    static MessageEmbed fields(
            final UpdateReport report,
            final UpdateRequest request,
            final Messages messages,
            final java.util.Locale locale,
            final boolean context) {
        final Card card = Card.of(glance(report.stage()) + " "
                        + messages.format(locale, MESSAGES.update().stage(report.stage())))
                .timestamp(request.finished() == null ? Instant.now() : request.finished());
        final java.util.function.IntFunction<String> more = count ->
                Card.italic(messages.format(locale, MESSAGES.update().embed().more(count)));

        if (context) {
            card.field(
                            messages.format(locale, MESSAGES.update().embed().run()),
                            request.kind().name().toLowerCase(java.util.Locale.ROOT))
                    .field(messages.format(locale, MESSAGES.update().embed().by()), asker(request))
                    .field(
                            messages.format(locale, MESSAGES.update().embed().from()),
                            request.source().name().toLowerCase(java.util.Locale.ROOT));
        }
        if (request.finished() != null && request.requested() != null) {
            card.field(
                    messages.format(locale, MESSAGES.update().embed().duration()),
                    Card.duration(Duration.between(request.requested(), request.finished())));
        }

        // The services get the budget first: which server failed matters more than why.
        final java.util.List<String> lines = new java.util.ArrayList<>();
        final java.util.List<String> notes = new java.util.ArrayList<>();
        for (final UpdateReport.ServiceLine line : report.services()) {
            lines.add(line(line, messages, locale));
            if (line.detail() != null && !line.detail().isBlank()) {
                notes.add(Card.bold(line.service()) + " " + Card.escape(line.detail()));
            }
        }
        for (final String note : report.notes()) {
            // A multi-line note only reads in monospace and repeats the service lines.
            if (!note.isBlank() && note.strip().indexOf('\n') < 0) {
                notes.add(Card.escape(note.strip()));
            }
        }
        card.block(messages.format(locale, MESSAGES.update().embed().services()), lines, more);
        card.block(messages.format(locale, MESSAGES.update().embed().notes()), notes, more);
        return card.build();
    }

    /** Renders a service line such as {@code ✅ smp running  smp 0.9.3 → 0.9.4}. */
    private static String line(
            final UpdateReport.ServiceLine line, final Messages messages, final java.util.Locale locale) {
        final StringBuilder text = new StringBuilder(marker(line.state()))
                .append(' ')
                .append(Card.bold(line.service()))
                .append(' ')
                .append(Card.italic(messages.format(locale, MESSAGES.update().state(line.state()))));
        for (final UpdateReport.Change change : line.changes()) {
            text.append("  ")
                    .append(Card.escape(change.artefact()))
                    .append(' ')
                    .append(
                            switch (change.state()) {
                                // No build for this Minecraft version, which stops no server.
                                case UNSUPPORTED ->
                                    Card.italic(messages.format(
                                            locale, MESSAGES.update().embed().noBuild()));
                                case MOVING ->
                                    change.from() == null
                                            ? Card.bold(change.to())
                                            : Card.arrow(change.from(), change.to());
                            });
        }
        return text.toString();
    }

    /** Returns the emoji of one service's state, from the same set as {@link #glance}. */
    private static String marker(final UpdateReport.State state) {
        return switch (state) {
            case UNCHANGED -> "➖";
            case PLANNED -> "⏳";
            case STOPPED, INSTALLED, STARTING -> "🔄";
            // A finished snapshot and a service that came back are the same news.
            case HEALTHY, SAVED -> "✅";
            case FAILED -> "🛑";
        };
    }

    /** Returns the emoji in front of a stage: the only place an outcome shows, since every card has one colour. */
    private static String glance(final UpdateReport.Stage stage) {
        return switch (stage) {
            case RESOLVING -> "🔍";
            case PLANNED -> "📋";
            case COUNTDOWN -> "⏳";
            case STOPPING, BACKING_UP, INSTALLING, STARTING, VERIFYING -> "🔄";
            case DONE, NOTHING_TO_DO -> "✅";
            case FAILED -> "🛑";
            case CANCELLED -> "⏹️";
        };
    }

    /** Who asked for a run, as a mention whenever the row carries a Discord id. */
    static String asker(final UpdateRequest request) {
        final String by = request.requestedBy();
        if (by == null) {
            return "console";
        }
        // Discord writes the bare id, Steward writes "name (id)".
        final java.util.regex.Matcher id = DISCORD_ID.matcher(by);
        return id.matches() ? "<@" + id.group(1) + ">" : Card.escape(by);
    }
}
