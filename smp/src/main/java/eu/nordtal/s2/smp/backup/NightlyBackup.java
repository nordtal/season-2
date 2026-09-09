package eu.nordtal.s2.smp.backup;

import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateSource;
import eu.nordtal.s2.smp.farm.DailySchedule;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * The one clock in the network that asks for a volume backup.
 *
 * <h2>Why this lives in a Minecraft plugin</h2>
 * Because the process that <em>performs</em> the backup must not own its schedule. {@code serve} in
 * the updater has exactly one rule protecting it - it does nothing at all until a row appears in
 * {@code update_request} - and that rule is what stops a crash restart at three in the morning from
 * moving a version. A timer inside the updater would end it, however small the timer.
 *
 * <p>So the row is written from outside, and this plugin is the natural place: it is the only one
 * in the network that already runs a daily clock, for the farm world reset, and it is the process
 * whose own world is the thing most worth saving. What that costs is written down rather than
 * hidden - <b>a season with {@code smp} down has no nightly backup, and nothing else in the stack
 * notices.</b> The only evidence either way is Arcane's own backup list.</p>
 *
 * <h2>It writes a request and nothing else</h2>
 * No stop, no snapshot, no Arcane. The row is claimed by the updater, which counts thirty seconds
 * down to every player online, stops the services that hold the volumes, asks Arcane for one
 * snapshot each, starts them again and waits for every healthcheck. That is the same run
 * {@code /backup now} asks for, and there is deliberately no second path: a backup nobody was
 * warned about is the thing Arcane's own {@code StopContainers} flag would have done.
 *
 * <h2>Fifteen minutes before the farm reset</h2>
 * {@code config.yml#backup-time} defaults to 04:45 against a reset at 05:00. Running them the other
 * way round would snapshot a farm world that is about to be deleted, on servers that had just come
 * back up.
 */
public final class NightlyBackup {

    /** {@code update_request.requested_by} for a row no person asked for. */
    public static final String SENDER = "smp";

    private final Plugin plugin;
    private final UpdateDirectory directory;
    private final Executor async;
    private final DailySchedule schedule;

    private BukkitTask task;

    /**
     * @param at the configured {@code HH:mm}, or blank for "never" - which is what a local stack
     *           wants, because there is no Arcane on a laptop and the run would fail every night
     * @throws IllegalArgumentException on a time nobody can parse, deliberately: a schedule that
     *                                  silently becomes "never" is a backup nobody knows they lost
     */
    public NightlyBackup(final Plugin plugin, final UpdateDirectory directory,
                         final Executor async, final String at) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.async = Objects.requireNonNull(async, "async");
        this.schedule = at == null || at.isBlank() ? null : DailySchedule.parse(at);
    }

    /** @return the time of day, or empty when no nightly backup is scheduled at all */
    public Optional<LocalTime> at() {
        return Optional.ofNullable(schedule).map(DailySchedule::at);
    }

    /** Arms the clock. Does nothing at all when {@code backup-time} is blank. */
    public void start() {
        if (schedule == null) {
            plugin.getLogger().info("backup-time is empty, so this server asks for no nightly"
                    + " backup. Nothing else in the network will ask for one either.");
            return;
        }
        scheduleNext();
        plugin.getLogger().info("a network backup is asked for daily at " + schedule);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void scheduleNext() {
        final Duration until = schedule.until(LocalTime.now());
        // Rounded UP to the next tick, and that is the whole of it. toSeconds() floors, so a wait
        // of 04:44:59.6 became 0 seconds, the task fired while the target was still ahead, ask()
        // re-armed for another fraction of a second, and each pass wrote another BACKUP row. A
        // backup that submits itself in a tight loop is worse than one that never fires, because
        // the first row takes the lock and every one after it is refused into the log.
        final long ticks = Math.ceilDiv(until.toNanos(), 50_000_000L);
        task = Bukkit.getScheduler().runTaskLater(plugin, this::ask, Math.max(1L, ticks));
    }

    /**
     * Writes the row, then re-arms.
     *
     * <p>The re-arm happens whatever the write did. A database that was briefly unreachable at
     * 04:45 must not cost every subsequent night as well - which is what a schedule that only
     * continues on success would do, silently, for the rest of the season.</p>
     */
    private void ask() {
        // Off the main thread: this is a database round trip, and the server is behind it.
        async.execute(() -> {
            try {
                directory.submit(UpdateKind.BACKUP, UpdateSource.CONSOLE, SENDER, Duration.ZERO);
                plugin.getLogger().info("asked the updater for the nightly backup");
            } catch (final RuntimeException failure) {
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "the nightly backup could not be asked for - nothing was saved tonight",
                        failure);
            }
        });
        scheduleNext();
    }
}
