package eu.nordtal.s2.networkcontrol.update;

import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.update.UpdateFollower;
import eu.nordtal.s2.common.update.UpdateDirectory;

import com.velocitypowered.api.proxy.ProxyServer;

import org.slf4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * Follows an update request on the proxy and prints its answer to whoever asked.
 *
 * <h2>Why the proxy has to draw the answer</h2>
 * Velocity executes every command it knows itself; the packet never reaches a backend. Since
 * {@code /update} became a root of its own on 2026-09-08 the proxy knows it, so for every player in
 * the network the process serving {@code /update} <b>is</b> the proxy - regardless of which server
 * they are standing on. The same change wired this process with a watcher that drew nothing, on the
 * reasoning that "the proxy's console has the log". The console does; the player does not. Every
 * admin got "asking the updater..." and then silence, on all four commands.
 *
 * <p>What to say is {@link UpdateFollower}'s, shared with the Paper consoles. This class is the
 * Velocity scheduler around it and nothing more.</p>
 */
public final class UpdateWatch {

    /** How often the answer row is re-read. Two seconds; a person is waiting. */
    static final Duration INTERVAL = Duration.ofSeconds(2);

    private final Object plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final UpdateDirectory updates;
    private final Clock clock;

    public UpdateWatch(final Object plugin, final ProxyServer proxy, final Logger logger,
                       final UpdateDirectory updates, final Clock clock) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.updates = Objects.requireNonNull(updates, "updates");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Follows one request and prints its answer when it lands.
     *
     * @param id   the request just written
     * @param user who to print it to. Velocity's {@code Player#sendMessage} is safe from a
     *             scheduler thread, so nothing here hops anywhere
     */
    public void watch(final long id, final NordtalUser user) {
        final UpdateFollower follower = UpdateFollower.of(id, updates::find, clock.instant());
        proxy.getScheduler().buildTask(plugin, task -> {
            final UpdateFollower.Step step = follower.poll(clock.instant());
            if (step.failure() != null) {
                logger.warn("Could not read update request {}", id, step.failure());
            }
            step.deliver(user);
            if (step.finished()) {
                task.cancel();
            }
        }).delay(INTERVAL).repeat(INTERVAL).schedule();
    }
}
