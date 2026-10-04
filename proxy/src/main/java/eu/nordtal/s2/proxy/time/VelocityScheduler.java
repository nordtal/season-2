package eu.nordtal.s2.proxy.time;

import com.velocitypowered.api.proxy.ProxyServer;
import eu.nordtal.s2.common.time.Scheduler;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;

/**
 * The one way proxy code runs work later or elsewhere: Velocity's scheduler, told durations.
 *
 * Repeated work comes from {@link Scheduler#every}, so two runs never overlap, as on every other process.
 */
public final class VelocityScheduler implements Scheduler {

    private final ProxyServer proxy;
    private final Object plugin;

    private VelocityScheduler(final ProxyServer proxy, final Object plugin) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /** Returns the scheduler of {@code plugin}, whose work Velocity calls off when the proxy shuts down. */
    public static VelocityScheduler of(final ProxyServer proxy, final Object plugin) {
        return new VelocityScheduler(proxy, plugin);
    }

    @Override
    public void execute(final Runnable work) {
        final var _ = after(Duration.ZERO, work);
    }

    @Override
    public Task after(final Duration delay, final Runnable work) {
        try {
            return proxy.getScheduler()
                    .buildTask(plugin, work)
                    .delay(delay.isNegative() ? Duration.ZERO : delay)
                    .schedule()::cancel;
        } catch (final IllegalStateException stopped) {
            // Velocity's scheduler refuses work once it has shut down, the way any stopped scheduler refuses.
            throw new RejectedExecutionException(stopped);
        }
    }
}
