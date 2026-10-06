package eu.nordtal.season.displaytags.nametag;

import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.displaytags.DisplayTags;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

public class NameTagScheduler {
    private final DisplayTags plugin;
    private Scheduler.@Nullable Task task;

    public NameTagScheduler(final DisplayTags plugin) {
        this.plugin = plugin;
    }

    public void start() {
        // start() is called again on every reload, so a previous timer must not keep running.
        this.end();

        if (this.plugin.config().isEnabled()) {
            // An update interval of 0 runs on every server tick, which is the scheduler's floor.
            final Duration interval = Duration.ofSeconds(this.plugin.config().getUpdateInterval());
            this.task = PaperScheduler.of(this.plugin.host()).onMainEvery(interval, interval, () -> {
                for (final PlayerNameTag nametag :
                        this.plugin.getNameTagManager().getAll()) {
                    nametag.tick();
                }
            });

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
