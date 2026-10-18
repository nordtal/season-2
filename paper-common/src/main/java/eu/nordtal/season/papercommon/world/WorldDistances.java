package eu.nordtal.season.papercommon.world;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.slf4j.Logger;

/**
 * Sets the view and simulation distance of every world.
 * An unset distance restores the value the world had when this class first saw it.
 */
public final class WorldDistances implements Listener {

    private final Logger logger;
    /** The distances of each world when this class first saw it, by name. Only the main thread uses it. */
    private final Map<String, Distances> found = new HashMap<>();

    private volatile Distances wanted;

    public WorldDistances(final Distances wanted, final Logger logger) {
        this.wanted = Objects.requireNonNull(wanted, "wanted");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Sets the distances for the next {@link #apply}. Any thread may call it. */
    public void want(final Distances distances) {
        wanted = Objects.requireNonNull(distances, "distances");
    }

    public void apply(final Iterable<World> worlds) {
        worlds.forEach(this::apply);
    }

    public void apply(final World world) {
        final Distances own = found.computeIfAbsent(
                world.getName(), name -> new Distances(world.getViewDistance(), world.getSimulationDistance()));
        final Distances target = wanted;
        final int view = target.view() != 0 ? target.view() : own.view();
        final int simulation = target.simulation() != 0 ? target.simulation() : own.simulation();
        if (world.getViewDistance() != view) {
            world.setViewDistance(view);
        }
        if (world.getSimulationDistance() != simulation) {
            world.setSimulationDistance(simulation);
        }
        logger.info(
                "World '{}' runs with view distance {} and simulation distance {}",
                world.getName(),
                world.getViewDistance(),
                world.getSimulationDistance());
    }

    @EventHandler
    public void onWorldLoad(final WorldLoadEvent event) {
        apply(event.getWorld());
    }
}
