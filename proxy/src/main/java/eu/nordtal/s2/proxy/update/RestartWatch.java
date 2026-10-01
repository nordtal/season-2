package eu.nordtal.s2.proxy.update;

import static eu.nordtal.s2.proxy.ProxyMessages.MESSAGES;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import eu.nordtal.s2.database.update.UpdateDirectory;
import eu.nordtal.s2.database.update.UpdateKind;
import eu.nordtal.s2.database.update.UpdateRequest;
import eu.nordtal.s2.database.update.UpdateStatus;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Messages;
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
 * Counts towards the row's {@link UpdateRequest#due()}; the poll catches withdrawals, the notification makes it prompt.
 */
public final class RestartWatch {

    /** How often the counting-down row is looked for. */
    public static final Duration INTERVAL = Duration.ofSeconds(5);

    /** How long a tick's subtitle stays up; longer than a second, so the counter does not flicker between ticks. */
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

    /** Whether a standby proxy is answering, asked once per countdown; defaults to no. */
    private volatile BooleanSupplier standbyProxy = () -> false;

    /** What the run being counted down is, fixed once per countdown and kept so {@link #vanished()} can name it. */
    private volatile RunShape shape = RunShape.of(UpdateKind.RESTART, Set.of(), true, false);

    /** Whether the voice chat sentence has been said for this countdown; it goes on the first chat line only. */
    private volatile boolean saidVoice;

    /** When to speak and what to say. */
    private final Countdown countdown = new Countdown();

    private final List<ScheduledTask> scheduled = new ArrayList<>();

    /** Whether the network has already been told the outage is happening; two paths reach that sentence. */
    private boolean saidNow;

    /** What else runs at zero besides the announcement, the evacuation in practice; defaults to nothing. */
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
     * @param answers {@code ProxySwap#canPark} in the deployment; a setter, since the swap is built after this watch
     */
    public void standbyProxyAnswers(final BooleanSupplier answers) {
        this.standbyProxy = Objects.requireNonNull(answers, "answers");
    }

    /**
     * What to run at the instant the counter reaches zero, beside the announcement.
     *
     * @param action must not throw, since Velocity stops running a scheduled task that throws
     */
    public void whenZeroReached(final Runnable action) {
        this.atZero = Objects.requireNonNull(action, "action");
    }

    /**
     * Whether a countdown is running right now.
     *
     * {@code OnlineWriter} writes counts every second from here on, so steward reads fresh ones at zero.
     */
    public synchronized boolean isCountingDown() {
        return countdown.watching() != null;
    }

    /** One pass, every {@link #INTERVAL} and on every {@code nordtal_update} notification; never throws. */
    public synchronized void check() {
        final Optional<UpdateRequest> pending;
        try {
            pending = updates.countingDown();
        } catch (final RuntimeException failure) {
            // Not a reason to announce anything, nor to take back a countdown already scheduled.
            logger.warn("Could not read the countdown; nobody was told anything this pass", failure);
            return;
        }

        if (pending.isEmpty()) {
            vanished();
            return;
        }

        final UpdateRequest request = pending.get();
        countdown.beats(request.id(), request.untilDue(clock.instant())).ifPresent(beats -> {
            // Once per countdown: it parses a report and opens a socket.
            shape = shapeOf(request);
            saidVoice = false;
            logger.info(
                    "Telling {} player(s) about the {} asked for by {}: {} beat(s) over"
                            + " {} - {} on {}, waiting room {}, standby proxy {}",
                    proxy.getPlayerCount(),
                    request.kind(),
                    request.actor(),
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

    /** What this run is, from the services it stops, {@link Evacuation#roomFor} and {@code ProxySwap}'s probe. */
    private RunShape shapeOf(final UpdateRequest request) {
        final Set<String> moving = Evacuation.backends(request);
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

    /** Puts one beat on the proxy's scheduler, even at zero delay, so no broadcast runs under this monitor. */
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
                        // Before the sentence: a failure here must not swallow the announcement.
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

    private void cancelScheduled() {
        scheduled.forEach(ScheduledTask::cancel);
        scheduled.clear();
    }

    /** Looks up what became of a row that is no longer counting down: ran out or withdrawn. */
    private void vanished() {
        final Long watched = countdown.watching();
        if (watched == null) {
            // Nothing was being counted down; clears the bookkeeping and stays silent.
            countdown.gone(null).ifPresent(this::say);
            return;
        }

        final UpdateStatus status;
        try {
            status = updates.find(watched).map(UpdateRequest::status).orElse(null);
        } catch (final RuntimeException failure) {
            // The countdown is left standing so the next pass asks again.
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
            // Chat is where a warning is read; the title reaches a player mining with chat closed.
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
     * One chat line: the occasion and service, then in grey what happens to the reader.
     *
     * {@link RunShape.Fate#NOTHING} adds no second half.
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

    static MessageRef countdown(final RunShape.Occasion occasion, final Component what, final long seconds) {
        final ProxyMessages.Restart.RestartCountdown lines = MESSAGES.restart().countdown();
        return switch (occasion) {
            case UPDATE -> lines.update(what, seconds);
            case RECREATE -> lines.recreate(what, seconds);
            case BACKUP -> lines.backup(what, seconds);
            case DOWN -> lines.down(what, seconds);
            case MAINTENANCE -> lines.maintenance(seconds);
        };
    }

    static MessageRef now(final RunShape.Occasion occasion, final Component what) {
        final ProxyMessages.Restart.Now lines = MESSAGES.restart().now();
        return switch (occasion) {
            case UPDATE -> lines.update(what);
            case RECREATE -> lines.recreate(what);
            case BACKUP -> lines.backup(what);
            case DOWN -> lines.down(what);
            case MAINTENANCE -> lines.maintenance();
        };
    }

    /** Whether this player is about to lose voice chat, by {@link RunShape#losesVoice}. */
    private boolean losesVoice(final RunShape current, final Player player) {
        final String on = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse(null);
        return RunShape.losesVoice(current.fateFor(on), servers.isWaitingRoom(on));
    }

    private static RunShape.Fate fateOf(final RunShape current, final Player player) {
        return current.fateFor(player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse(null));
    }

    /** The service a run is about as a player would name it, or "the network" for more than one. */
    private Component what(final Locale locale, final RunShape current) {
        final String only = current.onlyService();
        if (only == null) {
            return MessageRenderer.of(messages)
                    .format(locale, MESSAGES.restart().what().network());
        }
        return Homecoming.serviceName(messages, locale, only);
    }

    /** The occasion as a noun, for the lines that say it is off. */
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

    /** Renders a line for everybody in their own locale from {@link LoginRoster}, English when it has none. */
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
