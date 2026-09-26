package eu.nordtal.s2.proxy.update;

import static eu.nordtal.s2.proxy.ProxyMessages.MESSAGES;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.common.update.UpdateStatus;
import eu.nordtal.s2.proxy.PhaseServers;
import eu.nordtal.s2.proxy.ProxyMessages;
import eu.nordtal.s2.proxy.gate.LoginRoster;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.slf4j.Logger;

/**
 * Tells every player on the network that it is about to go down, and how long they have.
 *
 * <h2>Why the proxy and not the SMP plugin</h2>
 * A run takes servers down: the proxy is the only process that sees everybody - a backend sees its
 * own slice, and a player waiting in {@code limbo} or playing Hunger Games would otherwise be
 * disconnected with no warning at all. It also means a run asked for <b>in Discord</b> is announced
 * in game, which is the more common case and the one a countdown attached to {@code /update} on a
 * backend would have missed entirely.
 *
 * <h2>The instant comes from the row, never from a clock here</h2>
 * {@code update_request.not_before} is written by steward-worker when it has resolved a plan with work
 * in it, as an absolute instant on the database's clock. This class counts towards that instant and
 * the worker waits it out, so the two cannot disagree - which is the whole reason the countdown
 * length is not a setting in two config files.
 *
 * <h2>Scheduled tasks, not a poll that speaks - 2026-09-08</h2>
 * It used to announce whatever the five-second poll happened to observe, so a thirty-second
 * countdown was spoken as "27" and the last ten seconds could be spoken twice. Now the row is seen
 * once and the whole countdown is <b>scheduled</b>: one task per beat, each on the exact millisecond
 * its number becomes true ({@link Countdown}). The poll is still here and still every five seconds,
 * but its only remaining job is the other direction - noticing that a countdown has been withdrawn,
 * cancelling the tasks and saying so.
 *
 * <p>The {@code nordtal_update} listener is what makes the first sighting immediate. On a
 * thirty-second countdown, up to five seconds of poll latency is a sixth of the warning spent before
 * it is shown - and the beats that had already passed in that window would be dropped, so the
 * players would lose the "30 seconds" line entirely. The poll remains the guarantee: a lost
 * notification costs latency, never the countdown.</p>
 *
 * <h2>What it does not do</h2>
 * It does not stop anybody logging in during the last seconds, and it does not move players to limbo
 * first. Both were considered; both are more machinery running at exactly the moment the network is
 * already going down, and neither makes the outage better for anybody already connected.
 */
public final class RestartWatch {

    /** How often the counting-down row is looked for. */
    public static final Duration INTERVAL = Duration.ofSeconds(5);

    /**
     * How long a tick's subtitle stays up.
     *
     * <p>Longer than the second between two ticks, deliberately: the client fades a title out when
     * the next one replaces it, and a stay shorter than the gap leaves the screen blank for a moment
     * every second, which reads as flicker rather than as a counter.</p>
     */
    private static final Title.Times TIMES =
            Title.Times.times(Duration.ZERO, Duration.ofMillis(1400), Duration.ofMillis(250));

    private final Object plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final UpdateDirectory updates;
    private final LoginRoster roster;
    private final Messages messages;
    private final PhaseServers servers;
    private final Clock clock;

    /**
     * Whether a standby proxy is answering, asked once per countdown (season-2-ops/118).
     *
     * <p>Defaults to no, which is the honest default: a proxy wired without this has no standby,
     * and a player told they will see a loading screen and then thrown out is worse off than one
     * who was told the truth.</p>
     */
    private volatile BooleanSupplier standbyProxy = () -> false;

    /**
     * What the run being counted down actually is, worked out once when its beats are planned.
     *
     * <p>Held rather than recomputed per beat because it costs a report parse and a socket probe,
     * and because every beat of one countdown has to say the same thing. Kept after the countdown
     * ends so that {@link #vanished()} can name the right thing when it says it was called off.</p>
     */
    private volatile RunShape shape = RunShape.of(UpdateKind.RESTART, Set.of(), true, false);

    /**
     * Whether the one sentence about voice chat has been said for this countdown.
     *
     * <p>Once and on the first chat line, not on every one: it is a note about a side effect, and a
     * note repeated four times reads as the main event (season-2-ops/132).</p>
     */
    private volatile boolean saidVoice;

    /** When to speak and what to say. All the rules are in there; none of them are here. */
    private final Countdown countdown = new Countdown();

    /** The beats still to fire, so a withdrawal can take them all back. */
    private final List<ScheduledTask> scheduled = new ArrayList<>();

    /**
     * Whether the network has already been told the outage is happening.
     *
     * <p>Two paths can reach that sentence - the scheduled zero beat, and a poll landing in the few
     * milliseconds between the countdown running out and that beat firing - and saying it twice is
     * the one duplicate a player would definitely notice.</p>
     */
    private boolean saidNow;

    /**
     * What else happens at zero, besides saying so (season-2-ops/118).
     *
     * <p>The evacuation, in practice. It used to run off the five-second sweep with an eight-second
     * head start, so that a pass was guaranteed to land inside the window - and the cost of that
     * guarantee was that players left their server while the counter still showed eight. This class
     * already knows the exact instant the counter reaches zero, because it schedules a task on it;
     * handing that instant to the one other thing that needs it is cheaper than a second mechanism
     * for finding it, and it is the only way the two can agree to the millisecond.</p>
     *
     * <p>Defaults to doing nothing, so a proxy wired without it still counts down.</p>
     */
    private volatile Runnable atZero = () -> {};

    public RestartWatch(
            final Object plugin,
            final ProxyServer proxy,
            final Logger logger,
            final UpdateDirectory updates,
            final LoginRoster roster,
            final Messages messages,
            final PhaseServers servers,
            final Clock clock) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.updates = Objects.requireNonNull(updates, "updates");
        this.roster = Objects.requireNonNull(roster, "roster");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * How to find out whether a standby proxy is there to catch the network.
     *
     * @param answers {@code ProxySwap#canPark} in the deployment. A setter rather than a
     *                constructor argument because the swap is built after this watch and needs it -
     *                the same shape {@link #whenZeroReached} has, and for the same reason
     */
    public void standbyProxyAnswers(final BooleanSupplier answers) {
        this.standbyProxy = Objects.requireNonNull(answers, "answers");
    }

    /**
     * What to run at the instant the counter reaches zero, beside the announcement.
     *
     * @param action never throws on its own account - it is called inside a scheduled task, and a
     *               task that throws is a task Velocity stops running
     */
    public void whenZeroReached(final Runnable action) {
        this.atZero = Objects.requireNonNull(action, "action");
    }

    /**
     * Whether a countdown is running right now.
     *
     * <p><b>Read by {@code OnlineWriter}, and it is load-bearing (season-2-ops/118).</b> The player
     * counts are written every ten seconds ordinarily and every second while a run needs them, and
     * "needs them" starts here rather than at zero: steward-worker's first read happens the instant
     * the counter runs out, and a count written up to ten seconds earlier cannot answer it. Until
     * this was the signal, the fast cadence began at the same moment as the question - so the first
     * answer was either stale or lucky.</p>
     *
     * <p>Thirty rows a run is what that costs, and the thing it buys is a run that waits for the
     * right reason instead of running its ten-second cap out every time.</p>
     */
    public synchronized boolean isCountingDown() {
        return countdown.watching() != null;
    }

    /**
     * One pass. Scheduled every {@link #INTERVAL}, and run again on every {@code nordtal_update}
     * notification.
     *
     * <p>Never throws: it runs on the proxy's scheduler, and a task that throws is a task Velocity
     * stops running - the failure mode of which is a network that goes down one day with nobody
     * warned and nothing in the log saying why.</p>
     */
    public synchronized void check() {
        final Optional<UpdateRequest> pending;
        try {
            pending = updates.countingDown();
        } catch (final RuntimeException failure) {
            // A database that cannot be reached is not a reason to announce anything, and it is not
            // a reason to take back a countdown that is already scheduled either: the run still
            // happens or does not, and the beats already planned are measured against an instant
            // this pass did not need to read.
            logger.warn("Could not read the countdown; nobody was told anything this pass", failure);
            return;
        }

        if (pending.isEmpty()) {
            vanished();
            return;
        }

        final UpdateRequest request = pending.get();
        countdown.beats(request.id(), request.untilDue(clock.instant())).ifPresent(beats -> {
            // ONCE PER COUNTDOWN AND NOT PER BEAT (season-2-ops/118): it parses the report and
            // opens a socket, and every beat of one countdown has to say the same thing anyway.
            shape = shapeOf(request);
            saidVoice = false;
            logger.info(
                    "Telling {} player(s) about the {} asked for by {} ({}): {} beat(s) over"
                            + " {} - {} on {}, waiting room {}, standby proxy {}",
                    proxy.getPlayerCount(),
                    request.kind(),
                    request.requestedBy(),
                    request.source(),
                    beats.size(),
                    request.untilDue(clock.instant()),
                    shape.occasion(),
                    shape.moving(),
                    shape.waitingRoom() ? "yes" : "NO",
                    shape.standbyProxy() ? "yes" : "no");
            cancelScheduled();
            saidNow = false;
            beats.forEach(this::schedule);
        });
    }

    /**
     * What this run is, from the four things that already decided it (season-2-ops/118).
     *
     * <p>Nothing here is worked out twice: the services come out of the report steward-worker wrote
     * into the row before the countdown started, through the same parser {@link Evacuation} uses;
     * the waiting room is {@link Evacuation#roomFor}, asked of this run rather than of the running
     * one; and the standby proxy is {@code ProxySwap}'s own probe.</p>
     *
     * <p>Never throws. A report that cannot be read gives no services, which reads as a run that
     * touches nobody - the same safe direction {@link Evacuation#backends} takes, and the countdown
     * is still spoken.</p>
     */
    private RunShape shapeOf(final UpdateRequest request) {
        final Set<String> moving;
        try {
            moving = Evacuation.backends(request);
        } catch (final RuntimeException failure) {
            logger.warn(
                    "Could not read the plan of request {}; the countdown will be spoken"
                            + " without naming what it is for",
                    request.id(),
                    failure);
            return RunShape.of(request.kind(), Set.of(), true, false);
        }
        final boolean room = Evacuation.roomFor(
                        moving,
                        servers.limbo(),
                        servers.limboStandby(),
                        name -> proxy.getServer(name).isPresent())
                != null;
        boolean standby = false;
        try {
            standby = standbyProxy.getAsBoolean();
        } catch (final RuntimeException failure) {
            logger.warn(
                    "Could not ask whether the standby proxy answers; telling players the"
                            + " worse of the two outcomes",
                    failure);
        }
        return RunShape.of(request.kind(), moving, room, standby);
    }

    /**
     * Puts one beat on the proxy's scheduler.
     *
     * <p>A zero delay is scheduled rather than run inline: Velocity accepts one, and running it here
     * would put a broadcast on whatever thread the notification listener happens to be, in the
     * middle of a method holding this object's monitor.</p>
     */
    private void schedule(final Countdown.Beat beat) {
        scheduled.add(proxy.getScheduler()
                .buildTask(plugin, () -> {
                    if (beat.announcement().kind() == Announcement.Kind.NOW) {
                        synchronized (this) {
                            if (saidNow) {
                                return;
                            }
                            saidNow = true;
                            countdown.zeroReached();
                        }
                        // Before the sentence rather than after it: the two are the same event, and the
                        // one a player can be hurt by is the move. A failure here must not swallow the
                        // announcement, which is why it is caught rather than allowed to end the task.
                        try {
                            atZero.run();
                        } catch (final RuntimeException failure) {
                            logger.warn(
                                    "What was scheduled for the end of the countdown failed; the"
                                            + " five-second sweep behind it is what still has to catch this",
                                    failure);
                        }
                    }
                    say(beat.announcement());
                })
                .delay(beat.delay())
                .schedule());
    }

    /** Takes back every beat that has not fired. */
    private void cancelScheduled() {
        scheduled.forEach(ScheduledTask::cancel);
        scheduled.clear();
    }

    /**
     * The row being counted down is no longer counting down. Looks up what became of it.
     *
     * <p>The extra query is the whole of finding 39: a row leaving the set is far more often the
     * countdown running out than a person withdrawing it, and those two are opposite things to tell
     * a player. It costs one indexed lookup by primary key, on the single pass where a countdown
     * ends - not on the poll, which is the common case and still one query.</p>
     */
    private void vanished() {
        final Long watched = countdown.watching();
        if (watched == null) {
            // Nothing was being counted down. Clears the bookkeeping and stays silent.
            countdown.gone(null).ifPresent(this::say);
            return;
        }

        final UpdateStatus status;
        try {
            status = updates.find(watched).map(UpdateRequest::status).orElse(null);
        } catch (final RuntimeException failure) {
            // Same rule as above: a database that cannot be reached is not a reason to announce
            // anything, and guessing here is exactly what this method exists to stop. The countdown
            // is left standing so the next pass asks again.
            logger.warn(
                    "Countdown {} stopped and could not be read back; nobody was told anything" + " this pass",
                    watched,
                    failure);
            return;
        }

        logger.info("Countdown {} is over: {}", watched, status == null ? "the row is gone" : status);
        cancelScheduled();
        countdown.gone(status).ifPresent(announcement -> {
            if (announcement.kind() == Announcement.Kind.NOW) {
                if (saidNow) {
                    return;
                }
                saidNow = true;
            }
            say(announcement);
        });
    }

    private void say(final Announcement announcement) {
        final RunShape current = shape;
        switch (announcement.kind()) {
            // Chat and a title, since season-2-ops/132: chat is where a warning is read, and the
            // title is the half that reaches a player who is mining with the chat box closed. The
            // title is the tick's own text, so the middle of the screen counts in one voice - and
            // Countdown drops the tick of this second so the two do not draw over one another.
            case COUNTDOWN -> {
                each((player, locale) -> player.sendMessage(line(
                        locale,
                        countdown(current.occasion(), what(locale, current), announcement.seconds()),
                        fateOf(current, player))));
                title(locale -> MessageRenderer.of(messages)
                        .format(locale, MESSAGES.restart().tick(announcement.seconds())));
                if (!saidVoice) {
                    saidVoice = true;
                    each((player, locale) -> {
                        if (losesVoice(current, player)) {
                            player.sendMessage(MessageRenderer.of(messages)
                                    .format(locale, MESSAGES.restart().voice()));
                        }
                    });
                }
            }
            case NOW -> {
                each((player, locale) -> player.sendMessage(
                        line(locale, now(current.occasion(), what(locale, current)), fateOf(current, player))));
                title(locale ->
                        MessageRenderer.of(messages).format(locale, now(current.occasion(), what(locale, current))));
            }
            // No chat line: the number alone, in the middle of the screen, once a second.
            case TICK ->
                title(locale -> MessageRenderer.of(messages)
                        .format(locale, MESSAGES.restart().tick(announcement.seconds())));
            case CANCELLED ->
                broadcast(locale -> MessageRenderer.of(messages)
                        .format(locale, MESSAGES.restart().cancelled(occasion(locale, current))));
            case FAILED ->
                broadcast(locale -> MessageRenderer.of(messages)
                        .format(locale, MESSAGES.restart().failed(occasion(locale, current))));
        }
    }

    /**
     * One announcement, addressed: what is happening, and then what happens to <em>you</em>.
     *
     * <p>Two keys rather than one, and that is the whole of Till's second ask in season-2-ops/118.
     * The first names the occasion and the service; the second names the outcome for the player
     * reading it. Joined with a space into one chat line, because the old single key was already
     * written that way - a coloured sentence and a grey one - and two separate messages in the box
     * would read as the network repeating itself.</p>
     *
     * <p>{@link RunShape.Fate#NOTHING} adds no second half at all. There is nothing to tell
     * somebody whose server is not in the run and who is not going anywhere.</p>
     */
    private Component line(final Locale locale, final MessageRef announcement, final RunShape.Fate fate) {
        final Component head = MessageRenderer.of(messages).format(locale, announcement);
        final ProxyMessages.Restart.Fate fates = MESSAGES.restart().fate();
        return switch (fate) {
            case NOTHING -> head;
            case RECONNECT ->
                head.append(Component.space())
                        .append(MessageRenderer.of(messages).format(locale, fates.reconnect()));
            case WAITING_ROOM ->
                head.append(Component.space())
                        .append(MessageRenderer.of(messages).format(locale, fates.waitingRoom()));
            case DISCONNECT ->
                head.append(Component.space())
                        .append(MessageRenderer.of(messages).format(locale, fates.disconnect()));
        };
    }

    /** The warning ahead of a run, by what the run is. */
    private static MessageRef countdown(final RunShape.Occasion occasion, final Component what, final long seconds) {
        final ProxyMessages.Restart.Countdown lines = MESSAGES.restart().countdown();
        return switch (occasion) {
            case UPDATE -> lines.update(what, seconds);
            case RECREATE -> lines.recreate(what, seconds);
            case BACKUP -> lines.backup(what, seconds);
            case DOWN -> lines.down(what, seconds);
            case MAINTENANCE -> lines.maintenance(seconds);
        };
    }

    /** The line when a run starts, by what the run is. */
    private static MessageRef now(final RunShape.Occasion occasion, final Component what) {
        final ProxyMessages.Restart.Now lines = MESSAGES.restart().now();
        return switch (occasion) {
            case UPDATE -> lines.update(what);
            case RECREATE -> lines.recreate(what);
            case BACKUP -> lines.backup(what);
            case DOWN -> lines.down(what);
            case MAINTENANCE -> lines.maintenance();
        };
    }

    /**
     * Whether this player is about to lose voice chat - the rule is {@link RunShape#losesVoice},
     * and this is the half of it that needs a Velocity connection to answer.
     */
    private boolean losesVoice(final RunShape current, final Player player) {
        final String on = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse(null);
        return RunShape.losesVoice(current.fateFor(on), servers.isWaitingRoom(on));
    }

    /** What is about to happen to this player, from where they are standing right now. */
    private static RunShape.Fate fateOf(final RunShape current, final Player player) {
        return current.fateFor(player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse(null));
    }

    /**
     * What the run is about, as a name a player would use.
     *
     * <p>One service gets its own name; anything else is "the network". The fallback when a service
     * has no name of its own is the compose name itself, which is ugly and correct - a missing
     * translation must not turn the sentence into one about the whole network, because that is a
     * different and much larger promise.</p>
     */
    private Component what(final Locale locale, final RunShape current) {
        final String only = current.onlyService();
        if (only == null) {
            return MessageRenderer.of(messages)
                    .format(locale, MESSAGES.restart().what().network());
        }
        return Homecoming.serviceName(messages, locale, only);
    }

    /** The occasion as a noun, for the two lines that say it is off rather than that it is coming. */
    private Component occasion(final Locale locale, final RunShape current) {
        final ProxyMessages.Restart.Occasion occasions = MESSAGES.restart().occasion();
        return MessageRenderer.of(messages)
                .format(
                        locale,
                        switch (current.occasion()) {
                            case UPDATE -> occasions.update();
                            case RECREATE -> occasions.recreate();
                            case BACKUP -> occasions.backup();
                            case DOWN -> occasions.down();
                            case MAINTENANCE -> occasions.maintenance();
                        });
    }

    /**
     * To everybody, in their own language.
     *
     * <p>The locale comes from {@link LoginRoster}, which holds it from the login query, and falls
     * back to English for anybody the roster has no session for - which on this path is nobody, but
     * a broadcast must not be the thing that throws.</p>
     */
    private void broadcast(final java.util.function.Function<Locale, Component> render) {
        each((player, locale) -> player.sendMessage(render.apply(locale)));
    }

    /** The same, as a subtitle with an empty title above it. */
    private void title(final java.util.function.Function<Locale, Component> render) {
        each((player, locale) -> player.showTitle(Title.title(Component.empty(), render.apply(locale), TIMES)));
    }

    private void each(final java.util.function.BiConsumer<Player, Locale> what) {
        for (final Player player : proxy.getAllPlayers()) {
            try {
                what.accept(player, roster.localeOf(player.getUniqueId()));
            } catch (final RuntimeException failure) {
                logger.warn("Could not tell {} about the restart", player.getUsername(), failure);
            }
        }
    }
}
