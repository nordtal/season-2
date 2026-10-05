package eu.nordtal.season.papercommon.time;

import eu.nordtal.season.common.time.Scheduler;
import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * The one way Paper code runs work later or elsewhere: the server's scheduler, told durations and never ticks.
 *
 * As a {@link Scheduler} it runs work off the main thread; the {@code onMain} methods run it on the main thread.
 */
public final class PaperScheduler implements Scheduler {

    /** One server tick, for what a game measures in ticks. */
    public static final Duration TICK = Duration.ofMillis(50);

    private final Plugin plugin;

    private PaperScheduler(final Plugin plugin) {
        this.plugin = plugin;
    }

    /** Returns the scheduler of {@code plugin}, whose work the server calls off when the plugin is disabled. */
    public static PaperScheduler of(final Plugin plugin) {
        return new PaperScheduler(plugin);
    }

    @Override
    public void execute(final Runnable work) {
        final var _ = submit(() -> Bukkit.getScheduler().runTaskAsynchronously(plugin, work));
    }

    @Override
    public Task after(final Duration delay, final Runnable work) {
        return submit(() -> Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, work, ticks(delay)));
    }

    /** Runs {@code work} on the main thread at the next tick. */
    public void onMain(final Runnable work) {
        final var _ = submit(() -> Bukkit.getScheduler().runTask(plugin, work));
    }

    /** Runs {@code work} on the main thread once {@code delay} has passed, unless cancelled first. */
    public Task onMainAfter(final Duration delay, final Runnable work) {
        return submit(() -> Bukkit.getScheduler().runTaskLater(plugin, work, ticks(delay)));
    }

    /** Runs {@code work} on the main thread after {@code delay} and then every {@code period}, at least a tick. */
    public Task onMainEvery(final Duration delay, final Duration period, final Runnable work) {
        return submit(() -> Bukkit.getScheduler().runTaskTimer(plugin, work, ticks(delay), Math.max(1, ticks(period))));
    }

    /** Returns the main thread as a {@link Scheduler}, for code written against one whose work must run there. */
    public Scheduler mainThread() {
        return new Scheduler() {
            @Override
            public void execute(final Runnable work) {
                onMain(work);
            }

            @Override
            public Task after(final Duration delay, final Runnable work) {
                return onMainAfter(delay, work);
            }

            @Override
            public Task every(final Duration delay, final Duration period, final Runnable work) {
                return onMainEvery(delay, period, work);
            }
        };
    }

    /** Returns {@code duration} in whole ticks, rounded up so that nothing runs early. */
    static long ticks(final Duration duration) {
        return Math.ceilDiv(Math.max(0, duration.toMillis()), TICK.toMillis());
    }

    /** Hands {@code scheduling} to the server; a disabled plugin is refused the way any stopped scheduler refuses. */
    private static Task submit(final Supplier<BukkitTask> scheduling) {
        try {
            return scheduling.get()::cancel;
        } catch (final IllegalPluginAccessException disabled) {
            throw new RejectedExecutionException(disabled);
        }
    }
}
