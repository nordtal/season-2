package eu.nordtal.season.proxy.update;

import static eu.nordtal.season.proxy.ProxyMessages.MESSAGES;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import eu.nordtal.season.common.time.CountdownPlan;
import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.messagerendering.Lines;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.proxy.PhaseServers;
import eu.nordtal.season.proxy.ProxyMessages;
import eu.nordtal.season.proxy.gate.LoginRoster;
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

/** Announces the countdown of an update run to every player. The countdown ends at {@link UpdateRequest#due()}. */
public final class RestartWatch {

    /** The display times of the tick subtitle. */
    private static final Title.Times TIMES =
            Title.Times.times(Duration.ZERO, Duration.ofMillis(1400), Duration.ofMillis(250));

    private final Scheduler scheduler;
    private final ProxyServer proxy;
    private final Logger logger;
    private final UpdateDirectory updates;
    private final LoginRoster roster;
    private final MessageRenderer renderer;
    private final PhaseServers servers;
    private final Clock clock;

    /** Asks whether a standby proxy answers. It is asked once per countdown. */
    private volatile BooleanSupplier standbyProxy = () -> false;

    /** The shape of the run that is counted down. */
    private volatile RunShape shape = RunShape.of(UpdateKind.RESTART, Set.of(), true, false);

    /** Whether the voice chat message was sent in this countdown. */
    private volatile boolean saidVoice;

    /** Plans the announcements. */
    private final Countdown countdown = new Countdown();

    private final List<Scheduler.Task> scheduled = new ArrayList<>();

    /** Whether the {@code NOW} announcement was sent. */
    private boolean saidNow;

    /** The action that runs at zero. */
    private volatile Runnable atZero = () -> {};

    public RestartWatch(
            final Scheduler scheduler,
            final ProxyServer proxy,
            final Logger logger,
            final UpdateDirectory updates,
            final LoginRoster roster,
            final MessageRenderer renderer,
            final PhaseServers servers,
            final Clock clock) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.updates = Objects.requireNonNull(updates, "updates");
        this.roster = Objects.requireNonNull(roster, "roster");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Sets the check for a standby proxy.
     *
     * @param answers {@code ProxySwap#canPark} in the deployment
     */
    public void standbyProxyAnswers(final BooleanSupplier answers) {
        this.standbyProxy = Objects.requireNonNull(answers, "answers");
    }

    /**
     * Sets the action that runs at zero.
     *
     * @param action must not throw
     */
    public void whenZeroReached(final Runnable action) {
        this.atZero = Objects.requireNonNull(action, "action");
    }

    /** Returns whether a countdown runs. */
    public synchronized boolean isCountingDown() {
        return countdown.watching() != null;
    }

    /**
     * Reads the update request and plans the announcements.
     * It runs on every {@code nordtal_update} notification and never throws.
     */
    public synchronized void check() {
        final Optional<UpdateRequest> pending;
        try {
            pending = updates.countingDown();
        } catch (final RuntimeException failure) {
            logger.warn("Could not read the countdown; nobody was told anything this pass", failure);
            return;
        }

        if (pending.isEmpty()) {
            vanished();
            return;
        }

        final UpdateRequest request = pending.get();
        countdown.beats(request.id(), request.untilDue(clock.instant())).ifPresent(beats -> {
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

    /** Returns the shape of the run. */
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

    /** Schedules the beat on the proxy scheduler. */
    private void schedule(final CountdownPlan.Beat<Announcement> beat) {
        scheduled.add(scheduler.after(beat.delay(), () -> {
            if (beat.said().kind() == Announcement.Kind.NOW) {
                synchronized (this) {
                    if (saidNow) {
                        return;
                    }
                    saidNow = true;
                    countdown.zeroReached();
                }
                try {
                    atZero.run();
                } catch (final RuntimeException failure) {
                    logger.warn(
                            "What was scheduled for the end of the countdown failed; the commit's"
                                    + " notification is what still has to catch this",
                            failure);
                }
            }
            say(beat.said());
        }));
    }

    private void cancelScheduled() {
        scheduled.forEach(Scheduler.Task::cancel);
        scheduled.clear();
    }

    /** Announces the end of a countdown that ran out or was cancelled. */
    private void vanished() {
        final Long watched = countdown.watching();
        if (watched == null) {
            countdown.gone(null).ifPresent(this::say);
            return;
        }

        final UpdateStatus status;
        try {
            status = updates.find(watched).map(UpdateRequest::status).orElse(null);
        } catch (final RuntimeException failure) {
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
            case COUNTDOWN -> {
                each((player, locale) -> player.sendMessage(line(
                        locale,
                        countdown(current.occasion(), what(current), announcement.seconds()),
                        fateOf(current, player))));
                title(locale -> renderer.format(locale, MESSAGES.restart().tick(announcement.seconds())));
                if (!saidVoice) {
                    saidVoice = true;
                    each((player, locale) -> {
                        if (losesVoice(current, player)) {
                            player.sendMessage(
                                    renderer.format(locale, MESSAGES.restart().voice()));
                        }
                    });
                }
            }
            case NOW -> {
                each((player, locale) -> player.sendMessage(
                        line(locale, now(current.occasion(), what(current)), fateOf(current, player))));
                title(locale -> renderer.format(locale, now(current.occasion(), what(current))));
            }
            case TICK ->
                title(locale -> renderer.format(locale, MESSAGES.restart().tick(announcement.seconds())));
            case CANCELLED ->
                broadcast(locale -> renderer.format(locale, MESSAGES.restart().cancelled(occasion(current))));
            case FAILED ->
                broadcast(locale -> renderer.format(locale, MESSAGES.restart().failed(occasion(current))));
        }
    }

    /**
     * Returns the chat message with the occasion, the service and the {@link RunShape.Fate} of the player.
     * {@link RunShape.Fate#NOTHING} adds no text.
     */
    private Component line(final Locale locale, final MessageRef announcement, final RunShape.Fate fate) {
        final Component head = renderer.format(locale, announcement);
        final ProxyMessages.Restart.Fate fates = MESSAGES.restart().fate();
        return switch (fate) {
            case NOTHING -> head;
            case RECONNECT -> head.append(Component.space()).append(renderer.format(locale, fates.reconnect()));
            case WAITING_ROOM -> head.append(Component.space()).append(renderer.format(locale, fates.waitingRoom()));
            case DISCONNECT -> head.append(Component.space()).append(renderer.format(locale, fates.disconnect()));
        };
    }

    static MessageRef countdown(final RunShape.Occasion occasion, final MessageRef what, final long seconds) {
        final ProxyMessages.Restart.RestartCountdown lines = MESSAGES.restart().countdown();
        return switch (occasion) {
            case UPDATE -> lines.update(what, seconds);
            case RECREATE -> lines.recreate(what, seconds);
            case BACKUP -> lines.backup(what, seconds);
            case DOWN -> lines.down(what, seconds);
            case MAINTENANCE -> lines.maintenance(seconds);
        };
    }

    static MessageRef now(final RunShape.Occasion occasion, final MessageRef what) {
        final ProxyMessages.Restart.Now lines = MESSAGES.restart().now();
        return switch (occasion) {
            case UPDATE -> lines.update(what);
            case RECREATE -> lines.recreate(what);
            case BACKUP -> lines.backup(what);
            case DOWN -> lines.down(what);
            case MAINTENANCE -> lines.maintenance();
        };
    }

    /** Returns whether the player loses voice chat. */
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

    /** Returns the name of the only service of the run, or "the network" for several. */
    private static MessageRef what(final RunShape current) {
        final String only = current.onlyService();
        return only == null ? MESSAGES.restart().what().network() : Homecoming.serviceName(only);
    }

    /** Returns the occasion as a noun. */
    private static MessageRef occasion(final RunShape current) {
        final ProxyMessages.Restart.Occasion occasions = MESSAGES.restart().occasion();
        return switch (current.occasion()) {
            case UPDATE -> occasions.update();
            case RECREATE -> occasions.recreate();
            case BACKUP -> occasions.backup();
            case DOWN -> occasions.down();
            case MAINTENANCE -> occasions.maintenance();
        };
    }

    /** Sends the message to every player in the language of the player. */
    private void broadcast(final java.util.function.Function<Locale, Component> render) {
        each((player, locale) -> player.sendMessage(render.apply(locale)));
    }

    /** Shows the message to every player as a subtitle. */
    private void title(final java.util.function.Function<Locale, Component> render) {
        each((player, locale) ->
                player.showTitle(Title.title(Component.empty(), Lines.joined(render.apply(locale)), TIMES)));
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
