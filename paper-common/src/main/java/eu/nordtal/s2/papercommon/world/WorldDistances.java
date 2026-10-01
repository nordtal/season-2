package eu.nordtal.s2.papercommon.world;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.slf4j.Logger;

/**
 * Sets every world's view and simulation distance, at start, on every reload and for a world loaded later.
 * A distance left unset gives a world back the value it had when this first saw it: the server's own.
 */
public final class WorldDistances implements Listener {

    private final Logger logger;
    /** Each world's distances as this found them, by name; only the main thread reads or writes it. */
    private final Map<String, Distances> found = new HashMap<>();

    private volatile Distances wanted;

    public WorldDistances(final Distances wanted, final Logger logger) {
        this.wanted = Objects.requireNonNull(wanted, "wanted");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Takes new distances for the next {@link #apply}; any thread. */
    public void want(final Distances distances) {
        wanted = Objects.requireNonNull(distances, "distances");
    }

    /** Applies the wanted distances to each of {@code worlds}; main thread. */
    public void apply(final Iterable<World> worlds) {
        worlds.forEach(this::apply);
    }

    /** Applies the wanted distances to {@code world} and logs what it reports back; main thread. */
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

    /** A world loaded after the start, such as one a plugin creates, gets the same distances. */
    @EventHandler
    public void onWorldLoad(final WorldLoadEvent event) {
        apply(event.getWorld());
    }
}
