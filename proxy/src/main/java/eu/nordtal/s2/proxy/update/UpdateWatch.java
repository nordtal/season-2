package eu.nordtal.s2.proxy.update;

import com.velocitypowered.api.proxy.ProxyServer;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.update.UpdateFollower;
import eu.nordtal.s2.common.update.UpdateDirectory;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * Follows an update request on the proxy and prints its answer to whoever asked.
 *
 * Velocity executes every command it knows itself; the packet never reaches a backend. Since
 * {@code /update} is a root of its own on the proxy, for every player in the network the process
 * serving {@code /update} is the proxy - regardless of which server they are standing on. A watcher
 * that draws nothing leaves an admin with "asking Steward..." and then silence.
 *
 * What to say is {@link UpdateFollower}'s, shared with the Paper consoles. This class is the
 * Velocity scheduler around it and nothing more.
 */
public final class UpdateWatch {

    /** How often the answer row is re-read. Two seconds; a person is waiting. */
    static final Duration INTERVAL = Duration.ofSeconds(2);

    private final Object plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final UpdateDirectory updates;
    private final Clock clock;

    public UpdateWatch(
            final Object plugin,
            final ProxyServer proxy,
            final Logger logger,
            final UpdateDirectory updates,
            final Clock clock) {
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
        proxy.getScheduler()
                .buildTask(plugin, task -> {
                    final UpdateFollower.Step step = follower.poll(clock.instant());
                    if (step.failure() != null) {
                        logger.warn("Could not read update request {}", id, step.failure());
                    }
                    // Cancel is in a finally: delivery can throw, and without it a finished run repeats forever.
                    try {
                        step.deliver(user);
                    } finally {
                        if (step.finished()) {
                            task.cancel();
                        }
                    }
                })
                .delay(INTERVAL)
                .repeat(INTERVAL)
                .schedule();
    }
}
