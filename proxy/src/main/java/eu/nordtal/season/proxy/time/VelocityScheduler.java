package eu.nordtal.season.proxy.time;

import com.velocitypowered.api.proxy.ProxyServer;
import eu.nordtal.season.common.time.Scheduler;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;

/** Runs tasks on the scheduler of Velocity. {@link Scheduler#every} never runs two runs of a task at once. */
public final class VelocityScheduler implements Scheduler {

    private final ProxyServer proxy;
    private final Object plugin;

    private VelocityScheduler(final ProxyServer proxy, final Object plugin) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /** Returns the scheduler of the plugin. Velocity cancels its tasks when the proxy stops. */
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
            throw new RejectedExecutionException(stopped);
        }
    }
}
