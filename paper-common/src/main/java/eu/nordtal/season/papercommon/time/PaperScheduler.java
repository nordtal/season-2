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
 * Runs Paper work later or elsewhere, by durations and never by ticks.
 * As a {@link Scheduler} it runs off the main thread.
 */
public final class PaperScheduler implements Scheduler {

    public static final Duration TICK = Duration.ofMillis(50);

    private final Plugin plugin;

    private PaperScheduler(final Plugin plugin) {
        this.plugin = plugin;
    }

    /** Returns the scheduler of {@code plugin}. The server cancels its work when the plugin is disabled. */
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

    public void onMain(final Runnable work) {
        final var _ = submit(() -> Bukkit.getScheduler().runTask(plugin, work));
    }

    public Task onMainAfter(final Duration delay, final Runnable work) {
        return submit(() -> Bukkit.getScheduler().runTaskLater(plugin, work, ticks(delay)));
    }

    public Task onMainEvery(final Duration delay, final Duration period, final Runnable work) {
        return submit(() -> Bukkit.getScheduler().runTaskTimer(plugin, work, ticks(delay), Math.max(1, ticks(period))));
    }

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

    static long ticks(final Duration duration) {
        return Math.ceilDiv(Math.max(0, duration.toMillis()), TICK.toMillis());
    }

    private static Task submit(final Supplier<BukkitTask> scheduling) {
        try {
            return scheduling.get()::cancel;
        } catch (final IllegalPluginAccessException disabled) {
            throw new RejectedExecutionException(disabled);
        }
    }
}
