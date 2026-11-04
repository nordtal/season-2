package eu.nordtal.season.smp.rail;

import java.util.UUID;
import org.bukkit.entity.Player;

/** What {@link Rides} reports about each ride to the rider's display; each call comes on the main thread. */
public interface RideWatch {

    RideWatch NONE = new RideWatch() {};

    /** The way to {@code station} was planned, at the start or again, and {@code left} blocks remain. */
    default void planned(final Player rider, final UUID station, final String name, final double left) {}

    /** The cart moved this tick, or stood, and {@code left} blocks of the planned way remain. */
    default void moved(final Player rider, final double left) {}

    /** The rider was set down at {@code station}. */
    default void arrived(final Player rider, final UUID station, final String name) {}

    /** The ride ended, at a station or not. */
    default void ended(final Player rider) {}
}
