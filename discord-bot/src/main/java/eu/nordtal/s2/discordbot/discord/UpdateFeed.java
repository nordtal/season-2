package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.common.message.Locales;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.common.update.UpdateReports;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.common.update.UpdateSource;
import eu.nordtal.s2.common.update.UpdateStatus;

import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every update run in the admin channel, including the ones nobody in Discord started.
 *
 * <h2>The gap this closes</h2>
 * Until 2026-09-08 a run was only visible to whoever asked for it. A {@code /update now} typed in
 * game, or run from a server console, drew its report into that person's chat window and nowhere
 * else - so the four servers went down and came back with the admin channel saying nothing at all.
 * The one surface every admin reads was the one surface that only saw the runs that already had
 * somebody watching them.
 *
 * <p>The rows have always been there; nothing was reading them. This does: it remembers the highest
 * id it has drawn and asks {@link UpdateDirectory#since(long)} for what came after it.</p>
 *
 * <h2>{@code DISCORD} rows are skipped, deliberately</h2>
 * A run started here already has an embed - the asker's own, edited in place by
 * {@link UpdateCommand} for as long as it works. Posting a second copy of the same run into the same
 * guild would put two drawings of one row in front of the same people, and the moment one of them
 * stops updating (the interaction token expires after fifteen minutes; a channel message does not)
 * they would disagree.
 *
 * <h2>It draws, it does not decide</h2>
 * The embed is {@link UpdateCommand#fields}, the same one the asker sees, with a footer naming who
 * asked and from where. Nothing here forms an opinion about a run.
 *
 * <h2>English, like everything else in that channel</h2>
 * The admin channel has many readers and one text. Every {@code AdminLog} line in this bot is
 * English for that reason, and a run drawn in whichever language the person who typed it happens to
 * use would be the one exception.
 */
@Slf4j
public final class UpdateFeed {

    /** How often the table is asked what is new. Two seconds; a run moves stage by stage. */
    public static final Duration INTERVAL = Duration.ofSeconds(2);

    /**
     * How far back a start looks for runs that ended while this bot was down.
     *
     * <p>Twelve minutes, the same patience every other surface gives the updater. Anything older is
     * history somebody would read the log for rather than news for a channel - and posting it would
     * mean a bot that crash-loops filling the channel with the same finished run.</p>
     */
    public static final Duration CATCH_UP = Duration.ofMinutes(12);

    /**
     * Where a run is drawn.
     *
     * <h2>Why a seam and not {@link AdminLog} itself</h2>
     * Everything below is a decision - which rows are worth posting, where a restart picks up, when
     * an edit has something new to say - and every one of them was previously answerable only by
     * watching a real guild during a real update. That is one place per season. The interface is two
     * methods wide and {@link #of(AdminLog)} is the only implementation that ships.
     */
    public interface Board {

        void post(MessageEmbed embed, java.util.function.Consumer<String> sentId);

        void edit(String messageId, MessageEmbed embed);

        /**
         * A line that mentions the admin role.
         *
         * <p>Separate from {@link #post} because an <em>edit</em> notifies nobody: a run that goes
         * wrong at five in the morning would otherwise turn a green embed red on a screen nobody is
         * looking at. Only a failure uses this - a successful run that pings is a ping people learn
         * to ignore, which is the same as no ping at all.</p>
         */
        void alert(String text);

        static Board of(final AdminLog admin) {
            return new Board() {
                @Override
                public void post(final MessageEmbed embed,
                                 final java.util.function.Consumer<String> sentId) {
                    admin.post(embed, sentId);
                }

                @Override
                public void edit(final String messageId, final MessageEmbed embed) {
                    admin.edit(messageId, embed);
                }

                @Override
                public void alert(final String text) {
                    admin.alert(text);
                }
            };
        }
    }

    /** A run being drawn: the message showing it, and the report that message currently shows. */
    private record Drawn(String messageId, String showing) {
    }

    private final UpdateDirectory updates;
    private final Board board;
    private final Messages messages;

    /** The highest id already handled. Only the tick thread writes it. */
    private volatile long lastSeen;

    /**
     * The runs still moving, by request id.
     *
     * <p>Concurrent because the message id arrives on a JDA thread, after the tick that posted it
     * has returned. A run whose post has not been acknowledged yet sits here with a {@code null}
     * message id and is simply not edited until it has one - which is an ordinary state and not an
     * error, because the row is the record and the embed is a drawing of it.</p>
     */
    private final Map<Long, Drawn> drawing = new ConcurrentHashMap<>();

    /** Whether a pass is running. See {@link #tick()} for why this is a flag and not a lock. */
    private final java.util.concurrent.atomic.AtomicBoolean ticking =
            new java.util.concurrent.atomic.AtomicBoolean();

    public UpdateFeed(final UpdateDirectory updates, final Board board, final Messages messages) {
        this.updates = Objects.requireNonNull(updates, "updates");
        this.board = Objects.requireNonNull(board, "board");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    /**
     * Picks up where the last instance of this bot left off.
     *
     * <p>Two halves, and the second is the one that is easy to leave out. Starting from
     * {@code max(id)} is what stops a restart posting a season of history into the channel - and it
     * is also what would silently drop the run that finished during that restart, whose id is below
     * the mark. So finished rows inside {@link #CATCH_UP} are posted once, as results.</p>
     */
    public void start() {
        try {
            final long mark = updates.latestId();
            for (final UpdateRequest request : updates.finishedWithin(CATCH_UP)) {
                if (request.source() == UpdateSource.DISCORD) {
                    continue;
                }
                // Posted and forgotten: it is over, so there is nothing left to edit into it.
                board.post(embed(request), messageId -> { });
            }
            // A run that was still going when this bot went down is the third case, and it fell
            // through both of the others: its id is at or below the mark, so `since(lastSeen)`
            // will never return it, and it is not finished, so `finishedWithin` did not either.
            // The run everybody most wants to watch is exactly the one that outlives a bot
            // restart. Registering it before the mark moves is what makes tick() follow it.
            for (final UpdateRequest request : updates.since(0L)) {
                if (request.source() == UpdateSource.DISCORD || request.status().isFinished()
                        || request.id() > mark) {
                    continue;
                }
                board.post(embed(request), messageId ->
                        drawing.put(request.id(), new Drawn(messageId, request.result())));
            }
            lastSeen = mark;
        } catch (final RuntimeException failure) {
            // Not fatal. The feed starts from whatever it managed to read - zero, in the worst
            // case, which posts the history once and then behaves. A bot that refuses to start
            // because a channel could not be back-filled would be the worse trade.
            log.error("Could not read the update history; the feed starts from {}", lastSeen,
                    failure);
        }
    }

    /**
     * One pass. Scheduled every {@link #INTERVAL}, and never two at once.
     *
     * <p>The guard is not about correctness of the drawing - {@code drawing} is concurrent and
     * `lastSeen` only grows. It is about the thread. This runs on a worker rather than on the
     * single timer thread, because a slow database call here would otherwise hold up the payment
     * poll, the role reconciliation, the expiry sweep, the status channels and the readiness
     * marker, all of which share that one thread. Handing the work to a pool without this flag
     * would then let a slow pass be overtaken by the next one and post a row twice.</p>
     */
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
            if (request.source() == UpdateSource.DISCORD) {
                continue;
            }
            final boolean over = request.status().isFinished();
            if (over) {
                alertIfFailed(request);
            }
            board.post(embed(request), messageId -> {
                if (over) {
                    return;
                }
                // Only now is there something to edit. Registering before the post is acknowledged
                // would mean an edit against a message id that does not exist yet.
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
                // Deleted by hand. Nothing to draw and nothing to say about it that the message
                // already on screen does not.
                drawing.remove(id);
                continue;
            }
            final UpdateRequest request = row.get();
            final Drawn drawn = entry.getValue();
            // Only when it has something new to say. Discord rate-limits edits and this polls every
            // two seconds while a run writes a stage at a time; re-sending an identical embed
            // twenty times between two stages would spend that budget on nothing.
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

    /**
     * Mentions the admin role when a run ended badly, and says nothing at all when it did not.
     *
     * <p>Called exactly once per run: from {@link #tick()} for a row that was already over when it
     * was first seen, and from {@link #redraw()} at the moment a run being followed finishes, just
     * before it stops being followed. The two paths are exclusive - a row that arrives finished is
     * never registered for redrawing.</p>
     *
     * <p>A cancellation is not a failure: somebody typed {@code /update cancel} and already knows.
     * What this is for is the backup that gave up waiting after thirty minutes and the update that
     * could not stop a server - the cases where the network is in a state nobody asked for and the
     * only other trace is an edit to a message from five minutes ago.</p>
     */
    private void alertIfFailed(final UpdateRequest request) {
        if (request.status() != UpdateStatus.FAILED) {
            return;
        }
        try {
            board.alert(footer(request) + " - FAILED. See the embed above.");
        } catch (final RuntimeException failure) {
            // The embed is already posted; losing the mention must not lose the pass.
            log.warn("Could not alert admins about failed update request {}", request.id(), failure);
        }
    }

    /**
     * One run, drawn.
     *
     * <p>A row with no parsable report is one that has only just been written, or one from before
     * the report became structured. Either way the stage a reader wants is "somebody has asked for
     * this", which is what {@code RESOLVING} says.</p>
     */
    private MessageEmbed embed(final UpdateRequest request) {
        final UpdateReport report = UpdateReports.parse(request.result())
                .orElseGet(() -> UpdateReport.at(UpdateReport.Stage.RESOLVING));
        return UpdateCommand.fields(report, request, messages, Locales.DEFAULT, footer(request));
    }

    /** {@code /update now, asked for by Till from GAME} - the half the asker's own embed omits. */
    private static String footer(final UpdateRequest request) {
        final String who = request.requestedBy() == null ? "the console" : request.requestedBy();
        return request.kind() + ", asked for by " + who + " from " + request.source();
    }
}
