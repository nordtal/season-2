package eu.nordtal.displaytags.nametag;

import eu.nordtal.displaytags.DisplayTags;
import eu.nordtal.displaytags.api.nametag.PlayerNameTag;
import org.bukkit.scheduler.BukkitTask;

public class NameTagScheduler {
    private final DisplayTags plugin;
    private BukkitTask task;

    public NameTagScheduler(DisplayTags plugin) {
        this.plugin = plugin;
    }

    public void start() {
        // Never leave a previous timer running: start() is called again on every reload.
        this.end();

        if (plugin.config().nametag().isEnabled()) {
            // An update-interval of 0 would ask for a task on every server tick, so keep one tick
            // as the floor rather than letting the value fall through as a period of 0.
            int interval = Math.max(1, plugin.config().nametag().getUpdateInterval() * 20);
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
                for (PlayerNameTag nametag : plugin.getNameTagManager().getAll()) {
                    nametag.tick();
                }
            }, interval, interval);

            plugin.getLogger().info("Started the Name Tag Scheduler.");
        } else {
            plugin.getLogger().warning("Custom name tags are disabled for this server, therefore the Name Tag Scheduler has not been started.");
            plugin.getLogger().warning("If you want to enable the custom name tags again, enable them in config.yml and run /displaytags reload.");
        }
    }

    public void end() {
        if (task != null) {
            task.cancel();
            task = null;
            plugin.getLogger().info("Stopped the Name Tag Scheduler.");
        }
    }
}
