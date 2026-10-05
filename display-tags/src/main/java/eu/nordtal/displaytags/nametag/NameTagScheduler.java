package eu.nordtal.displaytags.nametag;

import eu.nordtal.displaytags.DisplayTags;
import eu.nordtal.displaytags.api.nametag.PlayerNameTag;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

public class NameTagScheduler {
    private final DisplayTags plugin;
    private @Nullable BukkitTask task;

    public NameTagScheduler(final DisplayTags plugin) {
        this.plugin = plugin;
    }

    public void start() {
        // start() is called again on every reload, so a previous timer must not keep running.
        this.end();

        if (this.plugin.config().isEnabled()) {
            // An update interval of 0 would ask for a task on every server tick, so one tick is the floor.
            final int interval = Math.max(1, this.plugin.config().getUpdateInterval() * 20);
            this.task = this.plugin
                    .getServer()
                    .getScheduler()
                    .runTaskTimer(
                            this.plugin.host(),
                            () -> {
                                for (final PlayerNameTag nametag :
                                        this.plugin.getNameTagManager().getAll()) {
                                    nametag.tick();
                                }
                            },
                            interval,
                            interval);

            this.plugin.getLogger().info("Started the Name Tag Scheduler.");
        } else {
            this.plugin
                    .getLogger()
                    .warning(
                            "Custom name tags are disabled for this server, therefore the Name Tag Scheduler has not been started.");
            this.plugin.getLogger().warning("Turning them on in the nametags settings starts it again.");
        }
    }

    public void end() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
            this.plugin.getLogger().info("Stopped the Name Tag Scheduler.");
        }
    }
}
