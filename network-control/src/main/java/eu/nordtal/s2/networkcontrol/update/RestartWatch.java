package eu.nordtal.s2.networkcontrol.update;

import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.common.update.UpdateStatus;
import eu.nordtal.s2.networkcontrol.gate.LoginRoster;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

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
 * {@code update_request.not_before} is written by the updater when it has resolved a plan with work
 * in it, as an absolute instant on the database's clock. This class counts towards that instant and
 * the updater waits it out, so the two cannot disagree - which is the whole reason the countdown
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
    private static final Title.Times TIMES = Title.Times.times(
            Duration.ZERO, Duration.ofMillis(1400), Duration.ofMillis(250));

    private final Object plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final UpdateDirectory updates;
    private final LoginRoster roster;
    private final Messages messages;
    private final Clock clock;

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

    public RestartWatch(final Object plugin, final ProxyServer proxy, final Logger logger,
                        final UpdateDirectory updates, final LoginRoster roster,
                        final Messages messages, final Clock clock) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.updates = Objects.requireNonNull(updates, "updates");
        this.roster = Objects.requireNonNull(roster, "roster");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.clock = Objects.requireNonNull(clock, "clock");
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
            logger.info("Telling {} player(s) about the {} asked for by {} ({}): {} beat(s) over {}",
                    proxy.getPlayerCount(), request.kind(), request.requestedBy(), request.source(),
                    beats.size(), request.untilDue(clock.instant()));
            cancelScheduled();
            saidNow = false;
            beats.forEach(this::schedule);
        });
    }

    /**
     * Puts one beat on the proxy's scheduler.
     *
     * <p>A zero delay is scheduled rather than run inline: Velocity accepts one, and running it here
     * would put a broadcast on whatever thread the notification listener happens to be, in the
     * middle of a method holding this object's monitor.</p>
     */
    private void schedule(final Countdown.Beat beat) {
        scheduled.add(proxy.getScheduler().buildTask(plugin, () -> {
            if (beat.announcement().kind() == Announcement.Kind.NOW) {
                synchronized (this) {
                    if (saidNow) {
                        return;
                    }
                    saidNow = true;
                    countdown.zeroReached();
                }
            }
            say(beat.announcement());
        }).delay(beat.delay()).schedule());
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
            logger.warn("Countdown {} stopped and could not be read back; nobody was told anything"
                    + " this pass", watched, failure);
            return;
        }

        logger.info("Countdown {} is over: {}", watched,
                status == null ? "the row is gone" : status);
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
        switch (announcement.kind()) {
            case COUNTDOWN -> broadcast(locale -> MessageRenderer.of(messages)
                    .format(locale, "restart.countdown", "seconds", announcement.seconds()));
            case NOW -> {
                broadcast(locale -> MessageRenderer.of(messages).get(locale, "restart.now"));
                title(locale -> MessageRenderer.of(messages).get(locale, "restart.now"));
            }
            // No chat line: the number alone, in the middle of the screen, once a second.
            case TICK -> title(locale -> MessageRenderer.of(messages)
                    .format(locale, "restart.tick", "seconds", announcement.seconds()));
            case CANCELLED -> broadcast(locale -> MessageRenderer.of(messages)
                    .get(locale, "restart.cancelled"));
            case FAILED -> broadcast(locale -> MessageRenderer.of(messages)
                    .get(locale, "restart.failed"));
        }
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
        each((player, locale) -> player.showTitle(
                Title.title(Component.empty(), render.apply(locale), TIMES)));
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
