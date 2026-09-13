package eu.nordtal.s2.smp.farm;

import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateRequest;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Whether the farm world may be deleted tonight - asked of the database, not of a second clock.
 *
 * <h2>What this replaces</h2>
 * Until 2026-09-13 the promise that the world about to be destroyed had just been saved was two
 * numbers in one file: {@code backup-time} at 04:45 against {@code farm-reset-time} at 05:00. The
 * fifteen minutes between them were the whole guarantee, and a test could hold them against each
 * other only because they happened to live in the same {@code @ConfigSpec}. The backup clock moved
 * to steward-worker (konzept §9a), so the two numbers are now in two different processes'
 * configuration and nothing can compare them at all.
 *
 * <p>So the reset stops inferring and asks: is there a {@code BACKUP} run in
 * {@code update_request} that finished, succeeded, and whose report shows a volume actually
 * written? If not, <b>the reset does not happen</b>. A night without a backup is a night without a
 * fresh farm world, which is the owner's decision and the cheaper of the two mistakes: a stale farm
 * world costs one day of worse loot, and a deleted one with no snapshot costs the world.
 *
 * <h2>Its own class, for the reason {@link DailySchedule} is its own class</h2>
 * It is the part of the decision that can be exercised without a server: a real
 * {@link eu.nordtal.s2.smp.farm.FarmWorldReset} needs Bukkit's scheduler, a loaded world and a
 * pre-generator. This needs a directory and a number.
 *
 * <h2>It blocks</h2>
 * {@link #refusal()} makes a database round trip. Every caller runs it on the plugin's async
 * executor - a Paper plugin never queries the database from the main thread, and this particular
 * query runs at the exact moment the server is about to unload a world.
 */
public final class BackupGate {

    private final UpdateDirectory updates;
    private final Duration window;

    /**
     * @param windowHours {@code config.yml#farm-reset-backup-window-hours}. Zero or less turns the
     *                    check off entirely - see {@link #isOff()}
     */
    public BackupGate(final UpdateDirectory updates, final int windowHours) {
        this.updates = Objects.requireNonNull(updates, "updates");
        this.window = Duration.ofHours(Math.max(0, windowHours));
    }

    /**
     * @return whether the check is switched off, in which case {@link #refusal()} always answers
     *         empty and never touches the database
     *
     * <p>The escape hatch exists for a stack with no steward-worker in it, where the check would
     * otherwise refuse every reset for the life of the season. The cost is that it is an off
     * switch on a guard, so the plugin says so at WARN on every start rather than letting it be
     * discovered from a config file six months later.</p>
     */
    public boolean isOff() {
        return window.isZero();
    }

    /** The window, for the log line that says what is being required of whom. */
    public Duration window() {
        return window;
    }

    /**
     * Asks the database whether a backup recent enough to authorise a reset exists.
     *
     * @return empty when the reset may go ahead, or the sentence to log at {@code WARNING} when it
     *         may not. A sentence rather than a boolean because the only thing this can do about a
     *         missing backup is tell somebody, and "false" is not something anybody can act on
     * @throws RuntimeException when the database cannot be asked. Deliberately not caught here:
     *                          the caller has the logger and the throwable is worth keeping. For
     *                          the reset an unanswerable question means the same as "no" - a
     *                          backup nobody can find is not a backup
     */
    public Optional<String> refusal() {
        if (isOff()) {
            return Optional.empty();
        }
        final Optional<UpdateRequest> backup = updates.lastSuccessfulBackup(window);
        if (backup.isPresent()) {
            return Optional.empty();
        }
        return Optional.of("THE FARM WORLD WAS NOT RESET. No network backup finished successfully"
                + " in the last " + window.toHours() + " hours, so the world that was about to be"
                + " deleted has no saved copy. Nothing has been touched and the reset will be tried"
                + " again at the next scheduled time. What to look at: steward-worker's nightly"
                + " clock and its log (`docker logs nordtal-s2-steward-worker-1`), and the"
                + " update_request table, where a BACKUP row may be sitting FAILED or may have"
                + " finished having saved nothing. `/backup now` on any surface also satisfies"
                + " this, once it comes back. Switching the check off is"
                + " config.yml#farm-reset-backup-window-hours = 0.");
    }
}
