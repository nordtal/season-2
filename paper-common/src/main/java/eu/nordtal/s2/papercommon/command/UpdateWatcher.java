package eu.nordtal.s2.papercommon.command;

import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.update.UpdateFollower;
import eu.nordtal.s2.common.update.UpdateDirectory;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;

/**
 * Follows an update request on a Paper server and prints its answer when it lands.
 *
 * <h2>Who this reaches, and who it does not</h2>
 * The Paper console, and nobody else. Velocity executes every command it knows itself and never
 * hands it to a backend, and since 2026-09-08 the proxy knows {@code /update} - so a <em>player</em>
 * who types it is answered by the proxy's own watcher, and this class is what a person at
 * {@code docker exec ... mc} gets. Until the same day the three Paper watchers were believed to be
 * the player's path, and the proxy drew nothing: every admin in the network got the acknowledgement
 * and never the answer.
 *
 * <h2>What is left of this class, and why</h2>
 * A Bukkit timer. What to say, when a row is finished, gone or overdue, and how much of a report
 * fits are {@link UpdateFollower}'s, decided once in {@code :commands} for every chat surface. This
 * class needs a Bukkit scheduler, which is exactly the rule for living here: code belongs in
 * {@code :paper-common} only if it needs a Paper type.
 */
public final class UpdateWatcher {

    /** How often the answer row is re-read, in ticks. Two seconds; a person is waiting. */
    private static final long CHECK_TICKS = 40L;

    private final Plugin plugin;
    private final UpdateDirectory updates;

    public UpdateWatcher(final Plugin plugin, final UpdateDirectory updates) {
        this.plugin = plugin;
        this.updates = updates;
    }

    /** The directory this watcher reads, so the commands write through the same pool. */
    public UpdateDirectory directory() {
        return updates;
    }

    /**
     * Follows one request and prints its answer when it lands.
     *
     * @param id   the request to follow
     * @param user who to print it to. Lines reach the user through {@link NordtalUser#reply} and
     *             {@link NordtalUser#replyLiteral}, and {@link PaperUser} hops onto the server
     *             thread for both - so the timer below may stay asynchronous, where a database read
     *             belongs
     */
    public void watch(final long id, final NordtalUser user) {
        final UpdateFollower follower = UpdateFollower.of(id, updates::find, Instant.now());
        final BukkitTask[] handle = new BukkitTask[1];
        handle[0] = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            final UpdateFollower.Step step = follower.poll(Instant.now());
            if (step.failure() != null) {
                plugin.getLogger().warning("Could not read update request " + id + ": "
                        + step.failure());
            }
            step.deliver(user);
            if (step.finished()) {
                handle[0].cancel();
            }
        }, CHECK_TICKS, CHECK_TICKS);
    }
}
