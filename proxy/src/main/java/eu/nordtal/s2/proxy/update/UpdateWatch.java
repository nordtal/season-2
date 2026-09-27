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
 * Velocity runs {@code /update} itself for every player; what to say is {@link UpdateFollower}'s.
 */
public final class UpdateWatch {

    /** How often the answer row is re-read; short, because a person is waiting. */
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
     * @param id the request just written
     * @param user who to print it to; safe from a scheduler thread
     */
    public void watch(final long id, final NordtalUser user) {
        final UpdateFollower follower = UpdateFollower.of(id, updates::find, clock.instant());
        proxy.getScheduler()
                .buildTask(plugin, task -> {
                    final UpdateFollower.Step step = follower.poll(clock.instant());
                    if (step.failure() != null) {
                        logger.warn("Could not read update request {}", id, step.failure());
                    }
                    // Cancel sits in a finally: delivery can throw, and a finished run would otherwise repeat forever.
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
