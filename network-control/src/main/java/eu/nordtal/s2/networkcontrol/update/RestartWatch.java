package eu.nordtal.s2.networkcontrol.update;

import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.networkcontrol.gate.LoginRoster;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Tells every player on the network that it is about to restart, and how long they have.
 *
 * <h2>Why the proxy and not the SMP plugin</h2>
 * A restart takes the whole stack down: the proxy, all three backends, the bot and the updater. The
 * proxy is the only process that sees everybody - a backend sees its own slice, and a player
 * waiting in {@code limbo} or playing Hunger Games would otherwise be disconnected with no warning
 * at all. It also means a restart asked for <b>in Discord</b> is announced in game, which is the
 * more common case and the one a countdown attached to {@code /smp update restart} would have
 * missed entirely.
 *
 * <h2>The instant comes from the row, never from a clock here</h2>
 * {@code update_request.not_before} is written by whoever asked, as an absolute instant on the
 * database's clock. This class counts towards it and the updater refuses to act before it, so the
 * two cannot disagree - which is the whole reason the countdown length is not a setting in two
 * config files.
 *
 * <h2>Poll, not LISTEN</h2>
 * Every five seconds, one indexed lookup on a partial index. A dedicated {@code LISTEN} connection
 * per backend was considered and not built: the phase model needs one because a phase switch has to
 * feel instant, and a countdown that starts five seconds late still counts down honestly - it says
 * the number of seconds that are actually left, not the number somebody asked for.
 *
 * <h2>What it does not do</h2>
 * It does not stop anybody logging in during the last seconds, and it does not move players to
 * limbo first. Both were considered; both are more machinery running at exactly the moment the
 * network is already going down, and neither makes the restart better for anybody who is already
 * connected.
 */
public final class RestartWatch {

    /** How often the pending restart is looked for. */
    public static final Duration INTERVAL = Duration.ofSeconds(5);

    private final ProxyServer proxy;
    private final Logger logger;
    private final UpdateDirectory updates;
    private final LoginRoster roster;
    private final Messages messages;
    private final Clock clock;

    /** When to speak and what to say. All the rules are in there; none of them are here. */
    private final Countdown countdown = new Countdown();

    public RestartWatch(final ProxyServer proxy, final Logger logger, final UpdateDirectory updates,
                        final LoginRoster roster, final Messages messages, final Clock clock) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.updates = Objects.requireNonNull(updates, "updates");
        this.roster = Objects.requireNonNull(roster, "roster");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * One pass. Scheduled every {@link #INTERVAL}.
     * <p>
     * Never throws: it runs on the proxy's scheduler, and a task that throws is a task Velocity
     * stops running - the failure mode of which is a network that restarts one day with nobody
     * warned and nothing in the log saying why.
     * </p>
     */
    public void check() {
        final Optional<UpdateRequest> pending;
        try {
            pending = updates.pendingRestart();
        } catch (final RuntimeException failure) {
            // A database that cannot be reached is not a reason to announce anything. The restart
            // still happens or does not; this only decides whether players were told.
            logger.warn("Could not read the pending restart; nobody was told anything this pass",
                    failure);
            return;
        }

        if (pending.isEmpty()) {
            countdown.gone().ifPresent(this::say);
            return;
        }

        final UpdateRequest request = pending.get();
        countdown.pending(request.id(), request.secondsUntilDue(clock.instant()))
                .ifPresent(announcement -> {
                    logger.info("Telling {} player(s) about the restart asked for by {} ({}): {}",
                            proxy.getPlayerCount(), request.requestedBy(), request.source(),
                            announcement);
                    say(announcement);
                });
    }

    private void say(final Announcement announcement) {
        broadcast(locale -> Component.text(switch (announcement.kind()) {
            case COUNTDOWN -> messages.format(locale, "restart.countdown",
                    "seconds", announcement.seconds());
            case NOW -> messages.get(locale, "restart.now");
            case CANCELLED -> messages.get(locale, "restart.cancelled");
        }));
    }

    /**
     * To everybody, in their own language.
     * <p>
     * The locale comes from {@link LoginRoster}, which holds it from the login query, and falls
     * back to English for anybody the roster has no session for - which on this path is nobody, but
     * a broadcast must not be the thing that throws.
     * </p>
     */
    private void broadcast(final java.util.function.Function<Locale, Component> render) {
        for (final Player player : proxy.getAllPlayers()) {
            try {
                player.sendMessage(render.apply(roster.localeOf(player.getUniqueId())));
            } catch (final RuntimeException failure) {
                logger.warn("Could not tell {} about the restart", player.getUsername(), failure);
            }
        }
    }
}
