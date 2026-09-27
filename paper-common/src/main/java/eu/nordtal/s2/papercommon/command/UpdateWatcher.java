package eu.nordtal.s2.papercommon.command;

import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.update.UpdateFollower;
import eu.nordtal.s2.common.update.UpdateDirectory;
import java.time.Instant;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Follows an update request on a Paper server and prints its answer when it lands.
 *
 * Only the Paper console reaches it; what to say is {@link UpdateFollower}'s, and this is its Bukkit timer.
 */
public final class UpdateWatcher {

    /** How often the answer row is re-read, in ticks. */
    private static final long CHECK_TICKS = 40L;

    private final Plugin plugin;
    private final UpdateDirectory updates;

    public UpdateWatcher(final Plugin plugin, final UpdateDirectory updates) {
        this.plugin = plugin;
        this.updates = updates;
    }

    /** Returns the directory this watcher reads, so the commands write through the same pool. */
    public UpdateDirectory directory() {
        return updates;
    }

    /**
     * Follows one request and prints its answer when it lands.
     *
     * @param id   the request to follow
     * @param user who to print it to; {@link PaperUser} hops onto the server thread, so the timer stays async
     */
    public void watch(final long id, final NordtalUser user) {
        final UpdateFollower follower = UpdateFollower.of(id, updates::find, Instant.now());
        final BukkitTask[] handle = new BukkitTask[1];
        handle[0] = Bukkit.getScheduler()
                .runTaskTimerAsynchronously(
                        plugin,
                        () -> {
                            final UpdateFollower.Step step = follower.poll(Instant.now());
                            if (step.failure() != null) {
                                plugin.getLogger()
                                        .warning("Could not read update request " + id + ": " + step.failure());
                            }
                            step.deliver(user);
                            if (step.finished()) {
                                handle[0].cancel();
                            }
                        },
                        CHECK_TICKS,
                        CHECK_TICKS);
    }
}
