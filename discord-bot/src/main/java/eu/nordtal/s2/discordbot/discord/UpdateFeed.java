package eu.nordtal.s2.discordbot.discord;

import static eu.nordtal.s2.database.AdminTexts.TEXTS;

import eu.nordtal.s2.common.language.Locales;
import eu.nordtal.s2.database.update.UpdateDirectory;
import eu.nordtal.s2.database.update.UpdateReport;
import eu.nordtal.s2.database.update.UpdateReports;
import eu.nordtal.s2.database.update.UpdateRequest;
import eu.nordtal.s2.discordbot.AdminLog;
import eu.nordtal.s2.discordbot.Card;
import eu.nordtal.s2.discordbot.DiscordRenderer;
import eu.nordtal.s2.messages.MessageRef;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.IntFunction;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.jspecify.annotations.Nullable;

/**
 * Draws every update run in the admin channel, in English.
 *
 * An edit notifies nobody, so a failed run reaches the admins as steward's alert, not from here.
 */
@Slf4j
public final class UpdateFeed {

    /** How far back a start posts runs that ended while this bot was down. */
    public static final Duration CATCH_UP = Duration.ofMinutes(12);

    /** Where a run is drawn; {@link #of(AdminLog)} is the only implementation that ships. */
    public interface Board {

        void post(MessageEmbed embed, java.util.function.Consumer<String> sentId);

        void edit(String messageId, MessageEmbed embed);

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
            };
        }
    }

    private record Drawn(String messageId, @Nullable String showing) {}

    private final UpdateDirectory updates;
    private final Board board;
    private final DiscordRenderer messages;

    /** The highest id already handled; only the tick thread writes it. */
    private volatile long lastSeen;

    /** The runs still moving, by request id; the message id arrives later on a JDA thread. */
    private final ConcurrentHashMap<Long, Drawn> drawing = new ConcurrentHashMap<>();

    /** Whether a pass is running; see {@link #submit} for why this is a flag and not a lock. */
    private final java.util.concurrent.atomic.AtomicBoolean ticking = new java.util.concurrent.atomic.AtomicBoolean();

    /** Set by every signal, so one that arrives during a pass is followed by exactly one more. */
    private final java.util.concurrent.atomic.AtomicBoolean again = new java.util.concurrent.atomic.AtomicBoolean();

    private final Clock clock;

    public UpdateFeed(
            final UpdateDirectory updates, final Board board, final DiscordRenderer messages, final Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
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
            final boolean over = request.status().isFinished();
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
                drawing.remove(id);
            }
        }
    }

    /** Draws one run; a row with no parsable report reads as {@code RESOLVING}. */
    private MessageEmbed embed(final UpdateRequest request) {
        final UpdateReport report =
                UpdateReports.parse(request.result()).orElseGet(() -> UpdateReport.at(UpdateReport.Stage.RESOLVING));
        return fields(report, request, messages, true, clock.instant());
    }

    static MessageEmbed fields(
            final UpdateReport report, final UpdateRequest request, final DiscordRenderer messages, final Instant now) {
        return fields(report, request, messages, false, now);
    }

    /**
     * Draws one run as data: the stage as title, the outcome as its emoji, one line per service.
     *
     * @param context whether to say who asked, and from where; only the admin channel's feed does
     */
    static MessageEmbed fields(
            final UpdateReport report,
            final UpdateRequest request,
            final DiscordRenderer messages,
            final boolean context,
            final Instant now) {
        final Function<MessageRef, String> text = message -> messages.format(Locales.DEFAULT, message);
        final Card card = Card.of(
                        glance(report.stage()) + " " + text.apply(TEXTS.run().stage(report.stage())))
                .timestamp(request.finished() == null ? now : request.finished());
        final IntFunction<String> more =
                count -> Card.italic(text.apply(TEXTS.run().more(count)));

        if (context) {
            card.field(text.apply(TEXTS.run().heading()), text.apply(TEXTS.run().kind(request.kind())))
                    .field(text.apply(TEXTS.journal().by()), asker(request, messages));
        }
        if (request.finished() != null && request.requested() != null) {
            card.field(
                    text.apply(TEXTS.run().duration()),
                    text.apply(TEXTS.run().took(Duration.between(request.requested(), request.finished()))));
        }

        // The services get the budget first: which server failed matters more than why.
        final List<String> lines = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        for (final UpdateReport.ServiceLine line : report.services()) {
            lines.add(line(line, text));
            final MessageRef detail = line.detail();
            if (detail != null) {
                notes.add(Card.bold(line.service()) + " " + text.apply(detail).strip());
            }
        }
        for (final MessageRef note : report.notes()) {
            // The renderer escapes every value; a multi-line note only reads in monospace and repeats the lines.
            final String shown = text.apply(note).strip();
            if (!shown.isBlank() && shown.indexOf('\n') < 0) {
                notes.add(shown);
            }
        }
        card.block(text.apply(TEXTS.run().services()), lines, more);
        card.block(text.apply(TEXTS.run().notes()), notes, more);
        return card.build();
    }

    /** Renders a service line such as {@code ✅ smp running  smp 0.9.3 → 0.9.4}. */
    private static String line(final UpdateReport.ServiceLine line, final Function<MessageRef, String> text) {
        final StringBuilder shown = new StringBuilder(marker(line.state()))
                .append(' ')
                .append(Card.bold(line.service()))
                .append(' ')
                .append(Card.italic(text.apply(TEXTS.run().state(line.state()))));
        for (final UpdateReport.Change change : line.changes()) {
            shown.append("  ")
                    .append(Card.escape(change.artefact()))
                    .append(' ')
                    .append(
                            switch (change.state()) {
                                // No build for this Minecraft version, which stops no server.
                                case UNSUPPORTED ->
                                    Card.italic(text.apply(TEXTS.run().noBuild()));
                                case MOVING ->
                                    change.from() == null
                                            ? Card.bold(change.to())
                                            : Card.arrow(change.from(), change.to());
                            });
        }
        return shown.toString();
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

    /** Who asked for a run, as the journal names who did something: a mention for a person, a word for the rest. */
    static String asker(final UpdateRequest request, final DiscordRenderer messages) {
        return messages.format(Locales.DEFAULT, TEXTS.journal().who(request.actor()));
    }
}
